package com.vtlo.unrestrict3d

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.litertlm.*
import com.vtlo.unrestrict3d.skills.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val BG = Color(0xFF111112)
private val TEXT = Color(0xFFF2F2EC)
private val OLIVE = Color(0xFFB2C99C)

data class ChatMsg(val text: String, val fromUser: Boolean, val streaming: Boolean = false, val tools: String = "")

data class SkillRow(val name: String, val description: String, val enabled: Boolean, val needsSecret: Boolean, val secretHint: String, val secret: String)

data class UiState(
    val status: String = "Starting…",
    val ready: Boolean = false,
    val error: String? = null,
    val backend: String = "",
    val messages: List<ChatMsg> = emptyList(),
    val generating: Boolean = false,
    val importing: Boolean = false,
    val importMb: Long = 0,
    val skillsOn: Boolean = false,
    val skills: List<SkillRow> = emptyList(),
    val mcp: List<McpServer> = emptyList(),
    val skillsMsg: String = "",
    val skillsBusy: Boolean = false,
)

/** LiteRT-LM engine + streaming chat state (engine code identical to the tested build). */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val modelFile = File(app.getExternalFilesDir(null), "model.litertlm")
    private val store = SkillStore(app)
    @Volatile private var curIdx = -1
    private var skillsDirty = false

    private fun toolUsed(note: String) {
        val i = curIdx
        replace(i) { it.copy(tools = it.tools + "⚙ " + note + "\n") }
    }

    private fun makeConversation(e: Engine): Conversation {
        val sampler = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)
        if (store.skillsOn) {
            runCatching {
                val t = AgentTools(getApplication(), store) { toolUsed(it) }
                val tools = t.build()
                val c = e.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(t.systemPrompt()), tools = tools, samplerConfig = sampler)
                )
                _ui.update { it.copy(skillsMsg = "Skills on · ${tools.size} tools" + (if (t.mcpNote.isNotEmpty()) " · " + t.mcpNote else "")) }
                return c
            }.onFailure { err -> _ui.update { it.copy(skillsMsg = "Skills failed (${err.message}); running without tools") } }
        }
        return e.createConversation(ConversationConfig(samplerConfig = sampler))
    }

    fun refreshSkills() = _ui.update {
        it.copy(
            skillsOn = store.skillsOn,
            skills = store.list().map { s -> SkillRow(s.name, s.description, store.enabled(s.name), s.requireSecret, s.secretDescription, store.secret(s.name)) },
            mcp = store.mcpServers(),
        )
    }

    private fun skillsJob(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _ui.update { it.copy(skillsBusy = true, skillsMsg = label) }
            val msg = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { e -> "Failed: ${e.message}" } }
            refreshSkills(); skillsDirty = true
            _ui.update { it.copy(skillsBusy = false, skillsMsg = msg) }
        }
    }

    fun setSkillsOn(on: Boolean) { store.skillsOn = on; skillsDirty = true; refreshSkills() }
    fun toggleSkill(n: String, on: Boolean) { store.setEnabled(n, on); skillsDirty = true; refreshSkills() }
    fun setSecret(n: String, v: String) { store.setSecret(n, v); skillsDirty = true }
    fun deleteSkill(n: String) { store.delete(n); skillsDirty = true; refreshSkills() }
    fun addSkillUrl(u: String) = skillsJob("Installing…") { "Installed ${store.installFromUrl(u).name}" }
    fun importSkillTree(uri: Uri) = skillsJob("Importing…") { "Installed ${store.installFromTree(uri).name}" }
    fun addMcp(name: String, url: String, token: String) {
        if (name.isBlank() || url.isBlank()) return
        store.addMcp(McpServer(name.trim(), url.trim(), token.trim())); skillsDirty = true; refreshSkills()
    }
    fun delMcp(n: String) { store.delMcp(n); skillsDirty = true; refreshSkills() }

    /** Rebuilds the conversation with the current skills/tools (the model starts a fresh context). */
    fun applySkills() {
        if (!skillsDirty) return
        val e = engine ?: return
        if (_ui.value.generating) return
        skillsDirty = false
        viewModelScope.launch {
            _ui.update { it.copy(skillsBusy = true, skillsMsg = "Applying…") }
            withContext(Dispatchers.IO) {
                runCatching { conversation?.close() }
                conversation = runCatching { makeConversation(e) }.getOrElse { err ->
                    _ui.update { it.copy(skillsMsg = "Apply failed: ${err.message}") }
                    e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)))
                }
            }
            _ui.update { it.copy(skillsBusy = false, messages = it.messages + ChatMsg(it.skillsMsg.ifBlank { "Skills updated" } + " (new context)", false)) }
        }
    }

    init {
        refreshSkills()
        initEngine()
    }

    @OptIn(ExperimentalApi::class)
    private fun initEngine() {
        viewModelScope.launch {
            if (!modelFile.exists()) {
                _ui.update {
                    it.copy(
                        status = "No model yet",
                        error = "Tap IMPORT MODEL and pick the model file (.litertlm).\n\n" +
                                "Or copy it to:\n${modelFile.absolutePath}",
                    )
                }
                return@launch
            }
            withContext(Dispatchers.IO) {
                Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
                val mtp = runCatching {                 // does the .litertlm ship an MTP drafter?
                    ModelInfo.from(modelFile.absolutePath).use { (it as? LlmCapability)?.hasSpeculativeDecodingSupport() ?: false }
                }.getOrDefault(false)
                val cache = getApplication<Application>().cacheDir.absolutePath
                // GPU+MTP first, CPU fallback. enableSpeculativeDecoding is read at Engine() build.
                for ((be, label, spec) in listOf(
                    Triple(Backend.GPU(), "GPU", mtp),
                    Triple(Backend.CPU(), "CPU", false),
                )) {
                    _ui.update { it.copy(status = "Loading model on $label…") }
                    ExperimentalFlags.enableSpeculativeDecoding = if (spec) true else null
                    val ok = runCatching {
                        val e = Engine(
                            EngineConfig(
                                modelPath = modelFile.absolutePath, backend = be,
                                maxNumTokens = 4096, cacheDir = cache,
                            )
                        )
                        e.initialize()
                        val c = makeConversation(e)
                        engine = e; conversation = c
                        _ui.update {
                            it.copy(
                                ready = true, error = null, status = "Ready",
                                backend = label + (if (spec) " · MTP" else "")
                            )
                        }
                    }.isSuccess
                    if (ok) return@withContext
                }
                _ui.update { it.copy(status = "Failed", error = "Engine init failed on GPU and CPU.") }
            }
        }
    }

    /** Copies the picked model file into the app's files dir, then loads it. */
    fun importModel(uri: Uri) {
        if (_ui.value.importing || _ui.value.generating) return
        viewModelScope.launch {
            _ui.update { it.copy(importing = true, importMb = 0, error = null, status = "Importing…") }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    runCatching { conversation?.close() }; runCatching { engine?.close() }
                    conversation = null; engine = null
                    val part = File(modelFile.path + ".part")
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { inp ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20)
                            var total = 0L
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); total += n
                                _ui.update { it.copy(importMb = total shr 20) }
                            }
                        }
                    }
                    modelFile.delete()
                    check(part.renameTo(modelFile)) { "rename failed" }
                }
            }
            _ui.update { it.copy(importing = false, ready = false) }
            if (ok.isSuccess) initEngine()
            else _ui.update { it.copy(status = "Import failed", error = ok.exceptionOrNull()?.message ?: "unknown error") }
        }
    }

    fun send(text: String) {
        val conv = conversation ?: return
        if (_ui.value.generating || text.isBlank()) return
        val msgs = _ui.value.messages + ChatMsg(text, true) + ChatMsg("", false, streaming = true)
        val idx = msgs.lastIndex
        curIdx = idx
        _ui.update { it.copy(messages = msgs, generating = true) }
        val sb = StringBuilder()
        // Callback overload: the Flow overload crashes on a coroutines ABI mismatch.
        conv.sendMessageAsync(
            Contents.of(listOf(Content.Text(text))),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (message.toolCalls.isNotEmpty()) return   // tool turn: tools report themselves
                    sb.append(
                        if (store.skillsOn) message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        else message.toString()
                    )
                    replace(idx) { it.copy(text = sb.toString()) }
                }

                override fun onDone() = finish(idx)
                override fun onError(throwable: Throwable) {
                    if (throwable !is CancellationException)
                        replace(idx) { it.copy(text = it.text + "\n[error: ${throwable.message}]") }
                    finish(idx)
                }
            },
            emptyMap(),
        )
    }

    private fun finish(idx: Int) {
        replace(idx) { it.copy(streaming = false) }
        _ui.update { it.copy(generating = false) }
    }

    fun stop() {
        runCatching { conversation?.cancelProcess() }
    }

    private fun replace(i: Int, f: (ChatMsg) -> ChatMsg) = _ui.update { s ->
        if (i in s.messages.indices)
            s.copy(messages = s.messages.toMutableList().also { it[i] = f(it[i]) })
        else s
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { conversation?.cancelProcess() }
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = android.graphics.Color.parseColor("#111112")
        enableEdgeToEdge(SystemBarStyle.dark(bg), SystemBarStyle.dark(bg))
        setContent {
            val scheme = darkColorScheme(
                background = BG, surface = BG, onBackground = TEXT, onSurface = TEXT,
                primary = OLIVE, onPrimary = Color(0xFF10140C),
                primaryContainer = Color(0xFF3C4A2E), onPrimaryContainer = TEXT,
                surfaceVariant = Color(0xFF26262A), onSurfaceVariant = TEXT,
            )
            MaterialTheme(colorScheme = scheme) { ChatScreen() }
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) list.animateScrollToItem(ui.messages.lastIndex)
    }
    val chatMode = ui.messages.isNotEmpty()
    var showSkills by remember { mutableStateOf(false) }
    if (showSkills) SkillsSheet(ui, vm) { showSkills = false; vm.applySkills() }
    Scaffold(
        containerColor = BG,
        bottomBar = {
            InputBar(
                input, { input = it }, ui.ready, ui.generating,
                onSend = { vm.send(input); input = "" }, onStop = vm::stop
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            Header(ui, chatMode) { showSkills = true }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!chatMode) {
                    Image(
                        painterResource(R.drawable.robot_bg), null,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter,
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color(0x99111112), 0.5f to Color.Transparent, 1f to Color(0xCC111112))
                        )
                    )
                }
                val none = PaddingValues(0.dp)
                when {
                    ui.importing -> Hint("Importing…", "${ui.importMb} MB copied", none, spinner = true)
                    ui.error != null -> Hint(ui.status, ui.error!!, none, mono = true, action = "IMPORT MODEL") {
                        picker.launch(arrayOf("*/*"))
                    }
                    !ui.ready -> Hint(ui.status, null, none, spinner = true)
                    !chatMode -> Hint("", ui.backend.ifEmpty { "Ready" }, none)
                    else -> LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { items(ui.messages) { Bubble(it) } }
                }
            }
        }
    }
}

