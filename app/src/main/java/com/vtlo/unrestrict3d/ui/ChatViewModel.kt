package com.vtlo.unrestrict3d.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vtlo.unrestrict3d.MemoryEvents
import com.vtlo.unrestrict3d.core.DiagReport
import com.vtlo.unrestrict3d.core.Diagnostics
import com.vtlo.unrestrict3d.core.EventLog
import com.vtlo.unrestrict3d.core.KeepAlive
import com.vtlo.unrestrict3d.core.MemSnap
import com.vtlo.unrestrict3d.core.RootTools
import com.vtlo.unrestrict3d.data.AppSettings
import com.vtlo.unrestrict3d.data.ChatStore
import com.vtlo.unrestrict3d.data.DefaultModel
import com.vtlo.unrestrict3d.data.ModelDownloader
import com.vtlo.unrestrict3d.data.ModelFile
import com.vtlo.unrestrict3d.data.ModelStore
import com.vtlo.unrestrict3d.data.Prefs
import com.vtlo.unrestrict3d.data.StoredMessage
import com.vtlo.unrestrict3d.engine.LlmEngine
import com.vtlo.unrestrict3d.engine.estimateTokens
import com.vtlo.unrestrict3d.pipeline.Dispatcher
import com.vtlo.unrestrict3d.pipeline.ModelGate
import com.vtlo.unrestrict3d.pipeline.PipelineUi
import com.vtlo.unrestrict3d.pipeline.RunStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class Role { USER, ASSISTANT, SYSTEM }
enum class Mode { CHAT, AUTO }
enum class ModelState { NONE, LOADING, READY, SLEEPING }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
)

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val modelName: String? = null,
    val modelState: ModelState = ModelState.NONE,
    val busyLabel: String? = null,
    val importProgress: Float? = null,
    val generating: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val models: List<ModelFile> = emptyList(),
    val mode: Mode = Mode.CHAT,
    val pipeline: PipelineUi? = null,
    val resumable: String? = null,
    val diag: DiagReport? = null,
    val mem: MemSnap? = null,
    val ctxUsed: Int = 0,
    val rootOk: Boolean? = null,
    val resources: String? = null,
    val defaultInstalled: Boolean = false,
)

class ChatViewModel(private val app: Application) : AndroidViewModel(app) {

    val engine = LlmEngine(app)
    private val store = ModelStore(app)
    private val downloader = ModelDownloader(app)
    private val prefs = Prefs(app)
    private val chats = ChatStore(app)
    private val runsRoot = File(app.filesDir, "runs").apply { mkdirs() }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var nextId = 1L
    private var genJob: Job? = null
    private var pipeJob: Job? = null
    private var busyJob: Job? = null
    private var idleJob: Job? = null
    private var emergency: String? = null
    private var compressedCount = 0 // non-system messages already folded into engine.memory
    private val loadLock = Mutex()

    private val s get() = _state.value.settings

    private val gate = object : ModelGate {
        override suspend fun ask(system: String, prompt: String, maxOut: Int) =
            engine.oneShot(system, prompt, 0.2, maxOut)
        override suspend fun ensureLoaded() = this@ChatViewModel.ensureLoaded()
        override suspend fun release(reason: String) = releaseModel(reason, announce = false)
    }

