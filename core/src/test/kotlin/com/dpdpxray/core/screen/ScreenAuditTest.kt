package com.dpdpxray.core.screen

import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAuditTest {

    @Test
    fun `deterministic audit of the leaky dialog`() {
        val a = DeterministicScreenAuditor.audit(Fixtures.leakyDialog)
        assertTrue(a.isConsentScreen)
        assertEquals(true, a.preTicked)
        assertEquals(RejectOption.HIDDEN, a.rejectOption) // only a "Manage" link, no reject button
        assertTrue("preTicked" in a.observedFields)
        assertTrue("rejectOption" in a.observedFields)
        assertNull(a.languageOptions)
        assertEquals(AuditSource.DETERMINISTIC, a.source)
    }

    @Test
    fun `deterministic audit of the fixed dialog`() {
        val a = DeterministicScreenAuditor.audit(Fixtures.fixedDialog)
        assertTrue(a.isConsentScreen)
        assertEquals(false, a.preTicked)
        assertEquals(RejectOption.VISIBLE, a.rejectOption)
        assertEquals(true, a.withdrawalInfo)
        assertTrue(a.languageOptions!!.containsAll(listOf("English", "Hindi", "Kannada")))
    }

    @Test
    fun `no accept or reject and no settings means NONE`() {
        val tree = Fixtures.label("We use cookies and personal data to show ads").copy(
            children = listOf(Fixtures.button("Accept")),
        )
        assertEquals(RejectOption.NONE, DeterministicScreenAuditor.audit(tree).rejectOption)
    }

    @Test
    fun `an ordinary screen is not a consent screen`() {
        assertFalse(DeterministicScreenAuditor.audit(Fixtures.productGrid).isConsentScreen)
    }

    @Test
    fun `tree text for the prompt shows roles and checkbox state`() {
        val text = UiTreeFormatter.format(Fixtures.leakyDialog)
        assertTrue(text.contains("[CheckBox ☑] Share usage data to improve offers"))
        assertTrue(text.contains("[Button] Accept & Continue"))
        assertTrue(text.contains("[Link] Manage"))
    }

    @Test
    fun `parses a VLM reply wrapped in a code fence with prose around it`() {
        val reply = """
            Sure! Here is the analysis:
            ```json
            {"is_consent_screen": true, "pre_ticked": true, "reject_option": "hidden", "purposes_count": 2,
             "separate_choices": false, "itemised_data": false, "purpose_stated": true, "withdrawal_info": false,
             "language_options": ["English"], "child_audience_signals": false,
             "dark_patterns": ["pre-ticked box", "reject hidden behind Manage"], "summary": "Bundled consent."}
            ```
        """.trimIndent()
        val a = VlmResponseParser.parse(reply)!!
        assertEquals(true, a.preTicked)
        assertEquals(RejectOption.HIDDEN, a.rejectOption)
        assertEquals(2, a.purposesCount)
        assertEquals(listOf("English"), a.languageOptions)
        assertEquals(2, a.darkPatterns.size)
        assertEquals(AuditSource.VLM, a.source)
    }

    @Test
    fun `garbage or missing fields give null instead of crashing`() {
        assertNull(VlmResponseParser.parse("I cannot help with that."))
        assertNull(VlmResponseParser.parse("{not json"))
        assertNull(VlmResponseParser.parse("""{"pre_ticked": true}""")) // is_consent_screen is required
    }

    @Test
    fun `merge keeps observed facts and flags conflicts`() {
        val det = DeterministicScreenAuditor.audit(Fixtures.leakyDialog)
        val vlm = ScreenAudit(
            isConsentScreen = true, preTicked = false, rejectOption = RejectOption.HIDDEN,
            purposesCount = 2, separateChoices = false, itemisedData = false, purposeStated = true,
            withdrawalInfo = false, languageOptions = listOf("English"), source = AuditSource.VLM, confidence = 0.9,
        )
        val m = ScreenAuditMerger.merge(det, vlm)
        assertEquals(AuditSource.MERGED, m.source)
        assertEquals(true, m.preTicked) // the tree saw a checked box: that wins
        assertTrue(m.conflicts.any { it.contains("preTicked") })
        assertTrue(m.confidence < 0.9)
        assertEquals(2, m.purposesCount) // VLM-only field carried over
        assertEquals(listOf("English"), m.languageOptions)
        assertTrue("preTicked" in m.observedFields)
        assertFalse("purposesCount" in m.observedFields)
    }

    @Test
    fun `merge without a VLM result returns the deterministic audit`() {
        val det = DeterministicScreenAuditor.audit(Fixtures.leakyDialog)
        assertEquals(det, ScreenAuditMerger.merge(det, null))
    }

    @Test
    fun `constrained-decoding schema is valid JSON and requires every key the parser reads`() {
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(ConsentAuditPrompt.JSON_SCHEMA)
        val required = schema.jsonObjectValue("required").toString()
        for (key in listOf("is_consent_screen", "pre_ticked", "reject_option", "itemised_data", "language_options", "confidence")) {
            assertTrue(key, required.contains(key))
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectValue(key: String) =
        (this as kotlinx.serialization.json.JsonObject).getValue(key)

    @Test
    fun `prompt contains the schema keys and the tree text`() {
        val p = ConsentAuditPrompt.userMessage(UiTreeFormatter.format(Fixtures.leakyDialog))
        for (key in listOf("is_consent_screen", "pre_ticked", "reject_option", "itemised_data", "language_options")) {
            assertTrue(key, ConsentAuditPrompt.SYSTEM.contains(key))
        }
        assertTrue(p.contains("Share usage data"))
    }
}
