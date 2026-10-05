package com.dpdpxray.core.rules

import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.trackers.TrackerCatalog
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns raw audit sessions into DPDP findings. Deterministic: same sessions in, same findings out.
 * The AI layer only enriches explanations; it never decides whether a finding exists.
 */
class RulesEngine(private val catalog: TrackerCatalog) {

    private data class Ctx(val session: AuditSession, val events: List<NetEvent>)

    fun evaluate(sessions: List<AuditSession>): AuditResult {
        val ctx = sessions.map { s -> Ctx(s, classify(PhaseAssigner.assign(s.netEvents, s.consentEvents))) }
        val findings = mutableListOf<Finding>()

        val allEvents = ctx.flatMap { it.events }
        val anyTracking = allEvents.any { it.category.isTracking }
        if (allEvents.size < MIN_EVENTS && !anyTracking) findings += lowVisibility(sessions)

        c1BeforeConsent(ctx)?.let { findings += it }
        noConsentMechanism(ctx)?.let { findings += it }
        c2AfterReject(ctx)?.let { findings += it }
        val screen = sessions.mapNotNull { it.screenAudit }.firstOrNull { it.isConsentScreen }
        val screenPlans = sessions.filter { it.screenAudit?.isConsentScreen == true }.map { it.plan }.distinct()
        if (screen != null) findings += screenChecks(screen, screenPlans)
        c9Children(ctx, screen)?.let { findings += it }
        c10AdIds(ctx)?.let { findings += it }

        val (score, band) = ScoreCalculator.compute(findings)
        return AuditResult(findings.sortedWith(compareBy({ it.severity.ordinal }, { it.checkId.ordinal })), score, band)
    }

    private fun classify(events: List<NetEvent>) = events.map { e ->
        if (e.entity != null) e else {
            val info = catalog.classify(e.hostname)
            e.copy(entity = info.entity, category = info.category)
        }
    }

    private fun preConsent(e: NetEvent) = e.phase == Phase.BEFORE_CONSENT || e.phase == Phase.NO_CONSENT_SEEN

