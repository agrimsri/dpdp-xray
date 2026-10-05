package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val s = 1_000_000_000L // one second in nanos

    private fun session(
        plan: AuditPlan,
        hosts: List<Pair<Long, String>>,
        consent: List<ConsentEvent> = emptyList(),
        screen: ScreenAudit? = null,
        childFacing: Boolean = false,
        privateDns: Boolean? = false,
    ) = AuditSession(
        id = plan.name, targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = plan,
        childFacing = childFacing, startedAtNanos = 0, endedAtNanos = 40 * s,
        netEvents = hosts.map { (t, h) -> NetEvent(tNanos = t, hostname = h) },
        consentEvents = consent, screenAudit = screen, privateDnsActive = privateDns,
    )

    private val cleanScreen = ScreenAudit(
        isConsentScreen = true, preTicked = false, rejectOption = RejectOption.VISIBLE, purposesCount = 1,
        separateChoices = true, itemisedData = true, purposeStated = true, withdrawalInfo = true,
        languageOptions = listOf("English", "Hindi"), childAudienceSignals = false,
        source = AuditSource.MERGED, observedFields = setOf("preTicked", "rejectOption"),
    )

    private val leakyScreen = cleanScreen.copy(
        preTicked = true, rejectOption = RejectOption.HIDDEN, purposesCount = 3, separateChoices = false,
        itemisedData = false, withdrawalInfo = false, languageOptions = listOf("English"),
    )

    private val acceptAt5 = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT))
    private val rejectAt5 = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.REJECT))

    private fun ids(r: com.dpdpxray.core.model.AuditResult) = r.findings.map { it.checkId }.toSet()

    @Test
    fun `trackers before accept give C1 with entities and timing evidence`() {
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(1 * s to "app-measurement.com", 2 * s to "graph.facebook.com", 8 * s to "api.leakyshop.example"), acceptAt5, cleanScreen)))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertEquals(Severity.HIGH, c1.severity)
        assertEquals(EvidenceType.OBSERVED, c1.evidenceType)
        assertEquals(PenaltyBand.RESIDUAL_50, c1.penaltyBand)
        assertTrue(c1.entities.containsAll(listOf("Google Analytics for Firebase", "Meta (Facebook SDK)")))
        assertTrue(c1.evidence.any { it.contains("app-measurement.com") && it.contains("before") })
        assertTrue(c1.dpdpRefs.contains("s6(1)"))
    }

    @Test
    fun `first-party and unknown hosts before consent do not trigger C1`() {
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(1 * s to "api.leakyshop.example", 2 * s to "cdn.leakyshop.example"), acceptAt5, cleanScreen)))
        assertFalse(CheckId.C1 in ids(r))
        assertEquals(ScoreBand.READY, r.band)
        assertEquals(100, r.score)
    }

    @Test
    fun `trackers after reject give a critical C2`() {
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(8 * s to "graph.facebook.com"), rejectAt5, cleanScreen)))
        val c2 = r.findings.single { it.checkId == CheckId.C2 }
        assertEquals(Severity.CRITICAL, c2.severity)
        assertEquals(listOf(AuditPlan.REJECT), c2.plans)
    }

    @Test
    fun `leaky consent screen gives C3 to C8 with correct evidence types`() {
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(8 * s to "api.leakyshop.example", 9 * s to "x.leakyshop.example"), acceptAt5, leakyScreen)))
        val byId = r.findings.associateBy { it.checkId }
        for (id in listOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8)) assertTrue("$id", id in byId)
        assertEquals(EvidenceType.OBSERVED, byId.getValue(CheckId.C3).evidenceType) // from the tree
        assertEquals(EvidenceType.INFERRED, byId.getValue(CheckId.C5).evidenceType) // from the VLM
    }

    @Test
    fun `unknown screen fields never fire screen checks`() {
        val unknown = ScreenAudit(isConsentScreen = true, source = AuditSource.DETERMINISTIC)
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(8 * s to "api.leakyshop.example", 9 * s to "b.leakyshop.example"), acceptAt5, unknown)))
        assertTrue(ids(r).none { it in setOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8) })
    }

    @Test
    fun `no consent screen but trackers gives C1 and NC`() {
        val r = engine.evaluate(listOf(session(AuditPlan.SILENT, listOf(1 * s to "app-measurement.com", 3 * s to "api.leakyshop.example"))))
        assertTrue(CheckId.C1 in ids(r))
        assertTrue(CheckId.NC in ids(r))
    }

    @Test
    fun `almost no DNS events gives V0 and an inconclusive result`() {
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(1 * s to "api.leakyshop.example"), acceptAt5, cleanScreen, privateDns = true)))
        assertTrue(CheckId.V0 in ids(r))
        assertNull(r.score)
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertTrue(r.findings.single { it.checkId == CheckId.V0 }.explanation.contains("Private DNS"))
    }

    @Test
    fun `child-facing app with any tracker gives critical C9 in the 200 crore band`() {
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(8 * s to "app-measurement.com", 9 * s to "api.leakyshop.example"), acceptAt5, cleanScreen, childFacing = true)))
        val c9 = r.findings.single { it.checkId == CheckId.C9 }
        assertEquals(Severity.CRITICAL, c9.severity)
        assertEquals(PenaltyBand.CHILDREN_200, c9.penaltyBand)
        assertEquals(EvidenceType.OBSERVED, c9.evidenceType)
        assertTrue(c9.dpdpRefs.contains("s9(3)"))
    }

    @Test
    fun `child signals from the VLM alone make C9 inferred`() {
        val kidsScreen = cleanScreen.copy(childAudienceSignals = true)
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(8 * s to "graph.facebook.com", 9 * s to "api.leakyshop.example"), acceptAt5, kidsScreen)))
        assertEquals(EvidenceType.INFERRED, r.findings.single { it.checkId == CheckId.C9 }.evidenceType)
    }

    @Test
    fun `ad-id SDK contacted before consent gives inferred C10`() {
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, listOf(1 * s to "launches.appsflyersdk.com", 9 * s to "api.leakyshop.example"), acceptAt5, cleanScreen)))
        val c10 = r.findings.single { it.checkId == CheckId.C10 }
        assertEquals(EvidenceType.INFERRED, c10.evidenceType)
        assertTrue(c10.entities.contains("AppsFlyer"))
    }

    @Test
    fun `findings from several plans are merged into one per check`() {
        val r = engine.evaluate(
            listOf(
                session(AuditPlan.SILENT, listOf(1 * s to "app-measurement.com", 2 * s to "api.leakyshop.example"), listOf(ConsentEvent(1 * s, ConsentKind.SCREEN_APPEARED))),
                session(AuditPlan.ACCEPT, listOf(1 * s to "graph.facebook.com", 9 * s to "api.leakyshop.example"), acceptAt5, cleanScreen),
            ),
        )
        val c1 = r.findings.filter { it.checkId == CheckId.C1 }
        assertEquals(1, c1.size)
        assertEquals(setOf(AuditPlan.SILENT, AuditPlan.ACCEPT), c1.single().plans.toSet())
    }

    @Test
    fun `score arithmetic and bands`() {
        assertEquals(100 to ScoreBand.READY, ScoreCalculator.compute(emptyList()).let { it.first to it.second })
        val high = fake(Severity.HIGH, EvidenceType.OBSERVED)
        val crit = fake(Severity.CRITICAL, EvidenceType.OBSERVED)
        val medInf = fake(Severity.MEDIUM, EvidenceType.INFERRED)
        assertEquals(85 to ScoreBand.READY, ScoreCalculator.compute(listOf(high)))
        assertEquals(51 to ScoreBand.AT_RISK, ScoreCalculator.compute(listOf(high, crit, medInf)))
        assertEquals(0 to ScoreBand.AT_RISK, ScoreCalculator.compute(List(5) { crit }))
        // 15 + 8 + 1.5 + 4 = 28.5 → 71.5 → rounds to 72
        assertEquals(72 to ScoreBand.NEEDS_WORK, ScoreCalculator.compute(listOf(high, fake(Severity.MEDIUM, EvidenceType.OBSERVED), fake(Severity.LOW, EvidenceType.INFERRED), fake(Severity.MEDIUM, EvidenceType.INFERRED))))
    }

    private fun fake(sev: Severity, ev: EvidenceType) = com.dpdpxray.core.model.Finding(
        CheckId.C1, sev, ev, "t", "e", emptyList(), emptyList(), emptyList(), PenaltyBand.RESIDUAL_50,
    )
}
