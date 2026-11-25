package com.vtlo.unrestrict3d.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vtlo.unrestrict3d.R
import com.vtlo.unrestrict3d.pipeline.PipelineUi

class ChatActions(
    val onSend: (String) -> Unit,
    val onStop: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onPickModel: () -> Unit,
    val onDownloadDefault: () -> Unit,
    val onMode: (Mode) -> Unit,
    val onStopPipeline: () -> Unit,
    val onDismissPipeline: () -> Unit,
    val onApproval: (Int) -> Unit,
    val onResume: () -> Unit,
    val onDismissDiag: () -> Unit,
    val onCancelBusy: () -> Unit,
)

/**
 * Home = the reference render (robot pointing at you, fog fading to black).
 * Once a chat is open the background becomes plain darkest grey and all text is high-contrast.
 */
@Composable
fun ChatScreen(state: UiState, a: ChatActions) {
    val chatMode = state.messages.isNotEmpty() || state.pipeline != null
    val robotAlpha by animateFloatAsState(if (chatMode) 0f else 1f, tween(400), label = "robot")
    val bg by animateColorAsState(if (chatMode) Vtlo.Chat else Vtlo.Void, tween(400), label = "bg")
    var showDiag by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(bg)) {
        if (robotAlpha > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = robotAlpha }) {
                Image(
                    painter = painterResource(R.drawable.robot_bg),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.00f to Color(0x66000000), 0.18f to Color.Transparent, 0.50f to Color.Transparent,
                            0.72f to Color(0xCC050506), 1.00f to Vtlo.Void,
                        )
                    )
                )
            }
        }

        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            val bannerH by animateDpAsState(if (chatMode) 84.dp else 120.dp, tween(400), label = "bannerH")
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(Modifier.fillMaxWidth().height(bannerH + topInset)) {
                AnimatedBanner(Modifier.fillMaxSize(), scrimTo = bg)
                Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomStart) {
                    TopBar(state, a.onOpenSettings)
                }
            }
            state.diag?.let { Banner("⚠ ${it.summary}", "VIEW", { showDiag = true }, "OK", a.onDismissDiag) }
            state.resumable?.let { if (state.pipeline?.running != true) Banner("↻ Unfinished pipeline: $it", "RESUME", a.onResume) }

            if (chatMode) {
                state.pipeline?.let { PipelineCard(it, a.onStopPipeline, a.onDismissPipeline) }
                MessageList(state, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Footer(state, a)
        }

        if (state.busyLabel != null) BusyOverlay(state, a.onCancelBusy)
        state.pipeline?.approval?.let { ap ->
            AlertDialog(
                onDismissRequest = { a.onApproval(0) },
                containerColor = Vtlo.Ink,
                title = { Text("Root step: ${ap.title}", color = Vtlo.TextHi, style = MaterialTheme.typography.titleMedium) },
                text = {
                    Text(
                        ap.script.take(1200), color = Vtlo.TextHi, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    Row {
                        TextButton({ a.onApproval(1) }) { Text("ONCE", color = Vtlo.Olive) }
                        TextButton({ a.onApproval(2) }) { Text("ALL IN RUN", color = Vtlo.Olive) }
                    }
                },
                dismissButton = { TextButton({ a.onApproval(0) }) { Text("SKIP", color = Vtlo.Danger) } },
            )
        }
        if (showDiag && state.diag != null) {
            AlertDialog(
                onDismissRequest = { showDiag = false },
                containerColor = Vtlo.Ink,
                title = { Text("What stopped the app", color = Vtlo.TextHi, style = MaterialTheme.typography.titleMedium) },
                text = {
                    Text(
                        state.diag.details, color = Vtlo.TextHi, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton({ showDiag = false }) { Text("CLOSE", color = Vtlo.Olive) } },
            )
        }
    }
}

@Composable
private fun TopBar(state: UiState, onOpenSettings: () -> Unit) {
    val m = state.mem
    val ramLine = buildString {
        if (m != null) append("RAM ${"%.1f".format(m.availMb / 1024.0)} GB free")
        if (state.modelState == ModelState.READY) append("  ·  ctx ${state.ctxUsed}/${state.settings.contextWindow}")
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("VTLo_unrestrict3d", color = Vtlo.TextHi, style = MaterialTheme.typography.titleMedium)
            val (label, color) = when (state.modelState) {
                ModelState.NONE -> "NO MODEL" to Vtlo.TextMid
                ModelState.LOADING -> "LOADING…" to Vtlo.Olive
                ModelState.READY -> "● ${state.modelName?.uppercase()}" to Vtlo.Olive
                ModelState.SLEEPING -> "◌ ${state.modelName?.uppercase()}  (asleep, wakes on use)" to Vtlo.TextMid
            }
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ramLine.isNotEmpty()) Text(ramLine, color = Vtlo.TextMid, style = MaterialTheme.typography.labelSmall)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Vtlo.Panel)
                .border(BorderStroke(1.dp, Vtlo.OliveDim), CircleShape).clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) { Text("≡", color = Vtlo.TextHi, fontSize = 22.sp) }
    }
}

