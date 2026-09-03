package com.jinatra.hiberna.privilege

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ShizukuGate(private val platform: ShizukuPlatform) {

    private val _state = MutableStateFlow(evaluate())
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    fun refresh() { _state.value = evaluate() }

    /**
     * Asking for permission when the binder is dead throws inside the SDK, so
     * the gate refuses rather than surfacing a crash the user cannot act on.
     */
    fun request() {
        if (!platform.isBinderAlive) return
        platform.requestPermission()
        refresh()
    }

    private fun evaluate(): PrivilegeState = when {
        !platform.isBinderAlive -> PrivilegeState.SERVICE_NOT_RUNNING
        !platform.checkSelfPermission() -> PrivilegeState.PERMISSION_DENIED
        else -> PrivilegeState.READY
    }
}
