package com.vtlo.unrestrict3d.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vtlo.unrestrict3d.data.AppSettings
import com.vtlo.unrestrict3d.data.ModelFile
import com.vtlo.unrestrict3d.engine.BackendChoice
import com.vtlo.unrestrict3d.engine.SamplerSettings
import java.util.Locale

class SettingsActions(
    val onDismiss: () -> Unit,
    val onPickModel: () -> Unit,
    val onDownloadDefault: () -> Unit,
    val onLoad: (String) -> Unit,
    val onLoadPath: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onApply: (AppSettings) -> Unit,
    val onNewChat: () -> Unit,
    val onCompress: () -> Unit,
    val onRootFree: () -> Unit,
    val onRootProtect: () -> Unit,
    val onResources: () -> Unit,
    val onClearResources: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: UiState, a: SettingsActions) {
    val s = state.settings
    var backend by remember { mutableStateOf(s.backend) }
    var temp by remember { mutableStateOf(s.sampler.temperature.toFloat()) }
    var topK by remember { mutableStateOf(s.sampler.topK.toFloat()) }
    var topP by remember { mutableStateOf(s.sampler.topP.toFloat()) }
    var system by remember { mutableStateOf(s.systemPrompt) }
    var auto by remember { mutableStateOf(s.autoLoadLast) }
    var ctx by remember { mutableStateOf(s.contextWindow) }
    var compress by remember { mutableStateOf(s.autoCompress) }
    var idle by remember { mutableStateOf(s.idleReleaseMin) }
    var root by remember { mutableStateOf(s.rootBoost) }
    var relPlan by remember { mutableStateOf(s.releaseAfterPlan) }
    var budget by remember { mutableStateOf(s.aiBudget) }
    var path by remember { mutableStateOf("") }

    fun commit() = a.onApply(
        s.copy(
            backend = backend,
            sampler = SamplerSettings(temp.toDouble(), topK.toInt(), topP.toDouble()),
            systemPrompt = system, autoLoadLast = auto, contextWindow = ctx, autoCompress = compress,
            idleReleaseMin = idle, rootBoost = root, releaseAfterPlan = relPlan, aiBudget = budget,
        )
    )

    ModalBottomSheet(
        onDismissRequest = { commit(); a.onDismiss() },
        containerColor = Vtlo.Ink, contentColor = Vtlo.TextHi,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header("MODEL")
            if (!state.defaultInstalled) Action("↓ DOWNLOAD BUILT-IN GEMMA-4 E2B ABLITERATED (5.1 GB)", primary = true, onClick = a.onDownloadDefault)
            Action("+ IMPORT MODEL FILE  (.litertlm)", onClick = a.onPickModel)
            state.models.forEach { m ->
                ModelRow(m, active = m.path == s.lastModelPath, a.onLoad, a.onDelete)
            }
            OutlinedTextField(
                value = path, onValueChange = { path = it },
                label = { Text("or a path, e.g. /sdcard/Download/model.litertlm") },
                singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            if (path.isNotBlank()) Action("LOAD FROM PATH (root copies it if the app cannot read it)") { commit(); a.onLoadPath(path); a.onDismiss() }

            Header("MEMORY & CONTEXT")
            Label("context window (KV cache — the biggest RAM lever)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048, 4096, 8192, 16384).forEach { v -> Chip("${v / 1024}k", ctx == v) { ctx = v } }
            }
            Hint("Changing it reloads the model. 4k is safe on most phones; 16k can exhaust RAM and kill the app.")
            Toggle("auto-compress context (summarize old turns at 70%)", compress) { compress = it }
            Label("release the model from RAM after being idle")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 min", 3 to "3 min", 10 to "10 min", 0 to "never").forEach { (v, l) -> Chip(l, idle == v) { idle = v } }
            }
            Toggle("auto-load last model at app start", auto) { auto = it }
            Action("COMPRESS CONTEXT NOW") { commit(); a.onCompress() }

            Header("ROOT")
            Toggle("root boost (protect app from the killer, free RAM before/after the model)", root) { root = it }
            Hint(
                when (state.rootOk) {
                    true -> "root: granted"
                    false -> "root: not available / denied"
                    null -> "root: not checked yet (turn the switch on and apply)"
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("FREE RAM", false) { a.onRootFree() }
                Chip("PROTECT APP", false) { a.onRootProtect() }
                Chip("RESOURCES", false) { a.onResources() }
            }
            state.resources?.let {
                Text(it, color = Vtlo.TextHi, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Vtlo.Panel).padding(10.dp).clickable(onClick = a.onClearResources))
            }

            Header("AUTO PIPELINE")
            Toggle("release the model once the plan is made (code runs without it)", relPlan) { relPlan = it }
            Label("max AI recalls per run: $budget")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 6, 8).forEach { v -> Chip("$v", budget == v) { budget = v } }
            }

            Header("BACKEND")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackendChoice.values().forEach { b -> Chip(b.name, backend == b) { backend = b } }
            }
            Hint("Changing backend reloads the model. GPU is faster but not every model/device supports it.")

            Header("SAMPLING")
            Labeled("temperature", String.format(Locale.US, "%.2f", temp)) { Slider(temp, { temp = it }, valueRange = 0f..2f, colors = sliderColors()) }
            Labeled("top-k", topK.toInt().toString()) { Slider(topK, { topK = it }, valueRange = 1f..100f, colors = sliderColors()) }
            Labeled("top-p", String.format(Locale.US, "%.2f", topP)) { Slider(topP, { topP = it }, valueRange = 0.1f..1f, colors = sliderColors()) }

            Header("SYSTEM PROMPT")
            OutlinedTextField(
                value = system, onValueChange = { system = it }, placeholder = { Text("optional") },
                minLines = 3, maxLines = 6, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            )

            Action("NEW CHAT") { commit(); a.onNewChat(); a.onDismiss() }
            Action("APPLY & CLOSE", primary = true) { commit(); a.onDismiss() }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun Header(t: String) =
    Text(t, color = Vtlo.Olive, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))