    init {
        val settings = prefs.load()
        _state.value = UiState(
            settings = settings,
            models = store.list(),
            defaultInstalled = downloader.isInstalled(),
            diag = Diagnostics.report(app),
            resumable = RunStore.resumable(runsRoot)?.let { "${it.goal.take(50)} · step ${it.next + 1}/${it.steps.size}" },
        )
        refreshModelName()
        restoreChat()
        EventLog.log("vm init; model=${settings.lastModelPath.substringAfterLast('/')} ctx=${settings.contextWindow}")

        viewModelScope.launch { // light RAM meter
            while (true) {
                _state.update { it.copy(mem = Diagnostics.mem(app), ctxUsed = contextUsed()) }
                delay(5000)
            }
        }
        viewModelScope.launch { // system memory pressure -> free the model before Android kills us
            MemoryEvents.flow.collect { level ->
                if (level >= 15 && (level < 20 || level >= 40)) {
                    if (_state.value.generating) {
                        emergency = "system memory pressure (trim $level)"
                        engine.cancel(); genJob?.cancel()
                    } else if (engine.isLoaded && pipeJob?.isActive != true) {
                        releaseModel("system memory pressure (trim $level)", announce = true)
                    }
                }
            }
        }
        if (settings.rootBoost) viewModelScope.launch {
            val ok = RootTools.isAvailable(force = true)
            _state.update { it.copy(rootOk = ok) }
            if (ok) RootTools.protectSelf()
        }
        if (downloader.isInstalled() && settings.lastModelPath != downloader.target.path) {
            saveSettings(settings.copy(lastModelPath = downloader.target.path))
        }
        if (downloader.isInstalled() || (settings.autoLoadLast && settings.lastModelPath.isNotBlank())) {
            viewModelScope.launch { ensureLoaded() }
        }
    }

    // ---- model lifecycle -------------------------------------------------------------------

    private fun refreshModelName() = _state.update {
        val p = it.settings.lastModelPath
        val ok = p.isNotBlank() && File(p).exists()
        it.copy(
            modelName = if (ok) File(p).name else null,
            modelState = when {
                !ok -> ModelState.NONE
                engine.isLoaded -> ModelState.READY
                else -> ModelState.SLEEPING
            },
        )
    }

    /** Loads the selected model if it is not in RAM (lazy: first message wakes a sleeping model). */
    private suspend fun ensureLoaded(): Boolean = loadLock.withLock {
        if (engine.isLoaded) return@withLock true
        val path = s.lastModelPath
        if (path.isBlank() || !File(path).exists()) {
            sys("No model selected. Use the built-in model download or import your own file.")
            return@withLock false
        }
        _state.update { it.copy(modelState = ModelState.LOADING, busyLabel = "LOADING ${File(path).name.uppercase()}") }
        KeepAlive.acquire(app, "Loading model…")
        try {
            if (s.rootBoost) { RootTools.protectSelf(); RootTools.freeMemory() }
            engine.load(path, s.backend, s.contextWindow, s.sampler, s.systemPrompt)
            _state.update { it.copy(modelState = ModelState.READY) }
            true
        } catch (t: Throwable) {
            _state.update { it.copy(modelState = ModelState.SLEEPING) }
            sys("Load failed: ${t.message ?: t.javaClass.simpleName}" +
                (if (s.backend.name == "GPU") "\nTip: try the CPU backend in settings." else "") +
                "\nTip: lower the context window in settings to use less RAM.")
            false
        } finally {
            _state.update { it.copy(busyLabel = null) }
            KeepAlive.release(app)
        }
    }

    /** Memory cut-out: drops the model + KV cache from RAM; it reloads on demand. */
    private suspend fun releaseModel(reason: String, announce: Boolean) {
        if (!engine.isLoaded) return
        engine.cancel()
        engine.unload()
        System.gc()
        if (s.rootBoost) RootTools.freeMemory(killCached = false)
        EventLog.log("model released: $reason")
        refreshModelName()
        _state.update { it.copy(modelState = ModelState.SLEEPING) }
        if (announce) sys("Model released from RAM ($reason). It reloads on the next message.")
    }

    fun loadModel(path: String) {
        saveSettings(s.copy(lastModelPath = path))
        viewModelScope.launch {
            stop(); pipeJob?.cancel()
            releaseModel("switching model", announce = false)
            refreshModelName()
            if (ensureLoaded()) sys("Model ready: ${File(path).name} [${s.backend}, ctx ${s.contextWindow}]")
        }
    }