@Composable
private fun Banner(text: String, a1: String, on1: () -> Unit, a2: String? = null, on2: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2417))
            .border(BorderStroke(1.dp, Color(0xFF8A6F2E)), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = Vtlo.TextHi, style = MaterialTheme.typography.labelSmall, maxLines = 3)
        Text(a1, color = Vtlo.Olive, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = on1).padding(8.dp))
        if (a2 != null && on2 != null) Text(a2, color = Vtlo.TextMid, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = on2).padding(8.dp))
    }
}

@Composable
private fun PipelineCard(p: PipelineUi, onStop: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(Vtlo.Panel)
            .border(BorderStroke(1.dp, Vtlo.OliveDim), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("AUTO PIPELINE · ${p.goal.take(60)}", color = Vtlo.TextHi, style = MaterialTheme.typography.labelMedium, maxLines = 2)
        LinearProgressIndicator(
            progress = { p.percent / 100f }, color = Vtlo.Olive, trackColor = Vtlo.OliveDim, modifier = Modifier.fillMaxWidth(),
        )
        Text("${p.percent}% · ${p.phase}", color = Vtlo.Olive, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(
            "code steps ${p.steps.count { it.status == "ok" || it.status == "skipped" }}/${p.steps.size} · AI recalls ${p.recalls}/${p.budget} · AI tokens ≈ ${p.aiTokens}",
            color = Vtlo.TextMid, style = MaterialTheme.typography.labelSmall,
        )
        p.steps.take(14).forEach { s ->
            val glyph = when (s.status) { "ok" -> "✓"; "running" -> "▶"; "failed" -> "✗"; "skipped" -> "↷"; else -> "○" }
            val c = when (s.status) { "ok" -> Vtlo.Olive; "running" -> Vtlo.TextHi; "failed" -> Vtlo.Danger; else -> Vtlo.TextMid }
            Text("$glyph ${s.title}", color = c, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.log.isNotEmpty()) Text(p.log.takeLast(4).joinToString("\n"), color = Vtlo.TextMid, style = MaterialTheme.typography.labelSmall)
        Row {
            if (p.running) Text("■ STOP (progress is saved)", color = Vtlo.Danger, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onStop).padding(vertical = 6.dp))
            else Text("DISMISS", color = Vtlo.Olive, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun MessageList(state: UiState, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    LazyColumn(
        state = listState, modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { items(state.messages, key = { it.id }) { Bubble(it) } }
}

@Composable
private fun Bubble(m: ChatMessage) {
    when (m.role) {
        Role.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                m.text, color = Vtlo.TextHi, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 3.dp, 16.dp)).background(Vtlo.Panel)
                    .border(BorderStroke(1.dp, Vtlo.OliveDim), RoundedCornerShape(16.dp, 16.dp, 3.dp, 16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Role.ASSISTANT -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 8.dp, end = 10.dp).size(7.dp).clip(CircleShape).background(Vtlo.Olive))
            Text(
                m.text + if (m.streaming) " ▌" else "", color = Vtlo.TextHi,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
        }
        Role.SYSTEM -> Text(
            "// " + m.text, color = Vtlo.TextMid, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Footer(state: UiState, a: ChatActions) {
    var text by rememberSaveable { mutableStateOf("") }
    val hasModel = state.modelState != ModelState.NONE
    val ready = hasModel && state.busyLabel == null && state.pipeline?.running != true
    val chatMode = state.messages.isNotEmpty() || state.pipeline != null
    val barBg = if (chatMode) Vtlo.Panel else Vtlo.Glass

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasModel && state.busyLabel == null) {
            if (!state.defaultInstalled) CtaButton("[ DOWNLOAD BUILT-IN: ${DEFAULT_LABEL} · 5.1 GB ]", a.onDownloadDefault)
            CtaButton("[ IMPORT MY OWN LITERT MODEL ]", a.onPickModel, primary = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeChip("CHAT", state.mode == Mode.CHAT) { a.onMode(Mode.CHAT) }
            ModeChip("AUTO", state.mode == Mode.AUTO) { a.onMode(Mode.AUTO) }
            Text(
                if (state.mode == Mode.AUTO) "plan once · code runs it · AI only on stops" else "normal chat",
                color = Vtlo.TextMid, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(barBg)
                .border(BorderStroke(1.dp, if (ready) Vtlo.OliveDim else Vtlo.OliveDim.copy(alpha = 0.5f)), RoundedCornerShape(26.dp))
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (text.isEmpty()) {
                    Text(
                        when {
                            !hasModel -> "load a model to start"
                            state.mode == Mode.AUTO -> "describe the job to automate…"
                            else -> "say something…"
                        },
                        color = Vtlo.TextMid, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BasicTextField(
                    value = text, onValueChange = { text = it }, enabled = ready, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.merge(TextStyle(color = Vtlo.TextHi)),
                    cursorBrush = SolidColor(Vtlo.Olive), modifier = Modifier.fillMaxWidth(),
                )
            }
            val working = state.generating
            val canSend = ready && !working && text.isNotBlank()
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(
                    when { working -> Vtlo.Danger; canSend -> Vtlo.Olive; else -> Vtlo.OliveDim.copy(alpha = 0.6f) }
                ).clickable(enabled = working || canSend) { if (working) a.onStop() else { a.onSend(text); text = "" } },
                contentAlignment = Alignment.Center,
            ) { Text(if (working) "■" else "▲", color = Vtlo.Void, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

private const val DEFAULT_LABEL = "VTLO CORE"

@Composable
private fun CtaButton(label: String, onClick: () -> Unit, primary: Boolean = true) = Box(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vtlo.Glass)
        .border(BorderStroke(1.dp, if (primary) Vtlo.Olive else Vtlo.OliveDim), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(vertical = 14.dp),
    contentAlignment = Alignment.Center,
) { Text(label, color = if (primary) Vtlo.Olive else Vtlo.TextHi, style = MaterialTheme.typography.labelMedium) }

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) Vtlo.Olive else Color.Transparent)
        .border(BorderStroke(1.dp, if (selected) Vtlo.Olive else Vtlo.OliveDim), RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
) { Text(label, color = if (selected) Vtlo.Void else Vtlo.TextHi, style = MaterialTheme.typography.labelMedium) }

@Composable
private fun BusyOverlay(state: UiState, onCancel: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a",
    )
    Box(Modifier.fillMaxSize().background(Color(0xE6111112)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            val p = state.importProgress
            if (p != null && p >= 0f) {
                LinearProgressIndicator(progress = { p }, color = Vtlo.Olive, trackColor = Vtlo.OliveDim, modifier = Modifier.fillMaxWidth(0.7f))
                Spacer(Modifier.height(8.dp))
                Text("${(p * 100).toInt()}%", color = Vtlo.TextHi, style = MaterialTheme.typography.labelMedium)
            } else {
                CircularProgressIndicator(color = Vtlo.Olive, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(state.busyLabel ?: "", color = Vtlo.Olive.copy(alpha = pulse), style = MaterialTheme.typography.labelMedium, maxLines = 3)
            if (p != null) {
                Spacer(Modifier.height(18.dp))
                Text("CANCEL (a download can be resumed)", color = Vtlo.TextHi, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable(onClick = onCancel).padding(10.dp))
            }
        }
    }
}
