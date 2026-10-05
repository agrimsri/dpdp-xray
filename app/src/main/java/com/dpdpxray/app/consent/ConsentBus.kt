package com.dpdpxray.app.consent

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Hand-off between the accessibility service and the orchestrator. Only the armed package is watched. */
object ConsentBus {
    private val _armed = MutableStateFlow<String?>(null)
    val armedPackage: StateFlow<String?> = _armed.asStateFlow()

    private val _events = MutableStateFlow<List<ConsentEvent>>(emptyList())
    val events: StateFlow<List<ConsentEvent>> = _events.asStateFlow()

    /** Becomes true when any accessibility event arrives from the armed app, i.e. it really is on screen. */
    private val _targetSeen = MutableStateFlow(false)
    val targetSeen: StateFlow<Boolean> = _targetSeen.asStateFlow()

    /** Screenshot/tree storage folder for the current session. */
    @Volatile var shotDir: java.io.File? = null

    /**
     * Manual mode: the choice the user was asked to make. Some UI toolkits (e.g. Jetpack Compose) do not report finger
     * taps to accessibility, so when the consent dialog disappears we record this choice, labelled as inferred.
     */
    @Volatile var expectedChoice: ConsentKind? = null

    val screenSeen: Boolean
        get() = _events.value.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }

    val choiceMade: Boolean
        get() = _events.value.any { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }

    fun arm(pkg: String, dir: java.io.File) {
        _events.value = emptyList()
        _targetSeen.value = false
        expectedChoice = null
        shotDir = dir.apply { mkdirs() }
        _armed.value = pkg
    }

    fun disarm() {
        _armed.value = null
        expectedChoice = null
    }

    fun markTargetSeen() {
        if (!_targetSeen.value) _targetSeen.value = true
    }

    /** Drops duplicates: the same kind within 1.5 s (e.g. our own programmatic tap plus the app's click event). */
    fun emit(event: ConsentEvent) {
        _events.update { list ->
            val dup = list.any { it.kind == event.kind && kotlin.math.abs(it.tNanos - event.tNanos) < 1_500_000_000L }
            if (dup) list else list + event
        }
    }
}