@Composable private fun Label(t: String) = Text(t, color = Vtlo.TextHi, style = MaterialTheme.typography.bodyMedium)

@Composable private fun Hint(t: String) = Text(t, color = Vtlo.TextMid, style = MaterialTheme.typography.labelSmall)

@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 10.dp), color = Vtlo.TextHi, style = MaterialTheme.typography.bodyMedium)
        Switch(
            value, onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Vtlo.Void, checkedTrackColor = Vtlo.Olive, uncheckedTrackColor = Vtlo.Panel, uncheckedThumbColor = Vtlo.TextMid),
        )
    }

@Composable private fun Action(label: String, primary: Boolean = false, onClick: () -> Unit) = Box(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .border(BorderStroke(1.dp, if (primary) Vtlo.Olive else Vtlo.OliveDim), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 8.dp),
    contentAlignment = Alignment.Center,
) { Text(label, color = if (primary) Vtlo.Olive else Vtlo.TextHi, style = MaterialTheme.typography.labelMedium) }

@Composable private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) Vtlo.Olive else Color.Transparent)
        .border(BorderStroke(1.dp, if (selected) Vtlo.Olive else Vtlo.OliveDim), RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
) { Text(label, color = if (selected) Vtlo.Void else Vtlo.TextHi, style = MaterialTheme.typography.labelMedium) }

@Composable private fun Labeled(label: String, value: String, content: @Composable () -> Unit) = Column {
    Row {
        Text(label, Modifier.weight(1f), color = Vtlo.TextHi, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Vtlo.Olive, style = MaterialTheme.typography.bodyMedium)
    }
    content()
}

@Composable
private fun ModelRow(m: ModelFile, active: Boolean, onLoad: (String) -> Unit, onDelete: (String) -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Vtlo.Panel)
        .border(BorderStroke(1.dp, if (active) Vtlo.Olive else Color.Transparent), RoundedCornerShape(12.dp)).padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(m.name, color = Vtlo.TextHi, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(String.format(Locale.US, "%.2f GB", m.sizeBytes / 1_073_741_824.0), color = Vtlo.TextMid, style = MaterialTheme.typography.labelSmall)
    }
    Text("LOAD", color = Vtlo.Olive, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { onLoad(m.path) }.padding(8.dp))
    Text("DEL", color = Vtlo.Danger, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { onDelete(m.path) }.padding(8.dp))
}

@Composable private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Vtlo.Olive, unfocusedBorderColor = Vtlo.OliveDim,
    focusedTextColor = Vtlo.TextHi, unfocusedTextColor = Vtlo.TextHi,
    cursorColor = Vtlo.Olive, focusedLabelColor = Vtlo.Olive, unfocusedLabelColor = Vtlo.TextMid,
    focusedPlaceholderColor = Vtlo.TextMid, unfocusedPlaceholderColor = Vtlo.TextMid,
)

@Composable private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Vtlo.Olive, activeTrackColor = Vtlo.Olive, inactiveTrackColor = Vtlo.OliveDim,
)
