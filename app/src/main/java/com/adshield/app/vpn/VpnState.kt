package com.adshield.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** VPN 运行状态（同进程共享给 UI）。 */
object VpnState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun markRunning() {
        _lastError.value = null
        _running.value = true
    }

    fun markStopped() {
        _running.value = false
    }

    fun markError(message: String) {
        _running.value = false
        _lastError.value = message
    }

    fun clearError() {
        _lastError.value = null
    }
}