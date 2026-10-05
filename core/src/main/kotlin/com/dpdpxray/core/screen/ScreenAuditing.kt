package com.dpdpxray.core.screen

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.UiNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Renders an accessibility tree as compact, role-tagged lines for the VLM prompt and the report. */
object UiTreeFormatter {
    fun format(root: UiNode): String = root.flatten()
        .filter { it.label.isNotBlank() }
        .joinToString("\n") { "${role(it)} ${it.label}" }

    fun role(n: UiNode): String = when {
        n.isCheckable -> {
            val kind = if (n.className.endsWith("Switch")) "Switch" else "CheckBox"
            "[$kind ${if (n.isChecked) "☑" else "☐"}]"
        }
        n.className.endsWith("Button") -> "[Button]"
        n.isClickable -> "[Link]"
        else -> "[Text]"
    }
}

/** Facts we can read straight from the accessibility tree, without any AI. These are OBSERVED evidence. */
object DeterministicScreenAuditor {
    private val WITHDRAW_CUES = listOf("withdraw", "revoke", "opt out", "opt-out", "वापस", "ಹಿಂಪಡೆ")
    private val LANGUAGES = linkedMapOf(
        "English" to listOf("english"),
        "Hindi" to listOf("hindi", "हिन्दी", "हिंदी"),
        "Kannada" to listOf("kannada", "ಕನ್ನಡ"),
        "Tamil" to listOf("tamil", "தமிழ்"),
        "Telugu" to listOf("telugu", "తెలుగు"),
        "Marathi" to listOf("marathi", "मराठी"),
        "Bengali" to listOf("bengali", "বাংলা"),
        "Malayalam" to listOf("malayalam", "മലയാളം"),
        "Gujarati" to listOf("gujarati", "ગુજરાતી"),
    )

    fun audit(root: UiNode): ScreenAudit {
        val nodes = root.flatten()
        val labels = nodes.map { it.label }.filter { it.isNotBlank() }
        if (!ConsentLexicon.isConsentScreen(labels)) return ScreenAudit(isConsentScreen = false)

        val observed = mutableSetOf<String>()
        val checkables = nodes.filter { it.isCheckable }
        val preTicked = checkables.any { it.isChecked }
        observed += "preTicked"

        val kinds = nodes.filter { it.isClickable }.mapNotNull { ConsentLexicon.classifyButton(it.label) }
        val rejectOption = when {
            ConsentKind.REJECT in kinds -> RejectOption.VISIBLE
            ConsentKind.SETTINGS in kinds -> RejectOption.HIDDEN
            ConsentKind.ACCEPT in kinds -> RejectOption.NONE
            else -> RejectOption.UNKNOWN
        }
        if (rejectOption != RejectOption.UNKNOWN) observed += "rejectOption"

        val allText = labels.joinToString(" ").lowercase()
        val withdrawal = if (WITHDRAW_CUES.any { allText.contains(it) }) true else null
        if (withdrawal == true) observed += "withdrawalInfo"

        val languages = LANGUAGES.filter { (_, cues) -> cues.any { allText.contains(it) } }.keys.toList()
        val languageOptions = languages.takeIf { it.size >= 2 }
        if (languageOptions != null) observed += "languageOptions"

        return ScreenAudit(
            isConsentScreen = true,
            preTicked = preTicked,
            rejectOption = rejectOption,
            withdrawalInfo = withdrawal,
            languageOptions = languageOptions,
            source = AuditSource.DETERMINISTIC,
            observedFields = observed,
            summary = "Read from the accessibility tree.",
        )
    }
}

/** Combines tree facts with the VLM's judgement. Observed facts win; disagreements are kept visible. */
object ScreenAuditMerger {
    fun merge(det: ScreenAudit, vlm: ScreenAudit?): ScreenAudit {
        if (vlm == null) return det
        val conflicts = mutableListOf<String>()
        val obs = det.observedFields

        val preTicked = if ("preTicked" in obs) {
            if (vlm.preTicked != null && vlm.preTicked != det.preTicked) conflicts += "preTicked: tree=${det.preTicked}, vlm=${vlm.preTicked}"
            det.preTicked
        } else vlm.preTicked

        val rejectOption = if ("rejectOption" in obs) {
            if (vlm.rejectOption != RejectOption.UNKNOWN && vlm.rejectOption != det.rejectOption) {
                conflicts += "rejectOption: tree=${det.rejectOption}, vlm=${vlm.rejectOption}"
            }
            det.rejectOption
        } else vlm.rejectOption

        val withdrawal = if (det.withdrawalInfo == true) true else vlm.withdrawalInfo
        val languages = when {
            det.languageOptions != null -> (det.languageOptions + (vlm.languageOptions ?: emptyList())).distinct()
            else -> vlm.languageOptions
        }

        return vlm.copy(
            isConsentScreen = det.isConsentScreen || vlm.isConsentScreen,
            preTicked = preTicked,
            rejectOption = rejectOption,
            withdrawalInfo = withdrawal,
            languageOptions = languages,
            source = AuditSource.MERGED,
            observedFields = obs,
            conflicts = conflicts,
            confidence = if (conflicts.isEmpty()) vlm.confidence else vlm.confidence * 0.7,
        )
    }
}

