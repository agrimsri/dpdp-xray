package com.dpdpxray.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.ConsentLine
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.bandColor
import com.dpdpxray.app.ui.theme.severityColor
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import java.io.File

@Composable
private fun WithBundle(vm: XrayViewModel, id: String, content: @Composable (AuditBundle) -> Unit) {
    LaunchedEffect(id) { vm.open(id) }
    val bundle by vm.bundle.collectAsState()
    val b = bundle
    if (b == null || b.meta.id != id) Note("Opening audit…") else content(b)
}

@Composable
fun ResultsScreen(vm: XrayViewModel, id: String, onBack: () -> Unit, onFinding: (CheckId) -> Unit, onFixes: () -> Unit, onReport: () -> Unit) {
    WithBundle(vm, id) { b ->
        val r = b.result
        Column(Modifier.fillMaxSize()) {
            TopBar(b.meta.targetLabel, onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (r.band == ScoreBand.INCONCLUSIVE) "?" else r.score.toString(),
                        style = MaterialTheme.typography.displayLarge, color = bandColor(r.band),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(r.band.label, style = MaterialTheme.typography.titleLarge, color = bandColor(r.band))
                        Text("DPDP readiness out of 100", style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                    }
                    Spacer(Modifier.weight(1f))
                    if (b.meta.sample) Tag("Sample data", Film.boneDim)
                }
                SeverityStrip(r.findings)
                Text(headline(r.findings), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))

                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val fixCount = vm.fixesFor(b).size
                    SecondaryAction("How to fix ($fixCount)", onFixes, Modifier.weight(1f), enabled = fixCount > 0)
                    SecondaryAction("Report and export", onReport, Modifier.weight(1f))
                }

                SectionTitle("Findings")
                if (r.findings.isEmpty()) Note("No consent problems in the captured evidence.", Film.clear)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.findings.forEach { f -> FindingRow(f) { onFinding(f.checkId) } }
                }

                SectionTitle("Timeline")
                PlanTimelines(vm, b)

                val notes = b.sessions.flatMap { s -> s.notes.map { "${s.plan.label}: $it" } }.distinct()
                if (notes.isNotEmpty()) {
                    SectionTitle("Audit notes")
                    notes.forEach { Note("• $it") }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun headline(findings: List<Finding>): String {
    val c1 = findings.firstOrNull { it.checkId == CheckId.C1 }
    val c2 = findings.firstOrNull { it.checkId == CheckId.C2 }
    val v0 = findings.any { it.checkId == CheckId.V0 }
    return when {
        v0 -> "Too few lookups were captured to judge this app. See the first finding for how to re-run."
        c2 != null -> "Tracking continued after the user refused consent: ${c2.entities.joinToString()}."
        c1 != null -> "${c1.entities.size} tracker${if (c1.entities.size == 1) "" else "s"} contacted before any consent choice: ${c1.entities.joinToString()}."
        findings.isEmpty() -> "No trackers before consent and no consent-screen problems found."
        else -> "No tracking before consent, but the consent screen needs work."
    }
}

@Composable
private fun SeverityStrip(findings: List<Finding>) {
    val counts = Severity.entries.associateWith { s -> findings.count { it.severity == s } }.filterValues { it > 0 }
    if (counts.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(10.dp)) {
        counts.forEach { (s, n) -> Box(Modifier.weight(n.toFloat()).height(10.dp).background(severityColor(s))) }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        counts.forEach { (s, n) -> Text("$n ${s.label.lowercase()}", style = MaterialTheme.typography.labelMedium, color = severityColor(s)) }
    }
}

@Composable
private fun FindingRow(f: Finding, onClick: () -> Unit) {
    RuleRow(severityColor(f.severity), onClick = onClick) {
        Text(f.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "${f.severity.label}, ${if (f.evidenceType == EvidenceType.OBSERVED) "observed on device" else "inferred"}" +
                (if (f.penaltyBand.name != "NONE") ", ${f.penaltyBand.label.substringBefore(" (")}" else ""),
            style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
        )
        if (f.entities.isNotEmpty()) Text(f.entities.joinToString(), style = MaterialTheme.typography.bodyMedium, color = severityColor(f.severity))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanTimelines(vm: XrayViewModel, b: AuditBundle) {
    var planIdx by remember(b.meta.id) { mutableStateOf(0) }
    var markHost by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        b.sessions.forEachIndexed { i, s ->
            FilterChip(
                selected = i == planIdx, onClick = { planIdx = i }, label = { Text(s.plan.label.substringAfter("· ")) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Film.scan, selectedLabelColor = Film.base),
            )
        }
    }
    val s = b.sessions.getOrNull(planIdx) ?: return
    val events = vm.timeline(s)
    val choice = s.consentChoice
    Note("Long-press a host to mark it as your own domain.")
    Column(Modifier.padding(top = 6.dp)) {
        var lineDrawn = false
        events.forEach { e ->
            if (!lineDrawn && choice != null && e.tNanos >= choice.tNanos) {
                lineDrawn = true
                ConsentLine(choiceLabel(choice.kind, secs(choice.tNanos - s.startedAtNanos)), if (choice.kind == ConsentKind.ACCEPT) Film.scan else Film.warn)
            }
            Box(Modifier.combinedClickable(onClick = {}, onLongClick = { markHost = e.hostname })) {
                TimelineRow(e, s.startedAtNanos, beforeConsent = choice == null || e.tNanos < choice.tNanos, afterRefusal = choice?.kind == ConsentKind.REJECT)
            }
        }
        if (!lineDrawn && choice != null) ConsentLine(choiceLabel(choice.kind, secs(choice.tNanos - s.startedAtNanos)), Film.scan)
        if (choice == null) Note(if (s.consentScreenSeen) "The user never chose; every lookup above happened before consent." else "No consent screen was shown in this run.")
    }
    markHost?.let { host ->
        AlertDialog(
            onDismissRequest = { markHost = null },
            title = { Text("Mark as your own domain?") },
            text = { Text("$host will be treated as first-party in future results, so it never counts as a tracker.") },
            confirmButton = { TextButton(onClick = { vm.markFirstParty(host); markHost = null }) { Text("Mark as mine") } },
            dismissButton = { TextButton(onClick = { markHost = null }) { Text("Cancel") } },
            containerColor = Film.raised,
        )
    }
}

private fun choiceLabel(kind: ConsentKind, at: String) = if (kind == ConsentKind.ACCEPT) "User accepted at $at s" else "User refused at $at s"

@Composable
fun FindingDetailScreen(vm: XrayViewModel, id: String, check: String, onBack: () -> Unit, onFixes: () -> Unit) {
    WithBundle(vm, id) { b ->
        val f = b.result.findings.firstOrNull { it.checkId.name == check } ?: return@WithBundle
        val explanations by vm.explanations.collectAsState()
        val explaining by vm.explaining.collectAsState()
        Column(Modifier.fillMaxSize()) {
            TopBar(f.checkId.code, onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text(f.title, style = MaterialTheme.typography.headlineMedium, color = severityColor(f.severity))
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(f.severity.label, severityColor(f.severity))
                    Tag(if (f.evidenceType == EvidenceType.OBSERVED) "Observed" else "Inferred", Film.boneDim)
                    if (f.penaltyBand.name != "NONE") Tag(f.penaltyBand.label.substringBefore(" ("), Film.boneDim)
                }
                Text(f.explanation, style = MaterialTheme.typography.bodyLarge)

                screenshotFor(b, f)?.let { path ->
                    SectionTitle("Consent screen as captured")
                    val bmp = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                    if (bmp != null) Image(bmp, contentDescription = "Captured consent screen", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                }

                if (f.evidence.isNotEmpty()) {
                    SectionTitle("Evidence")
                    f.evidence.forEach { CodeBlock(it, Modifier.padding(bottom = 6.dp)) }
                }

                SectionTitle("What the law says")
                val cites by produceState(initialValue = emptyList<com.dpdpxray.core.dpdp.Passage>(), f) { value = vm.citationsFor(f) }
                if (cites.isEmpty()) Note("Finding the relevant sections…")
                cites.forEach { p ->
                    RuleRow(Film.scan) {
                        Text(p.text, style = MaterialTheme.typography.bodyMedium)
                        Text(p.source + if (p.verbatim) "" else " (summary)", style = MaterialTheme.typography.labelMedium, color = Film.scan, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                SectionTitle("Explain with on-device AI")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction("In English", { vm.explain(f, hindi = false) }, Modifier.weight(1f), enabled = explaining == null)
                    SecondaryAction("हिन्दी में", { vm.explain(f, hindi = true) }, Modifier.weight(1f), enabled = explaining == null)
                }
                listOf("en", "hi").forEach { lang ->
                    val key = "${f.checkId}-$lang"
                    if (explaining == key) Note("Gemma is writing on this phone…", Film.scan)
                    explanations[key]?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryAction("See how to fix", onFixes)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun screenshotFor(b: AuditBundle, f: Finding): String? {
    val screenChecks = setOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C9)
    if (f.checkId !in screenChecks) return null
    return b.sessions.flatMap { it.consentEvents }.mapNotNull { it.screenshotPath }.firstOrNull { File(it).exists() }
}

@Composable
fun FixesScreen(vm: XrayViewModel, id: String, onBack: () -> Unit) {
    WithBundle(vm, id) { b ->
        val fixes = vm.fixesFor(b)
        Column(Modifier.fillMaxSize()) {
            TopBar("How to fix", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Note("Copy a fix and paste it into your project (with clipboard sync, straight into Android Studio on your laptop). Then rebuild and audit again.")
                fixes.forEach { fix ->
                    SectionTitle(fix.title)
                    if (!fix.verified) Note("Check the exact API for your SDK version in the vendor docs.", Film.warn)
                    fix.manifest?.let { m ->
                        Text("AndroidManifest.xml", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        CodeBlock(m)
                        TextButton(onClick = { vm.copy("manifest", m) }) { Text("Copy manifest change", color = Film.scan) }
                    }
                    fix.code?.let { c ->
                        Text("Code", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        CodeBlock(c)
                        TextButton(onClick = { vm.copy("code", c) }) { Text("Copy code", color = Film.scan) }
                    }
                    fix.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
                    fix.docUrl?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Film.scan, modifier = Modifier.padding(top = 6.dp)) }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun ReportScreen(vm: XrayViewModel, id: String, onBack: () -> Unit) {
    WithBundle(vm, id) { b ->
        Column(Modifier.fillMaxSize()) {
            TopBar("Report", onBack)
            Column(Modifier.padding(horizontal = 20.dp)) {
                PrimaryAction("Save PDF to Downloads", { vm.exportPdf(b, share = false) })
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction("Share PDF", { vm.exportPdf(b, share = true) }, Modifier.weight(1f))
                    SecondaryAction("Save Markdown", { vm.saveMarkdown(b) }, Modifier.weight(1f))
                }
                Note("Saved files land in Downloads/DPDP-XRay, ready to share or move to your laptop.")
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(vm.markdown(b), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
            }
        }
    }
}
