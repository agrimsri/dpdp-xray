package com.dpdpxray.core.audit

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase

/** Labels each network event relative to the user's first consent choice (both streams share one clock). */
object PhaseAssigner {
    fun assign(events: List<NetEvent>, consentEvents: List<ConsentEvent>): List<NetEvent> {
        val sorted = events.sortedBy { it.tNanos }
        val choice = consentEvents.sortedBy { it.tNanos }
            .firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
        val screenSeen = consentEvents.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }

        return sorted.map { e ->
            val phase = when {
                choice != null && e.tNanos < choice.tNanos -> Phase.BEFORE_CONSENT
                choice != null && choice.kind == ConsentKind.ACCEPT -> Phase.AFTER_ACCEPT
                choice != null -> Phase.AFTER_REJECT
                screenSeen -> Phase.BEFORE_CONSENT
                else -> Phase.NO_CONSENT_SEEN
            }
            e.copy(phase = phase)
        }
    }
}
