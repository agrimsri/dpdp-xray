package com.dpdpxray.core.model

import kotlinx.serialization.Serializable

/** What a contacted host is used for. TRACKING_CATEGORIES are the ones DPDP consent checks care about. */
@Serializable
enum class Category {
    ANALYTICS, ADVERTISING, ATTRIBUTION, SOCIAL, CRASH_REPORTING, ENGAGEMENT, FIRST_PARTY, UNKNOWN;

    val isTracking: Boolean get() = this in TRACKING_CATEGORIES

    companion object {
        val TRACKING_CATEGORIES = setOf(ANALYTICS, ADVERTISING, ATTRIBUTION, SOCIAL, ENGAGEMENT)
    }
}

/** Where a network event sits relative to the user's consent choice. */
@Serializable
enum class Phase { BEFORE_CONSENT, AFTER_ACCEPT, AFTER_REJECT, NO_CONSENT_SEEN }

@Serializable
enum class ConsentKind { SCREEN_APPEARED, ACCEPT, REJECT, SETTINGS, MANUAL_MARK }

/** The three audit plans: accept the consent, reject it, or never touch the app. */
@Serializable
enum class AuditPlan(val label: String) {
    ACCEPT("Plan A · Accept"),
    REJECT("Plan B · Reject"),
    SILENT("Plan C · No interaction"),
}

@Serializable
enum class Severity(val weight: Int, val label: String) {
    CRITICAL(30, "Critical"),
    HIGH(15, "High"),
    MEDIUM(8, "Medium"),
    LOW(3, "Low"),
    INFO(0, "Info"),
}

/** OBSERVED = seen on the device. INFERRED = derived from SDK documentation or AI judgement. */
@Serializable
enum class EvidenceType { OBSERVED, INFERRED }

/** Statutory ceilings from the DPDP Act Schedule. Never show the ₹250 crore security band for consent issues. */
@Serializable
enum class PenaltyBand(val label: String) {
    RESIDUAL_50("Up to ₹50 crore (DPDP Schedule, residual)"),
    CHILDREN_200("Up to ₹200 crore (DPDP Schedule, children's data)"),
    NONE("—"),
}

@Serializable
data class NetEvent(
    val tNanos: Long,
    val hostname: String,
    val queryType: Int = 1,
    val entity: String? = null,
    val category: Category = Category.UNKNOWN,
    val phase: Phase = Phase.NO_CONSENT_SEEN,
    /** Package that issued the lookup, when it is not the audited app (e.g. Google Play services). */
    val viaPackage: String? = null,
)

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** A flattened-but-nested snapshot of an accessibility node. */
@Serializable
data class UiNode(
    val className: String = "",
    val text: String? = null,
    val contentDescription: String? = null,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isClickable: Boolean = false,
    val bounds: Bounds? = null,
    val children: List<UiNode> = emptyList(),
) {
    val label: String get() = listOfNotNull(text, contentDescription).joinToString(" ").trim()

    fun flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }
}

@Serializable
data class ConsentEvent(
    val tNanos: Long,
    val kind: ConsentKind,
    val nodeText: String? = null,
    val screenshotPath: String? = null,
    val tree: UiNode? = null,
)

@Serializable
enum class RejectOption { VISIBLE, HIDDEN, NONE, UNKNOWN }

@Serializable
enum class AuditSource { VLM, DETERMINISTIC, MERGED }

/** Result of reviewing a consent screen, from the on-device VLM, the accessibility tree, or both. */
@Serializable
data class ScreenAudit(
    val isConsentScreen: Boolean,
    val preTicked: Boolean? = null,
    val rejectOption: RejectOption = RejectOption.UNKNOWN,
    val purposesCount: Int? = null,
    val separateChoices: Boolean? = null,
    val itemisedData: Boolean? = null,
    val purposeStated: Boolean? = null,
    val withdrawalInfo: Boolean? = null,
    /** null = unknown. A list containing only English means no Indian-language option was found. */
    val languageOptions: List<String>? = null,
    val childAudienceSignals: Boolean? = null,
    val darkPatterns: List<String> = emptyList(),
    val summary: String = "",
    val confidence: Double = 1.0,
    val source: AuditSource = AuditSource.DETERMINISTIC,
    /** Field names confirmed from the accessibility tree (OBSERVED); all others came from the VLM (INFERRED). */
    val observedFields: Set<String> = emptySet(),
    val conflicts: List<String> = emptyList(),
    val modelId: String? = null,
    val latencyMs: Long? = null,
)

@Serializable
data class AuditSession(
    val id: String,
    val targetPackage: String,
    val targetLabel: String,
    val plan: AuditPlan,
    val childFacing: Boolean = false,
    val startedAtNanos: Long = 0,
    val endedAtNanos: Long = 0,
    val startedAtEpochMs: Long = 0,
    val netEvents: List<NetEvent> = emptyList(),
    val consentEvents: List<ConsentEvent> = emptyList(),
    val screenAudit: ScreenAudit? = null,
    val deviceModel: String = "",
    val androidVersion: String = "",
    val privateDnsActive: Boolean? = null,
    val notes: List<String> = emptyList(),
) {
    val consentChoice: ConsentEvent?
        get() = consentEvents.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }

    val consentScreenSeen: Boolean
        get() = consentEvents.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK } ||
            consentChoice != null
}

@Serializable
enum class CheckId(val code: String, val title: String) {
    C1("C1", "Trackers contacted before consent"),
    C2("C2", "Trackers contacted after the user refused"),
    C3("C3", "Pre-ticked consent option"),
    C4("C4", "No clear way to refuse"),
    C5("C5", "Bundled consent for several purposes"),
    C6("C6", "Notice lacks itemised data or purpose"),
    C7("C7", "No visible way to withdraw consent"),
    C8("C8", "Notice offered only in English"),
    C9("C9", "Tracking in a child-facing app"),
    C10("C10", "Device identifiers likely shared before consent"),
    NC("NC", "No consent mechanism detected"),
    V0("V0", "Low network visibility — result inconclusive"),
}

@Serializable
data class Finding(
    val checkId: CheckId,
    val severity: Severity,
    val evidenceType: EvidenceType,
    val title: String,
    val explanation: String,
    val evidence: List<String>,
    val plans: List<AuditPlan>,
    val dpdpRefs: List<String>,
    val penaltyBand: PenaltyBand,
    val confidence: Double = 1.0,
    val entities: List<String> = emptyList(),
)

@Serializable
enum class ScoreBand(val label: String) {
    READY("DPDP-ready"),
    NEEDS_WORK("Needs work"),
    AT_RISK("At risk"),
    INCONCLUSIVE("Inconclusive"),
}

@Serializable
data class AuditResult(
    val findings: List<Finding>,
    val score: Int?,
    val band: ScoreBand,
)
