package com.dpdpxray.app.capture

import com.dpdpxray.core.model.NetEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Starting : CaptureState
    data class Running(val targetPackage: String) : CaptureState
    data class Error(val message: String) : CaptureState
}

/** Process-wide hand-off between the VPN service and the audit orchestrator/UI. */
object CaptureBus {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<NetEvent>>(emptyList())
    val events: StateFlow<List<NetEvent>> = _events.asStateFlow()

    fun reset() {
        _events.value = emptyList()
    }

    fun setState(s: CaptureState) {
        _state.value = s
    }

    fun record(event: NetEvent) {
        _events.update { it + event }
    }
}