    fun loadFromPath(raw: String) {
        val path = raw.trim()
        val f = File(path)
        if (f.canRead()) { loadModel(path); return }
        busyJob = viewModelScope.launch {
            if (!RootTools.isAvailable(force = true)) {
                sys("Cannot read $path. Enable root in settings, or import the file with the picker."); return@launch
            }
            _state.update { it.copy(busyLabel = "COPYING VIA ROOT", importProgress = -1f) }
            val dest = File(store.modelsDir, f.name).path
            val ok = RootTools.importFile(path, dest)
            _state.update { it.copy(busyLabel = null, importProgress = null, models = store.list()) }
            if (ok) loadModel(dest) else sys("Root copy failed for $path")
        }
    }

    fun importModel(uri: Uri) {
        busyJob = viewModelScope.launch {
            KeepAlive.acquire(app, "Importing model…")
            _state.update { it.copy(busyLabel = "IMPORTING MODEL", importProgress = 0f) }
            try {
                val f = store.import(uri) { p -> _state.update { it.copy(importProgress = p) } }
                _state.update { it.copy(models = store.list(), busyLabel = null, importProgress = null) }
                loadModel(f.path)
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, importProgress = null) }
                if (t !is CancellationException) sys("Import failed: ${t.message ?: t.javaClass.simpleName}")
            } finally { KeepAlive.release(app) }
        }
    }

    /** The built-in model: hardcoded source, resumable download, then auto-load. */
    fun downloadDefault() {
        busyJob = viewModelScope.launch {
            KeepAlive.acquire(app, "Downloading ${DefaultModel.NAME}…")
            _state.update { it.copy(busyLabel = "DOWNLOADING ${DefaultModel.NAME.uppercase()}  (5.1 GB)", importProgress = 0f) }
            try {
                val f = downloader.download { p -> _state.update { it.copy(importProgress = p) } }
                _state.update { it.copy(models = store.list(), busyLabel = null, importProgress = null, defaultInstalled = true) }
                loadModel(f.path)
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, importProgress = null) }
                if (t !is CancellationException) sys("Download failed: ${t.message ?: t.javaClass.simpleName}\nYou can resume it: the partial file is kept.")
            } finally { KeepAlive.release(app) }
        }
    }

    fun cancelBusy() { busyJob?.cancel() }

    fun deleteModel(path: String) {
        viewModelScope.launch {
            if (s.lastModelPath == path) {
                releaseModel("deleting model", false)
                saveSettings(s.copy(lastModelPath = ""))
            }
            store.delete(path)
            _state.update { it.copy(models = store.list(), defaultInstalled = downloader.isInstalled()) }
            refreshModelName()
        }
    }

    // ---- chat --------------------------------------------------------------------------------

    private fun busyNow() = _state.value.generating || pipeJob?.isActive == true || _state.value.busyLabel != null

    fun setMode(m: Mode) = _state.update { it.copy(mode = m) }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busyNow()) return
        if (_state.value.mode == Mode.AUTO) { startPipeline(prompt); return }
        idleJob?.cancel()
        val reply = ChatMessage(nextId++, Role.ASSISTANT, "", streaming = true)
        _state.update {
            it.copy(messages = it.messages + ChatMessage(nextId++, Role.USER, prompt) + reply, generating = true)
        }
        genJob = viewModelScope.launch {
            KeepAlive.acquire(app, "Generating…")
            emergency = null
            var watchdog: Job? = null
            try {
                if (!ensureLoaded()) { append(reply.id, "[no model loaded]"); return@launch }
                maybeCompress(prompt)
                watchdog = launch { memoryWatchdog() }
                EventLog.log("generate start")
                engine.stream(prompt)
                    .catch { e -> if (e !is CancellationException) append(reply.id, "\n[error: ${e.message}]") }
                    .collect { chunk -> append(reply.id, chunk) }
                EventLog.log("generate done")
            } catch (_: CancellationException) {
                // stopped by the user or by the memory guard
            } finally {
                watchdog?.cancel()
                withContext(NonCancellable) {
                    emergency?.let { why ->
                        append(reply.id, "\n[stopped: $why]")
                        releaseModel(why, announce = true)
                    }
                    finish(reply.id)
                    persist()
                    KeepAlive.release(app)
                    scheduleIdleRelease()
                }
            }
        }
    }

    private suspend fun memoryWatchdog() {
        while (true) {
            delay(2000)
            val m = Diagnostics.mem(app)
            _state.update { it.copy(mem = m, ctxUsed = contextUsed()) }
            if (m.low || m.availMb < 350) {
                EventLog.log("watchdog: critical memory avail=${m.availMb}MB low=${m.low}")
                emergency = "memory critical (${m.availMb} MB free)"
                engine.cancel(); genJob?.cancel()
                return
            }
        }
    }

    fun stop() { engine.cancel(); genJob?.cancel() }

    fun newChat() {
        stop()
        engine.resetConversation(); engine.clearMemory()
        compressedCount = 0
        _state.update { it.copy(messages = emptyList(), generating = false, ctxUsed = 0) }
        persist()
    }

    // ---- context compression -----------------------------------------------------------------

    private fun nonSystem() = _state.value.messages.filter { it.role != Role.SYSTEM }

    private fun contextUsed(): Int {
        val pending = nonSystem().drop(compressedCount).sumOf { estimateTokens(it.text) }
        return maxOf(engine.tokenCount(), estimateTokens(engine.memory) + pending)
    }

    private suspend fun maybeCompress(prompt: String) {
        if (!s.autoCompress) return
        val used = contextUsed() + estimateTokens(prompt)
        if (used > s.contextWindow * 0.70) compressNow(auto = true)
    }

    /** Folds the transcript into a short memory and drops the KV cache. */
    suspend fun compressNow(auto: Boolean) {
        val all = nonSystem()
        val upTo = if (auto) all.size - 2 else all.size // auto: the live prompt/reply are not folded
        val transcript = all.take(maxOf(upTo, 0)).drop(compressedCount)
            .joinToString("\n") { (if (it.role == Role.USER) "User: " else "Assistant: ") + it.text }
        if (transcript.isBlank() && engine.memory.isBlank()) return
        val before = contextUsed()
        sys("compressing context…")
        val (_, after) = engine.compress(transcript)
        compressedCount = maxOf(upTo, 0)
        if (s.rootBoost) RootTools.freeMemory(killCached = false) else System.gc()
        sys("context compressed: ≈$before → ≈$after tokens")
        persist()
    }

    fun compressManually() { viewModelScope.launch { compressNow(auto = false) } }

    // ---- idle release ------------------------------------------------------------------------

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        val min = s.idleReleaseMin
        if (min <= 0) return
        idleJob = viewModelScope.launch {
            delay(min * 60_000L)
            if (!busyNow()) releaseModel("idle for $min min", announce = false)
        }
    }

    // ---- pipeline (AI plans once, code executes, AI recalled on stops) ------------------------

    private fun dispatcher() = Dispatcher(
        gate = gate,
        rootAvailable = { RootTools.isAvailable() },
        settings = { s },
        ui = { f -> _state.update { it.copy(pipeline = f(it.pipeline ?: PipelineUi())) } },
        runsRoot = runsRoot,
    )

    fun startPipeline(goal: String) {
        idleJob?.cancel()
        _state.update { it.copy(messages = it.messages + ChatMessage(nextId++, Role.USER, goal)) }
        runPipeline { dispatcher().start(goal) }
    }

    fun resumePipeline() {
        val dir = RunStore.resumable(runsRoot)?.dir ?: return
        _state.update { it.copy(resumable = null) }
        runPipeline { dispatcher().resume(dir) }
    }

    private fun runPipeline(block: suspend () -> String) {
        pipeJob = viewModelScope.launch {
            KeepAlive.acquire(app, "Pipeline running…")
            if (s.rootBoost) RootTools.protectSelf()
            val result = try { block() }
            catch (c: CancellationException) { "Pipeline stopped. Progress is saved: you can resume it." }
            catch (t: Throwable) { EventLog.log("pipeline crash: $t"); "Pipeline error: ${t.message ?: t.javaClass.simpleName}" }
            withContext(NonCancellable) {
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(nextId++, Role.ASSISTANT, result),
                        pipeline = it.pipeline?.copy(running = false, approval = null),
                        resumable = RunStore.resumable(runsRoot)?.let { r -> "${r.goal.take(50)} · step ${r.next + 1}/${r.steps.size}" },
                    )
                }
                persist()
                KeepAlive.release(app)
                scheduleIdleRelease()
            }
        }
    }

    fun stopPipeline() { engine.cancel(); pipeJob?.cancel() }
    fun dismissPipeline() = _state.update { it.copy(pipeline = null) }
    fun answerApproval(code: Int) { _state.value.pipeline?.approval?.reply?.complete(code) }

    // ---- root tools --------------------------------------------------------------------------

    fun rootFreeNow() = viewModelScope.launch {
        val r = RootTools.freeMemory()
        _state.update { it.copy(rootOk = RootTools.isAvailable(), mem = Diagnostics.mem(app)) }
        sys("root: memory freed\n$r")
    }

    fun rootProtectNow() = viewModelScope.launch {
        val ok = RootTools.protectSelf()
        sys(if (ok) "root: app protected from the low-memory killer" else "root not available")
    }

    fun showResources() = viewModelScope.launch {
        val m = Diagnostics.mem(app)
        val top = RootTools.topProcesses()
        _state.update { it.copy(resources = "free ${m.availMb} MB / ${m.totalMb} MB · app RSS ${m.rssMb} MB\n$top") }
    }

    fun clearResources() = _state.update { it.copy(resources = null) }

    // ---- diagnostics -------------------------------------------------------------------------

    fun dismissDiag() { Diagnostics.acknowledge(app); _state.update { it.copy(diag = null) } }

    // ---- settings ----------------------------------------------------------------------------

    fun applySettings(new: AppSettings) {
        val old = s
        saveSettings(new)
        if (new.rootBoost && !old.rootBoost) viewModelScope.launch {
            val ok = RootTools.isAvailable(force = true)
            _state.update { it.copy(rootOk = ok) }
            if (ok) { RootTools.protectSelf(); sys("root granted: boost active") }
            else sys("root not granted: boost stays inactive")
        }
        if (engine.isLoaded && (new.backend != old.backend || new.contextWindow != old.contextWindow)) {
            viewModelScope.launch { releaseModel("settings changed", false); ensureLoaded() }
        } else if (engine.isLoaded && (new.sampler != old.sampler || new.systemPrompt != old.systemPrompt)) {
            engine.configure(new.sampler, new.systemPrompt)
        }
        scheduleIdleRelease()
    }

    private fun saveSettings(new: AppSettings) {
        prefs.save(new)
        _state.update { it.copy(settings = new) }
    }

    // ---- persistence + helpers ---------------------------------------------------------------

    private fun restoreChat() {
        val c = chats.load() ?: return
        val restored = c.messages.map {
            ChatMessage(nextId++, runCatching { Role.valueOf(it.role) }.getOrDefault(Role.SYSTEM), it.text)
        }
        engine.memory = c.memory.ifBlank {
            restored.filter { it.role != Role.SYSTEM }.takeLast(4).joinToString("\n") { it.text.take(300) }.takeLast(900)
        }
        compressedCount = restored.count { it.role != Role.SYSTEM }
        _state.update { it.copy(messages = restored) }
    }

    private fun persist() {
        chats.save(engine.memory, _state.value.messages.filter { !it.streaming }.map { StoredMessage(it.role.name, it.text) })
    }

    private fun append(id: Long, chunk: String) = _state.update { st ->
        st.copy(messages = st.messages.map { if (it.id == id) it.copy(text = it.text + chunk) else it })
    }

    private fun finish(id: Long) = _state.update { st ->
        st.copy(generating = false, messages = st.messages.map { if (it.id == id) it.copy(streaming = false) else it })
    }

    private fun sys(text: String) = _state.update {
        it.copy(messages = it.messages + ChatMessage(nextId++, Role.SYSTEM, text))
    }

    override fun onCleared() {
        engine.cancel()
        persist()
        // Unloading is quick and idempotent; run it off the (cancelled) viewModelScope.
        Thread { runCatching { kotlinx.coroutines.runBlocking { engine.unload() } } }.start()
    }
}
