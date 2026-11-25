package com.vtlo.unrestrict3d.engine

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.vtlo.unrestrict3d.core.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class BackendChoice { CPU, GPU }

data class SamplerSettings(
    val temperature: Double = 0.8,
    val topK: Int = 40,
    val topP: Double = 0.95,
)

fun estimateTokens(s: String): Int = (s.length / 3.6).toInt() + 1

private fun textOf(m: Message): String =
    m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/**
 * Thin wrapper around LiteRT-LM. Adds what a phone needs: a hard context window (KV-cache size),
 * context compression into a short "memory", one-shot calls for the pipeline, and a clean unload.
 */
class LlmEngine(private val appContext: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private var sampler = SamplerSettings()
    private var systemPrompt = ""

    /** Compressed memory of earlier turns; injected into every new conversation's system prompt. */
    var memory: String = ""

    var contextWindow: Int = 4096
        private set

    val isLoaded: Boolean get() = engine != null

    suspend fun load(
        modelPath: String,
        backend: BackendChoice,
        contextWindow: Int,
        sampler: SamplerSettings,
        systemPrompt: String,
    ) = withContext(Dispatchers.IO) {
        unloadInternal()
        this@LlmEngine.contextWindow = contextWindow
        this@LlmEngine.sampler = sampler
        this@LlmEngine.systemPrompt = systemPrompt
        EventLog.log("engine.load start path=$modelPath backend=$backend ctx=$contextWindow")
        val config = EngineConfig(
            modelPath = modelPath,
            backend = when (backend) {
                BackendChoice.CPU -> Backend.CPU()
                BackendChoice.GPU -> Backend.GPU()
            },
            maxNumTokens = contextWindow,
            cacheDir = appContext.cacheDir.path,
        )
        val e = Engine(config)
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            EventLog.log("engine.load FAILED ${t.message}")
            throw t
        }
        engine = e
        conversation = null // created lazily
        EventLog.log("engine.load done")
    }

    /** Applies new sampler / system prompt; the next message starts a fresh conversation. */
    fun configure(sampler: SamplerSettings, systemPrompt: String) {
        this.sampler = sampler
        this.systemPrompt = systemPrompt
        resetConversation()
    }

    fun resetConversation() {
        runCatching { conversation?.close() }
        conversation = null
    }

    fun clearMemory() { memory = "" }

    private fun systemText(): String = buildString {
        append(systemPrompt.trim())
        if (memory.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("Memory of the earlier conversation (already summarized, do not repeat it):\n").append(memory)
        }
    }

    private fun conversationConfig(
        sys: String,
        s: SamplerSettings,
        maxOut: Int? = null,
    ): ConversationConfig {
        val sc = SamplerConfig(s.topK, s.topP, s.temperature, 0)
        return when {
            sys.isBlank() && maxOut == null -> ConversationConfig(samplerConfig = sc)
            sys.isBlank() -> ConversationConfig(samplerConfig = sc, maxOutputToken = maxOut)
            maxOut == null -> ConversationConfig(systemInstruction = Contents.of(sys), samplerConfig = sc)
            else -> ConversationConfig(systemInstruction = Contents.of(sys), samplerConfig = sc, maxOutputToken = maxOut)
        }
    }

    private fun ensureConversation(): Conversation {
        conversation?.let { return it }
        val e = engine ?: error("No model loaded")
        return e.createConversation(conversationConfig(systemText(), sampler)).also { conversation = it }
    }

    /** Streams the reply as incremental text chunks. */
    fun stream(prompt: String): Flow<String> =
        ensureConversation().sendMessageAsync(prompt).map { textOf(it) }

    /** The runtime of this version exposes no token counter: the view-model estimates from text length. */
    fun tokenCount(): Int = 0

    fun cancel() { runCatching { conversation?.cancelProcess() } }

    /**
     * Stateless single call (planner, incident repair, summarizer). Uses its own temporary
     * conversation, so it never pollutes the chat context. The chat conversation is closed first
     * (one live conversation at a time keeps RAM flat) and re-created lazily from [memory].
     */
    suspend fun oneShot(
        system: String,
        prompt: String,
        temperature: Double = 0.2,
        maxOut: Int = 1500,
    ): String = withContext(Dispatchers.IO) {
        val e = engine ?: error("No model loaded")
        resetConversation()
        val cfg = conversationConfig(system, SamplerSettings(temperature, 40, 0.9), maxOut)
        e.createConversation(cfg).use { c ->
            val sb = StringBuilder()
            c.sendMessageAsync(prompt).collect { sb.append(textOf(it)) }
            sb.toString().trim()
        }
    }

    /**
     * Context compression: folds [transcript] (+ the previous memory) into a short summary, drops the
     * heavy KV-cache by restarting the conversation, and keeps only the summary as memory.
     * Returns (new memory, estimated tokens after). Falls back to deterministic truncation if the
     * model cannot summarize (so compression can never fail the chat).
     */
    suspend fun compress(transcript: String): Pair<String, Int> {
        val source = buildString {
            if (memory.isNotBlank()) append("PREVIOUS MEMORY:\n").append(memory).append("\n\n")
            append("NEW MESSAGES:\n").append(transcript.takeLast(7000))
        }
        var summary = runCatching { oneShot(SUMMARIZER, source, 0.1, 380) }
            .onFailure { EventLog.log("compress oneShot failed: ${it.message}") }
            .getOrDefault("")
        if (summary.length < 30) {
            summary = (memory + "\n" + transcript).takeLast(900) // deterministic fallback
        }
        memory = summary.take(1500)
        resetConversation()
        EventLog.log("context compressed -> ${memory.length} chars")
        return memory to estimateTokens(memory)
    }

    suspend fun unload() = withContext(Dispatchers.IO) { unloadInternal() }

    private fun unloadInternal() {
        if (engine != null) EventLog.log("engine.unload")
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }

    companion object {
        private const val SUMMARIZER =
            "You compress a chat into a memory for yourself. Write at most 120 words, plain text, no preamble. " +
                "Keep: the user's goal, decisions, names, numbers, file names, commands, open tasks. Drop pleasantries."
    }
}
