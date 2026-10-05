package com.dpdpxray.core.report

import com.dpdpxray.core.dpdp.Bm25Retriever
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixesCitationsReportTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val s = 1_000_000_000L

    private val leakySession = AuditSession(
        id = "a", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.ACCEPT,
        endedAtNanos = 30 * s,
        netEvents = listOf(
            NetEvent(1 * s, "app-measurement.com"), NetEvent(2 * s, "graph.facebook.com"),
            NetEvent(3 * s, "firebase-settings.crashlytics.com"), NetEvent(9 * s, "api.leakyshop.example"),
        ),
        consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT)),
        screenAudit = ScreenAudit(
            isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN, purposesCount = 3,
            separateChoices = false, itemisedData = false, purposeStated = true, withdrawalInfo = false,
            languageOptions = listOf("English"), source = AuditSource.MERGED, observedFields = setOf("preTicked", "rejectOption"),
        ),
    )
    private val result = RulesEngine(catalog).evaluate(listOf(leakySession))

    @Test
    fun `fixes cover every SDK seen before consent plus the consent-screen problems`() {
        val fixes = FixCatalog.loadDefault().fixesFor(result, catalog)
        val ids = fixes.map { it.id }
        assertTrue(ids.containsAll(listOf("firebase-analytics", "facebook")))
        assertTrue(ids.containsAll(listOf("ui-preticked", "ui-reject", "ui-bundled", "ui-withdraw", "ui-language")))
        assertEquals("SDK fixes come first", "sdk", fixes.first().kind)
        val fb = fixes.single { it.id == "facebook" }
        assertTrue(fb.manifest!!.contains("com.facebook.sdk.AutoInitEnabled"))
        assertTrue(fb.code!!.contains("FacebookSdk.fullyInitialize()"))
        val fa = fixes.single { it.id == "firebase-analytics" }
        assertTrue(fa.manifest!!.contains("firebase_analytics_collection_enabled"))
    }

    @Test
    fun `no findings means no fixes`() {
        val clean = RulesEngine(catalog).evaluate(listOf(leakySession.copy(netEvents = listOf(NetEvent(1, "a.example"), NetEvent(2, "b.example")), screenAudit = null)))
        assertTrue(FixCatalog.loadDefault().fixesFor(clean, catalog).isEmpty())
    }

    @Test
    fun `corpus holds verbatim key sections`() {
        val corpus = DpdpCorpus.loadDefault()
        val s61 = corpus.byId("s6(1)")!!
        assertTrue(s61.text.contains("free, specific, informed, unconditional and unambiguous with a clear affirmative action"))
        assertTrue(corpus.byId("s9(3)")!!.text.contains("tracking or behavioural monitoring of children"))
        assertTrue(corpus.byId("r3(b)") != null)
    }

    @Test
    fun `bm25 finds the right section for plain-language questions`() {
        val bm25 = Bm25Retriever(DpdpCorpus.loadDefault().passages)
        assertEquals("s9(3)", bm25.search("children tracking targeted advertising", 1).first().passage.id)
        assertEquals("s6(1)", bm25.search("clear affirmative action consent free specific", 1).first().passage.id)
        assertTrue(bm25.search("withdraw consent ease comparable", 2).map { it.passage.id }.contains("s6(4)"))
        assertTrue(bm25.search("notice language Eighth Schedule English", 2).map { it.passage.id }.any { it == "s5(3)" || it == "s6(3)" })
    }

    @Test
    fun `citations are exactly the finding's own references`() {
        val cs = CitationService(DpdpCorpus.loadDefault())
        val c1 = result.findings.single { it.checkId == CheckId.C1 }
        assertEquals(listOf("s4(1)", "s6(1)"), cs.cite(c1, 3).map { it.id })
    }

    @Test
    fun `search fills in only when a finding has no references`() {
        val cs = CitationService(DpdpCorpus.loadDefault())
        val bare = result.findings.first().copy(dpdpRefs = emptyList(), title = "Tracking of children", explanation = "children tracking advertising")
        assertEquals("s9(3)", cs.cite(bare, 3).first().id)
    }

    @Test
    fun `markdown report has score, findings, evidence, citations, fixes and the disclaimer`() {
        val md = MarkdownReport.build(
            result, listOf(leakySession), FixCatalog.loadDefault().fixesFor(result, catalog), CitationService(DpdpCorpus.loadDefault()),
        )
        assertTrue(md.contains("LeakyShop"))
        assertTrue(md.contains("Score: ${result.score}/100"))
        assertTrue(md.contains(CheckId.C1.title))
        assertTrue(md.contains("app-measurement.com"))
        assertTrue(md.contains("clear affirmative action"))
        assertTrue(md.contains("AutoInitEnabled"))
        assertTrue(md.contains("not legal advice"))
        assertTrue(md.contains("0 bytes of audit data"))
    }

    @Test
    fun `inconclusive report says so`() {
        val r = RulesEngine(catalog).evaluate(listOf(leakySession.copy(netEvents = listOf(NetEvent(1, "a.example")), screenAudit = null)))
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        val md = MarkdownReport.build(r, listOf(leakySession), emptyList(), CitationService(DpdpCorpus.loadDefault()))
        assertTrue(md.contains("Inconclusive"))
    }

    @Test
    fun `session json round-trips`() {
        val json = AuditJson.encodeSessions(listOf(leakySession))
        assertEquals(listOf(leakySession), AuditJson.decodeSessions(json))
    }
}