/** Tolerant parser for the VLM's JSON (handles code fences and chatty prefixes). */
object VlmResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private data class Dto(
        @SerialName("is_consent_screen") val isConsentScreen: Boolean? = null,
        @SerialName("pre_ticked") val preTicked: Boolean? = null,
        @SerialName("reject_option") val rejectOption: String? = null,
        @SerialName("purposes_count") val purposesCount: Int? = null,
        @SerialName("separate_choices") val separateChoices: Boolean? = null,
        @SerialName("itemised_data") val itemisedData: Boolean? = null,
        @SerialName("purpose_stated") val purposeStated: Boolean? = null,
        @SerialName("withdrawal_info") val withdrawalInfo: Boolean? = null,
        @SerialName("language_options") val languageOptions: List<String>? = null,
        @SerialName("child_audience_signals") val childAudienceSignals: Boolean? = null,
        @SerialName("dark_patterns") val darkPatterns: List<String> = emptyList(),
        val summary: String = "",
        val confidence: Double? = null,
    )

    fun parse(reply: String): ScreenAudit? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val dto = runCatching { json.decodeFromString<Dto>(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        val isConsent = dto.isConsentScreen ?: return null
        return ScreenAudit(
            isConsentScreen = isConsent,
            preTicked = dto.preTicked,
            rejectOption = when (dto.rejectOption?.lowercase()?.trim()) {
                "visible", "clear", "equal" -> RejectOption.VISIBLE
                "hidden", "harder", "secondary" -> RejectOption.HIDDEN
                "none", "missing", "absent" -> RejectOption.NONE
                else -> RejectOption.UNKNOWN
            },
            purposesCount = dto.purposesCount,
            separateChoices = dto.separateChoices,
            itemisedData = dto.itemisedData,
            purposeStated = dto.purposeStated,
            withdrawalInfo = dto.withdrawalInfo,
            languageOptions = dto.languageOptions,
            childAudienceSignals = dto.childAudienceSignals,
            darkPatterns = dto.darkPatterns,
            summary = dto.summary,
            confidence = (dto.confidence ?: 0.8).coerceIn(0.0, 1.0),
            source = AuditSource.VLM,
        )
    }
}

/** The instructions given to the on-device VLM (Gemma 4 E2B) together with the screenshot. */
object ConsentAuditPrompt {
    val SYSTEM = """
        You are a privacy-compliance reviewer for India's Digital Personal Data Protection Act 2023 (DPDP) and DPDP Rules 2025.
        You are shown a screenshot of a mobile app screen plus the text of its accessibility tree.
        Judge ONLY what is visible. Do not guess about things you cannot see.

        Reply with ONE JSON object and nothing else, using exactly these keys:
        {
          "is_consent_screen": boolean,          // is this a privacy / consent / data-sharing request?
          "pre_ticked": boolean,                 // is any consent checkbox or toggle already ON before the user acts?
          "reject_option": "visible" | "hidden" | "none",  // visible = a Reject/Decline button as easy as Accept; hidden = only via Manage/Settings or much less prominent; none = no way to refuse
          "purposes_count": integer,             // how many distinct purposes are mentioned (e.g. delivery, analytics, ads)
          "separate_choices": boolean,           // can the user agree to each purpose separately?
          "itemised_data": boolean,              // does the notice list WHICH personal data is collected (DPDP Rule 3(b)(i))?
          "purpose_stated": boolean,             // does it state the specific purpose (Rule 3(b)(ii))?
          "withdrawal_info": boolean,            // does it say how to withdraw consent later (Section 6(4), Rule 3(c))?
          "language_options": [string],          // languages the notice can be viewed in, e.g. ["English","Hindi"]
          "child_audience_signals": boolean,     // does the app look aimed at children (kids, school class, cartoon style)?
          "dark_patterns": [string],             // short names of manipulative designs you see
          "summary": string,                     // one sentence
          "confidence": number                   // 0 to 1
        }
    """.trimIndent()

    /** JSON Schema used for constrained decoding, so the model can only emit a valid audit object. */
    val JSON_SCHEMA = """
        {"type":"object","properties":{
          "is_consent_screen":{"type":"boolean"},
          "pre_ticked":{"type":"boolean"},
          "reject_option":{"type":"string","enum":["visible","hidden","none"]},
          "purposes_count":{"type":"integer"},
          "separate_choices":{"type":"boolean"},
          "itemised_data":{"type":"boolean"},
          "purpose_stated":{"type":"boolean"},
          "withdrawal_info":{"type":"boolean"},
          "language_options":{"type":"array","items":{"type":"string"}},
          "child_audience_signals":{"type":"boolean"},
          "dark_patterns":{"type":"array","items":{"type":"string"}},
          "summary":{"type":"string"},
          "confidence":{"type":"number"}
        },
        "required":["is_consent_screen","pre_ticked","reject_option","purposes_count","separate_choices","itemised_data",
                    "purpose_stated","withdrawal_info","language_options","child_audience_signals","dark_patterns","summary","confidence"]}
    """.trimIndent()

    fun userMessage(treeText: String): String =
        "Accessibility tree (role-tagged; ☑ = checked, ☐ = unchecked):\n$treeText\n\nReturn the JSON object now."
}
