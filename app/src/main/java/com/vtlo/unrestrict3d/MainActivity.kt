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

data class ChatMsg(val text: String, val fromUser: Boolean, val streaming: Boolean = false)

data class UiState(
    val status: String = "Starting…",
    val ready: Boolean = false,
    val error: String? = null,
    val backend: String = "",
    val messages: List<ChatMsg> = emptyList(),
    val generating: Boolean = false,
    val importing: Boolean = false,
    val importMb: Long = 0,
)

/** LiteRT-LM engine + streaming chat state (engine code identical to the tested build). */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val modelFile = File(app.getExternalFilesDir(null), "model.litertlm")

    init {
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
                        val c = e.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)
                            )
                        )
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
        _ui.update { it.copy(messages = msgs, generating = true) }
        val sb = StringBuilder()
        // Callback overload: the Flow overload crashes on a coroutines ABI mismatch.
        conv.sendMessageAsync(
            Contents.of(listOf(Content.Text(text))),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    sb.append(message.toString())
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
            Header(ui, chatMode)
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
private fun Header(ui: UiState, chatMode: Boolean) {
    val h = if (chatMode) 84.dp else 120.dp
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().height(h + inset)) {
        AnimatedBanner(Modifier.fillMaxSize(), scrimTo = BG)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text("VTLo_unrestrict3d", color = TEXT, style = MaterialTheme.typography.titleLarge)
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
                m.text + if (m.streaming) " ▍" else "",
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