@Composable
private fun Header(ui: UiState, chatMode: Boolean, onSkills: () -> Unit) {
    val h = if (chatMode) 84.dp else 120.dp
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().height(h + inset)) {
        AnimatedBanner(Modifier.fillMaxSize(), scrimTo = BG)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VTLo_unrestrict3d", color = TEXT, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onSkills) { Text(if (ui.skillsOn) "SKILLS ●" else "SKILLS", color = OLIVE) }
            }
            if (ui.ready && ui.backend.isNotEmpty())
                Text("● ${ui.backend}", color = OLIVE, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                (if (m.tools.isNotEmpty()) m.tools + "\n" else "") + m.text + if (m.streaming) " ▍" else "",
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = TEXT, style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun InputBar(
    input: String, onInput: (String) -> Unit, ready: Boolean, generating: Boolean,
    onSend: () -> Unit, onStop: () -> Unit,
) {
    Surface(color = BG, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                input, onInput, Modifier.weight(1f),
                enabled = !generating, placeholder = { Text("Message") }, maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            if (generating) FilledIconButton(onStop) { Icon(Icons.Filled.Stop, "Stop") }
            else FilledIconButton(onSend, enabled = ready && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Hint(
    title: String, body: String?, pad: PaddingValues, mono: Boolean = false, spinner: Boolean = false,
    action: String? = null, onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) {
            CircularProgressIndicator(); Spacer(Modifier.height(16.dp))
        }
        if (title.isNotEmpty()) Text(title, color = TEXT, style = MaterialTheme.typography.titleMedium)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                body, color = TEXT, style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onAction) { Text(action) }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsSheet(ui: UiState, vm: ChatViewModel, onClose: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var mName by remember { mutableStateOf("") }
    var mUrl by remember { mutableStateOf("") }
    var mTok by remember { mutableStateOf("") }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) vm.importSkillTree(u) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color(0xFF1A1A1C), contentColor = TEXT) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skills & tools", color = TEXT, style = MaterialTheme.typography.titleMedium)
                    Text("The model can load skills, run their scripts, start phone actions and call MCP tools.", color = TEXT, style = MaterialTheme.typography.bodySmall)
                }
                Switch(ui.skillsOn, { vm.setSkillsOn(it) })
            }
            if (ui.skillsMsg.isNotBlank()) Text(ui.skillsMsg, color = OLIVE, style = MaterialTheme.typography.bodySmall)
            Text("SKILLS", color = OLIVE, style = MaterialTheme.typography.labelMedium)
            if (ui.skills.isEmpty()) Text("None yet. Add one by URL (folder with SKILL.md) or import a folder.", color = TEXT, style = MaterialTheme.typography.bodySmall)
            ui.skills.forEach { sk ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(sk.enabled, { vm.toggleSkill(sk.name, it) })
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sk.name, color = TEXT, style = MaterialTheme.typography.bodyMedium)
                            Text(sk.description, color = TEXT, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        TextButton({ vm.deleteSkill(sk.name) }) { Text("✕", color = TEXT) }
                    }
                    if (sk.needsSecret) {
                        var sec by remember(sk.name) { mutableStateOf(sk.secret) }
                        OutlinedTextField(sec, { sec = it; vm.setSecret(sk.name, it) }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(sk.secretHint.ifBlank { "Secret / API key" }) })
                    }
                }
            }
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Skill folder URL") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.addSkillUrl(url); url = "" }, enabled = url.isNotBlank() && !ui.skillsBusy) { Text("ADD FROM URL") }
                OutlinedButton({ tree.launch(null) }, enabled = !ui.skillsBusy) { Text("IMPORT FOLDER") }
            }
            Spacer(Modifier.height(6.dp))
            Text("MCP SERVERS (Streamable HTTP)", color = OLIVE, style = MaterialTheme.typography.labelMedium)
            ui.mcp.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = TEXT, style = MaterialTheme.typography.bodyMedium)
                        Text(m.url, color = TEXT, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    TextButton({ vm.delMcp(m.name) }) { Text("✕", color = TEXT) }
                }
            }
            OutlinedTextField(mName, { mName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
            OutlinedTextField(mUrl, { mUrl = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("MCP URL") })
            OutlinedTextField(mTok, { mTok = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Bearer token (optional)") })
            Button({ vm.addMcp(mName, mUrl, mTok); mName = ""; mUrl = ""; mTok = "" }, enabled = mName.isNotBlank() && mUrl.isNotBlank()) { Text("ADD SERVER") }
            Spacer(Modifier.height(4.dp))
            Button(onClose, Modifier.fillMaxWidth()) { Text("APPLY") }
            Text("Applying starts a fresh model context so the new tools are known.", color = TEXT, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }
}
