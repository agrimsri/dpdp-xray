package com.dpdpxray.app.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ai.BackendKind
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.ai.ModelFiles
import com.dpdpxray.app.ui.CameraCheckState
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import java.util.Locale

@Composable
fun AiLabScreen(vm: XrayViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by vm.llmStatus.collectAsState()
    val bench by vm.bench.collectAsState()
    val running by vm.benchRunning.collectAsState()
    val push = "adb push gemma-4-E2B-it.litertlm ${ModelFiles.dir(ctx).absolutePath}/"

    Column(Modifier.fillMaxSize()) {
        TopBar("AI lab", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                when (val s = status) {
                    is LlmStatus.Ready -> "${s.model} is loaded on ${s.backend.label} (${s.loadMs / 1000.0} s to load)."
                    is LlmStatus.Loading -> "Loading on ${s.backend.label}…"
                    LlmStatus.NotLoaded -> "A model is installed but not loaded."
                    LlmStatus.NoModel -> "No model installed yet."
                    is LlmStatus.Failed -> "Last load failed: ${s.message}"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            SectionTitle("Load the model")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendKind.entries.forEach { k -> SecondaryAction(k.name, { vm.loadModel(k) }, Modifier.weight(1f), enabled = status !is LlmStatus.Loading) }
            }
            TextButton(onClick = { vm.unloadModel() }) { Text("Unload to free memory", color = Film.boneDim) }

            SectionTitle("Speed on this phone")
            Note("Runs LiteRT-LM's built-in benchmark on each processor. NPU needs a Qualcomm-compiled bundle in the models folder.")
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendKind.entries.forEach { k ->
                    SecondaryAction(if (running == k) "Running…" else k.name, { vm.runBench(k) }, Modifier.weight(1f), enabled = running == null)
                }
            }
            bench.sortedBy { it.backend.ordinal }.forEach { r ->
                Spacer(Modifier.height(8.dp))
                RuleRow(if (r.ok) Film.clear else Film.leak) {
                    Text(r.backend.label, style = MaterialTheme.typography.titleMedium)
                    if (r.ok) Text(
                        String.format(Locale.US, "%.1f tokens/s writing, %.0f tokens/s reading, first token in %.2f s, load %.1f s", r.decodeTps, r.prefillTps, r.ttftSeconds, r.initSeconds),
                        style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
                    ) else Text(r.error ?: "Failed", style = MaterialTheme.typography.bodyMedium, color = Film.leak)
                }
            }
            val headroom = vm.thermalHeadroom()
            if (!headroom.isNaN()) Note(String.format(Locale.US, "Thermal headroom: %.2f (1.0 means the phone is about to throttle)", headroom))

            SectionTitle("Legal search")
            Note(if (vm.graph.semanticSearch) "Semantic search with the on-device embedding model is active." else "Keyword search (BM25) over the DPDP Act and Rules. Add an EmbeddingGemma model to the folder for semantic search.")

            SectionTitle("Install models")
            Note("Copy model files into this folder from your laptop. No root needed.")
            CodeBlock(ModelFiles.describe(ctx))
            CodeBlock(push, Modifier.padding(top = 8.dp))
            TextButton(onClick = { vm.copy("adb", push) }) { Text("Copy command", color = Film.scan) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun CameraCheckScreen(vm: XrayViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by vm.cameraResult.collectAsState()
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> bmp?.let { vm.cameraCheck(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u ->
            runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, u)) { d, _, _ -> d.isMutableRequired = true } }
                .getOrNull()?.let { vm.cameraCheck(it.copy(android.graphics.Bitmap.Config.ARGB_8888, false)) }
        }
    }
    LaunchedEffect(Unit) { vm.resetCamera() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Camera check", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                "Point the camera at any app's consent screen, even on someone else's phone. On-device AI checks it against DPDP consent rules in seconds.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            PrimaryAction("Take a photo", { takePhoto.launch(null) }, enabled = state !is CameraCheckState.Working)
            SecondaryAction("Choose a screenshot", { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth().padding(top = 10.dp))
            when (val s = state) {
                CameraCheckState.Working -> Note("Reading the screen on this phone…", Film.scan)
                is CameraCheckState.Error -> Note(s.message, Film.leak)
                is CameraCheckState.Done -> {
                    val bmp = remember(s.imagePath) { BitmapFactory.decodeFile(s.imagePath)?.asImageBitmap() }
                    if (bmp != null) Image(bmp, null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(top = 16.dp))
                    Verdict(s.audit)
                }
                CameraCheckState.Idle -> {}
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Verdict(a: ScreenAudit) {
    SectionTitle(if (a.isConsentScreen) "What the AI sees" else "This does not look like a consent screen")
    if (!a.isConsentScreen) return
    VerdictLine("Option switched on before you choose", a.preTicked == true, a.preTicked)
    VerdictLine(
        when (a.rejectOption) { RejectOption.VISIBLE -> "Reject is as easy as Accept"; RejectOption.HIDDEN -> "Reject is hidden behind another step"; RejectOption.NONE -> "No way to refuse"; RejectOption.UNKNOWN -> "Reject option unclear" },
        a.rejectOption == RejectOption.HIDDEN || a.rejectOption == RejectOption.NONE, a.rejectOption != RejectOption.UNKNOWN,
    )
    VerdictLine("One button covers several purposes", (a.purposesCount ?: 0) >= 2 && a.separateChoices == false, a.separateChoices)
    VerdictLine("Lists exactly which data is collected", a.itemisedData == false, a.itemisedData, inverted = true)
    VerdictLine("Explains how to withdraw consent", a.withdrawalInfo == false, a.withdrawalInfo, inverted = true)
    a.languageOptions?.let { Note("Languages offered: ${it.ifEmpty { listOf("none shown") }.joinToString()}") }
    if (a.darkPatterns.isNotEmpty()) Note("Dark patterns: ${a.darkPatterns.joinToString()}", Film.high)
    if (a.summary.isNotBlank()) Text(a.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    Note(String.format(Locale.US, "Model confidence %.0f%%%s", a.confidence * 100, a.latencyMs?.let { ", ${it / 1000.0} s on device" } ?: ""))
}

@Composable
private fun VerdictLine(text: String, problem: Boolean, known: Any?, inverted: Boolean = false) {
    val color = when {
        known == null -> Film.boneDim
        problem -> Film.leak
        else -> Film.clear
    }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (known == null) "?" else if (problem) "✕" else "✓", color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
        Text(if (inverted && problem) "Does not: ${text.lowercase()}" else text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
fun SettingsScreen(vm: XrayViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SettingToggle("Presentation mode", "Larger text for projectors and screen sharing.", s.stageMode) { v -> vm.updateSettings { it.copy(stageMode = v) } }
            SettingToggle("Fast audits", "Shorter waits (about 1 minute for a full audit). Good for quick checks and demos.", s.fastDemo) { v -> vm.updateSettings { it.copy(fastDemo = v) } }
            SettingToggle("Load AI when an audit starts", "Loads Gemma automatically if it is installed.", s.autoLoadModel) { v -> vm.updateSettings { it.copy(autoLoadModel = v) } }

            SectionTitle("Your own domains")
            if (s.firstPartyDomains.isEmpty()) Note("None yet. Long-press a host on any timeline to add it.")
            s.firstPartyDomains.sorted().forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.updateSettings { it.copy(firstPartyDomains = it.firstPartyDomains - d) } }) { Text("Remove", color = Film.leak) }
                }
            }

            SectionTitle("Privacy")
            Note(
                "X-Ray uploads nothing. Its only network use is relaying the audited app's own DNS lookups during an audit. " +
                    "Reports, screenshots and AI analysis stay on this phone until you export them.",
            )
            SectionTitle("Not legal advice")
            Note("Results are an automated technical assessment with citations to the DPDP Act 2023 and DPDP Rules 2025.")
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingToggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Film.scan, checkedThumbColor = Film.base))
    }
}