    private fun c1BeforeConsent(ctx: List<Ctx>): Finding? {
        val hits = ctx.flatMap { c -> c.events.filter { it.category.isTracking && preConsent(it) }.map { c to it } }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C1, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.C1.title,
            explanation = "The app contacted ${entities.joinToString()} before the user made any consent choice. " +
                "Under the DPDP Act, personal data may be processed only with consent (or a legitimate use), " +
                "and consent must be given by a clear affirmative action before processing starts.",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s4(1)", "s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            entities = entities,
        )
    }

    private fun noConsentMechanism(ctx: List<Ctx>): Finding? {
        if (ctx.any { it.session.consentScreenSeen }) return null
        val trackers = ctx.flatMap { it.events.filter { e -> e.category.isTracking } }
        if (trackers.isEmpty()) return null
        return Finding(
            checkId = CheckId.NC, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.NC.title,
            explanation = "No consent request or privacy notice was detected, yet the app contacted " +
                "${trackers.mapNotNull { it.entity }.distinct().joinToString()}. DPDP requires a notice (s.5) and valid consent (s.6), " +
                "and the Data Fiduciary must be able to prove both (s.6(10)).",
            evidence = listOf("No consent screen detected in ${ctx.joinToString { it.session.plan.label }}."),
            plans = ctx.map { it.session.plan }.distinct(),
            dpdpRefs = listOf("s5(1)", "s6(1)", "s6(10)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
        )
    }

    private fun c2AfterReject(ctx: List<Ctx>): Finding? {
        val hits = ctx.flatMap { c -> c.events.filter { it.category.isTracking && it.phase == Phase.AFTER_REJECT }.map { c to it } }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C2, severity = Severity.CRITICAL, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.C2.title,
            explanation = "The user refused consent, but the app still contacted ${entities.joinToString()}. " +
                "Consent must be free and specific; processing based on a refused consent has no lawful ground.",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s4(1)", "s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            entities = entities,
        )
    }

    private fun screenChecks(a: ScreenAudit, plans: List<AuditPlan>): List<Finding> {
        val out = mutableListOf<Finding>()
        fun ev(field: String) = if (field in a.observedFields) EvidenceType.OBSERVED else EvidenceType.INFERRED
        fun add(id: CheckId, sev: Severity, field: String, explanation: String, evidence: String, refs: List<String>) {
            out += Finding(
                checkId = id, severity = sev, evidenceType = ev(field), title = id.title, explanation = explanation,
                evidence = listOf(evidence) + a.conflicts.filter { it.startsWith(field) }.map { "Note: tree and AI disagree — $it" },
                plans = plans, dpdpRefs = refs, penaltyBand = PenaltyBand.RESIDUAL_50,
                confidence = if (ev(field) == EvidenceType.OBSERVED) 1.0 else a.confidence,
            )
        }
        if (a.preTicked == true) add(
            CheckId.C3, Severity.HIGH, "preTicked",
            "A consent option is already switched on before the user acts. DPDP consent needs a clear affirmative action by the user.",
            "Consent screen shows a pre-ticked option.", listOf("s6(1)"),
        )
        if (a.rejectOption == RejectOption.NONE || a.rejectOption == RejectOption.HIDDEN) add(
            CheckId.C4, Severity.HIGH, "rejectOption",
            "Refusing is " + (if (a.rejectOption == RejectOption.NONE) "not possible" else "harder than agreeing (hidden behind another screen)") +
                ". Consent must be free, and withdrawing it must be as easy as giving it.",
            "Reject option: ${a.rejectOption.name.lowercase()}.", listOf("s6(1)", "s6(4)"),
        )
        if ((a.purposesCount ?: 0) >= 2 && a.separateChoices == false) add(
            CheckId.C5, Severity.MEDIUM, "separateChoices",
            "One button covers ${a.purposesCount} purposes. Consent must be specific to each specified purpose.",
            "${a.purposesCount} purposes, no separate choices.", listOf("s6(1)", "r3(b)"),
        )
        if (a.itemisedData == false || a.purposeStated == false) add(
            CheckId.C6, Severity.MEDIUM, if (a.itemisedData == false) "itemisedData" else "purposeStated",
            "The notice does not " + listOfNotNull(
                "list exactly which personal data is collected".takeIf { a.itemisedData == false },
                "state the specific purpose".takeIf { a.purposeStated == false },
            ).joinToString(" or ") + ", as Rule 3 requires.",
            "Notice: itemised data=${a.itemisedData}, purpose stated=${a.purposeStated}.", listOf("s5(1)", "r3(b)"),
        )
        if (a.withdrawalInfo == false) add(
            CheckId.C7, Severity.MEDIUM, "withdrawalInfo",
            "The notice does not explain how to withdraw consent later, exercise rights, or complain to the Board.",
            "No withdrawal information visible.", listOf("s5(1)", "s6(4)", "r3(c)"),
        )
        val langs = a.languageOptions
        if (langs != null && langs.all { it.equals("English", ignoreCase = true) }) add(
            CheckId.C8, Severity.LOW, "languageOptions",
            "The notice can only be read in English. DPDP requires an option to access it in English or any Eighth Schedule language.",
            "Languages offered: ${langs.ifEmpty { listOf("none shown") }.joinToString()}.", listOf("s5(3)", "s6(3)"),
        )
        return out
    }

    private fun c9Children(ctx: List<Ctx>, screen: ScreenAudit?): Finding? {
        val declared = ctx.any { it.session.childFacing }
        val signalled = screen?.childAudienceSignals == true
        if (!declared && !signalled) return null
        val hits = ctx.flatMap { c -> c.events.filter { it.category.isTracking }.map { c to it } }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C9, severity = Severity.CRITICAL,
            evidenceType = if (declared) EvidenceType.OBSERVED else EvidenceType.INFERRED,
            title = CheckId.C9.title,
            explanation = "This app ${if (declared) "is declared child-facing" else "looks aimed at children"} and contacts " +
                "${entities.joinToString()}. DPDP bars tracking, behavioural monitoring and targeted advertising directed at " +
                "children, and requires verifiable parental consent.",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s9(1)", "s9(3)"),
            penaltyBand = PenaltyBand.CHILDREN_200,
            confidence = if (declared) 1.0 else (screen?.confidence ?: 0.5),
            entities = entities,
        )
    }

    private fun c10AdIds(ctx: List<Ctx>): Finding? {
        val hits = ctx.flatMap { c ->
            c.events.filter { preConsent(it) && catalog.classify(it.hostname).collectsAdIdAtInit }.map { c to it }
        }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C10, severity = Severity.MEDIUM, evidenceType = EvidenceType.INFERRED,
            title = CheckId.C10.title,
            explanation = "Per vendor documentation, ${entities.joinToString()} read a device or advertising identifier when they " +
                "initialise. They were contacted before consent, so identifiers were likely shared without consent. " +
                "(Inferred from SDK behaviour; payloads were not inspected.)",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            confidence = 0.7,
            entities = entities,
        )
    }

    private fun lowVisibility(sessions: List<AuditSession>): Finding {
        val privateDns = sessions.any { it.privateDnsActive == true }
        return Finding(
            checkId = CheckId.V0, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.V0.title,
            explanation = "Almost no DNS lookups were captured, so this audit cannot call the app clean. " +
                (if (privateDns) "Private DNS is turned on — turn it Off in Settings and re-run. " else "") +
                "The app may have cached DNS before the audit, use DNS-over-HTTPS, or simply not have started; " +
                "clear its data and re-run the audit.",
            evidence = sessions.map { "${it.plan.label}: ${it.netEvents.size} lookups" },
            plans = sessions.map { it.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
        )
    }

    private fun describe(c: Ctx, e: NetEvent): String {
        val sinceLaunch = secs(e.tNanos - c.session.startedAtNanos)
        val choice = c.session.consentChoice
        val rel = when {
            choice == null && e.phase == Phase.NO_CONSENT_SEEN -> "no consent was ever requested"
            choice == null -> "the user had not chosen yet"
            e.tNanos < choice.tNanos -> "${secs(choice.tNanos - e.tNanos)} s before the consent choice"
            else -> "${secs(e.tNanos - choice.tNanos)} s after the user chose ${choice.kind.name.lowercase()}"
        }
        val who = e.entity?.let { " ($it)" } ?: ""
        val via = e.viaPackage?.let { " via $it" } ?: ""
        return "${e.hostname}$who$via — $sinceLaunch s after launch, $rel [${c.session.plan.label}]"
    }

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)

    companion object {
        const val MIN_EVENTS = 2
        const val MAX_EVIDENCE = 12
    }
}

object ScoreCalculator {
    /** 100 minus severity weights (inferred findings count half); V0 makes the result inconclusive. */
    fun compute(findings: List<Finding>): Pair<Int?, ScoreBand> {
        if (findings.any { it.checkId == CheckId.V0 }) return null to ScoreBand.INCONCLUSIVE
        val penalty = findings.sumOf { f -> f.severity.weight * if (f.evidenceType == EvidenceType.INFERRED) 0.5 else 1.0 }
        val score = (100 - penalty).coerceAtLeast(0.0).roundToInt()
        val band = when {
            score >= 85 -> ScoreBand.READY
            score >= 60 -> ScoreBand.NEEDS_WORK
            else -> ScoreBand.AT_RISK
        }
        return score to band
    }
}
