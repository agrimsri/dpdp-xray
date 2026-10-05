package com.dpdpxray.core.audit

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class PhaseAssignerTest {
    private val events = listOf(NetEvent(100, "a.com"), NetEvent(200, "b.com"), NetEvent(300, "c.com"))

    @Test
    fun `events before an accept are BEFORE_CONSENT and after are AFTER_ACCEPT`() {
        val phased = PhaseAssigner.assign(events, listOf(ConsentEvent(50, ConsentKind.SCREEN_APPEARED), ConsentEvent(200, ConsentKind.ACCEPT)))
        assertEquals(listOf(Phase.BEFORE_CONSENT, Phase.AFTER_ACCEPT, Phase.AFTER_ACCEPT), phased.map { it.phase })
    }

    @Test
    fun `events after a reject are AFTER_REJECT`() {
        val phased = PhaseAssigner.assign(events, listOf(ConsentEvent(150, ConsentKind.REJECT)))
        assertEquals(listOf(Phase.BEFORE_CONSENT, Phase.AFTER_REJECT, Phase.AFTER_REJECT), phased.map { it.phase })
    }

    @Test
    fun `screen seen but never answered means everything is BEFORE_CONSENT`() {
        val phased = PhaseAssigner.assign(events, listOf(ConsentEvent(10, ConsentKind.SCREEN_APPEARED)))
        assertEquals(List(3) { Phase.BEFORE_CONSENT }, phased.map { it.phase })
    }

    @Test
    fun `no consent screen at all means NO_CONSENT_SEEN`() {
        val phased = PhaseAssigner.assign(events, emptyList())
        assertEquals(List(3) { Phase.NO_CONSENT_SEEN }, phased.map { it.phase })
    }

    @Test
    fun `the first choice decides and settings taps are ignored`() {
        val phased = PhaseAssigner.assign(
            events,
            listOf(ConsentEvent(120, ConsentKind.SETTINGS), ConsentEvent(250, ConsentKind.REJECT), ConsentEvent(260, ConsentKind.ACCEPT)),
        )
        assertEquals(listOf(Phase.BEFORE_CONSENT, Phase.BEFORE_CONSENT, Phase.AFTER_REJECT), phased.map { it.phase })
    }

    @Test
    fun `output is sorted by time even if input is not`() {
        val phased = PhaseAssigner.assign(events.reversed(), emptyList())
        assertEquals(listOf(100L, 200L, 300L), phased.map { it.tNanos })
    }
}
