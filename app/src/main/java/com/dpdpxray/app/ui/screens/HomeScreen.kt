package com.dpdpxray.app.ui.screens

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.StatusDot
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.bandColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: XrayViewModel,
    onAudit: () -> Unit,
    onOpen: (String) -> Unit,
    onLab: () -> Unit,
    onCamera: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val pf by vm.preflight.collectAsState()
    val audits by vm.audits.collectAsState()
    val llm by vm.llmStatus.collectAsState()
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPreflight() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DPDP X-Ray", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onLab) { Text("AI lab", color = Film.scan) }
            TextButton(onClick = onSettings) { Text("Settings", color = Film.boneDim) }
        }
        Text(
            "Find out what an app sends to trackers before people agree, and how to fix it. Everything runs on this phone.",
            style = MaterialTheme.typography.bodyLarge, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp),
        )

        SectionTitle("Before you audit")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CheckLine(
                "VPN permission", if (pf.vpnReady) "Allowed" else "Needed to watch the app's lookups", pf.vpnReady,
                action = if (pf.vpnReady) null else "Allow" to {
                    VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) } ?: vm.refreshPreflight()
                },
            )
            CheckLine(
                "Consent watcher", if (pf.accessibilityOn) "On" else "Turn on \"DPDP X-Ray consent watcher\" in Accessibility", pf.accessibilityOn,
                action = if (pf.accessibilityOn) null else "Turn on" to { DeviceChecks.openAccessibilitySettings(ctx) },
            )
            CheckLine(
                "Fresh-install reset", when {
                    pf.shizukuGranted -> "Shizuku ready: each plan starts from a clean app"
                    pf.shizukuRunning -> "Shizuku is running; allow X-Ray to use it"
                    else -> "Optional. Start Shizuku for full audits (reject + accept)"
                },
                if (pf.shizukuGranted) true else null,
                action = if (pf.shizukuRunning && !pf.shizukuGranted) "Allow" to { vm.requestShizuku() } else null,
            )
            CheckLine(
                "Private DNS", if (pf.privateDnsActive) "On: lookups may be hidden from the audit" else "Off", !pf.privateDnsActive,
                action = if (pf.privateDnsActive) "Open settings" to { DeviceChecks.openPrivateDnsSettings(ctx) } else null,
            )
            CheckLine(
                "On-device AI", when (val s = llm) {
                    is LlmStatus.Ready -> "${s.model} on ${s.backend.label}"
                    is LlmStatus.Loading -> "Loading on ${s.backend.label}…"
                    LlmStatus.NotLoaded -> "Model found; it loads when an audit starts"
                    LlmStatus.NoModel -> "No model yet. Screen checks use the accessibility tree only"
                    is LlmStatus.Failed -> s.message
                },
                when (llm) { is LlmStatus.Ready -> true; is LlmStatus.Failed, LlmStatus.NoModel -> null; else -> null },
                action = "AI lab" to onLab,
            )
        }

        Spacer(Modifier.height(24.dp))
        PrimaryAction("Audit an app", onAudit, enabled = pf.canAudit)
        if (!pf.canAudit) Note("Allow the VPN and turn on the consent watcher to run a live audit.", Film.warn)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryAction("Open sample audit", { vm.openSample(onOpen) }, Modifier.weight(1f))
            SecondaryAction("Camera check", onCamera, Modifier.weight(1f))
        }

        SectionTitle("Recent audits")
        if (audits.isEmpty()) Note("No audits yet. Run one, or open the sample to see what a report looks like.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            audits.forEach { AuditRow(it) { onOpen(it.id) } }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CheckLine(title: String, detail: String, ok: Boolean?, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(ok)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        if (action != null) TextButton(onClick = action.second) { Text(action.first, color = Film.scan) }
    }
}

@Composable
private fun AuditRow(meta: AuditMeta, onClick: () -> Unit) {
    val color = bandColor(meta.band)
    RuleRow(color, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meta.targetLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${meta.findingCount} findings, ${SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(meta.createdAtMs))}",
                    style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
                )
            }
            if (meta.sample) Tag("Sample", Film.boneDim)
            Spacer(Modifier.width(10.dp))
            Text(meta.score?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, color = color)
        }
    }
}
