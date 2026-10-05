package com.dpdpxray.core.report

import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AuditJson {
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    fun encodeSessions(sessions: List<AuditSession>): String = json.encodeToString(sessions)
    fun decodeSessions(text: String): List<AuditSession> = json.decodeFromString(text)
    fun encodeResult(result: AuditResult): String = json.encodeToString(result)
}

/** Builds the human-readable audit report (Markdown). The PDF on the phone is rendered from the same content. */
object MarkdownReport {
    const val DISCLAIMER = "Automated technical assessment based on network and on-screen evidence captured on this device. " +
        "This is not legal advice."
    const val PRIVACY_LINE = "0 bytes of audit data left this phone: capture, AI analysis and this report were produced on-device."

    fun build(result: AuditResult, sessions: List<AuditSession>, fixes: List<FixTemplate>, citations: CitationService): String = buildString {
        val first = sessions.firstOrNull()
        appendLine("# DPDP X-Ray report — ${first?.targetLabel ?: "Unknown app"} (`${first?.targetPackage ?: "?"}`)")
        appendLine()
        val score = if (result.band == ScoreBand.INCONCLUSIVE) "Inconclusive" else "Score: ${result.score}/100 · ${result.band.label}"
        appendLine("**$score**")
        appendLine()
        if (first != null) {
            appendLine("- Audited: ${date(first.startedAtEpochMs)} · ${sessions.joinToString { it.plan.label }}")
            if (first.deviceModel.isNotBlank()) appendLine("- Device: ${first.deviceModel} · Android ${first.androidVersion}")
        }
        appendLine("- $PRIVACY_LINE")
        appendLine()

        appendLine("## Summary")
        appendLine()
        if (result.findings.isEmpty()) appendLine("No DPDP consent issues were detected in the captured evidence.")
        else {
            appendLine("| Check | Finding | Severity | Evidence | Penalty ceiling |")
            appendLine("|---|---|---|---|---|")
            for (f in result.findings) {
                appendLine("| ${f.checkId.code} | ${f.title} | ${f.severity.label} | ${f.evidenceType.name.lowercase()} | ${f.penaltyBand.label} |")
            }
        }
        appendLine()

        if (result.findings.isNotEmpty()) appendLine("## Findings")
        for (f in result.findings) {
            appendLine()
            appendLine("### ${f.checkId.code} · ${f.title}")
            appendLine("*${f.severity.label} · ${f.evidenceType.name.lowercase()} · confidence ${pct(f.confidence)} · ${f.plans.joinToString { it.label }}*")
            appendLine()
            appendLine(f.explanation)
            if (f.evidence.isNotEmpty()) {
                appendLine()
                appendLine("**Evidence**")
                f.evidence.forEach { appendLine("- $it") }
            }
            val cites = citations.cite(f)
            if (cites.isNotEmpty()) {
                appendLine()
                appendLine("**DPDP basis**")
                for (p in cites) {
                    appendLine()
                    appendLine("> ${p.text}")
                    appendLine(">")
                    appendLine("> — ${p.source}${if (p.verbatim) "" else " (summary)"}")
                }
            }
        }

        if (fixes.isNotEmpty()) {
            appendLine()
            appendLine("## How to fix")
            for (fix in fixes) {
                appendLine()
                appendLine("### ${fix.title}")
                if (!fix.verified) appendLine("*Check the exact API for your SDK version in the vendor docs.*")
                fix.manifest?.let { appendLine(); appendLine("AndroidManifest.xml:"); appendLine("```xml"); appendLine(it); appendLine("```") }
                fix.code?.let { appendLine(); appendLine("```kotlin"); appendLine(it); appendLine("```") }
                if (fix.steps.isNotEmpty()) { appendLine(); fix.steps.forEach { appendLine("- $it") } }
                fix.docUrl?.let { appendLine(); appendLine("Docs: $it") }
            }
        }

        appendLine()
        appendLine("## Network timeline")
        for (s in sessions) {
            appendLine()
            appendLine("**${s.plan.label}** — ${s.netEvents.size} lookups${s.consentChoice?.let { c -> ", user chose ${c.kind.name.lowercase()} at ${secs(c.tNanos - s.startedAtNanos)} s" } ?: ""}")
            appendLine()
            appendLine("| t (s) | Host |")
            appendLine("|---|---|")
            s.netEvents.sortedBy { it.tNanos }.take(60).forEach { appendLine("| ${secs(it.tNanos - s.startedAtNanos)} | ${it.hostname} |") }
        }

        appendLine()
        appendLine("## Method and limitations")
        appendLine("- Network evidence is DNS lookups made by the audited app (captured by a local, per-app VPN). A lookup shows the app tried to contact a server; payloads are not decrypted.")
        appendLine("- *Observed* findings come from captured traffic or the screen's accessibility tree. *Inferred* findings come from SDK documentation or the on-device AI's reading of the screen, and count half in the score.")
        appendLine("- Some processing may rely on a legitimate use (DPDP s.7) rather than consent; review findings in that light.")
        appendLine("- Penalty ceilings are statutory maximums from the DPDP Act Schedule, not predicted fines.")
        appendLine()
        appendLine("---")
        appendLine("*$DISCLAIMER*")
    }

    private fun date(epochMs: Long): String =
        if (epochMs <= 0) "—" else DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)
    private fun pct(d: Double) = "${(d * 100).toInt()}%"
}
