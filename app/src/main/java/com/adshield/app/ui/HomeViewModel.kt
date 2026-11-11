package com.adshield.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.adshield.app.appContainer
import com.adshield.app.data.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val vpnRunning: Boolean = false,
    val todayTotal: Long = 0L,
    val ruleCount: Int = 0,
    val customCount: Int = 0,
    val blockDoh: Boolean = false,
    val customDomains: List<String> = emptyList(),
    val allowDomains: List<String> = emptyList(),
    val snapshot: StatsRepository.Snapshot? = null,
    val appLabels: Map<String, String> = emptyMap(),
    val error: String? = null,
    val message: String? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        viewModelScope.launch {
            container.vpnState.running.collect { running ->
                _state.update { it.copy(vpnRunning = running) }
            }
        }
        viewModelScope.launch {
            container.vpnState.lastError.collect { error ->
                _state.update { it.copy(error = error) }
            }
        }
        viewModelScope.launch {
            container.stats.todayTotal.collect { total ->
                _state.update { it.copy(todayTotal = total) }
            }
        }
        viewModelScope.launch {
            container.rules.stats.collect { stats ->
                _state.update {
                    it.copy(
                        ruleCount = stats.blocked,
                        customCount = stats.custom,
                        blockDoh = stats.blockDoh,
                    )
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private data class RefreshPayload(
        val snapshot: StatsRepository.Snapshot?,
        val labels: Map<String, String>,
        val custom: List<String>,
        val allow: List<String>,
    )

    private suspend fun refresh() {
        val payload = withContext(Dispatchers.IO) {
            val snap = runCatching { container.stats.snapshot() }.getOrNull()
            RefreshPayload(
                snapshot = snap,
                labels = resolveLabels(snap?.topPackages?.map { it.pkg }.orEmpty()),
                custom = container.rules.customDomains(),
                allow = container.rules.allowDomains(),
            )
        }
        _state.update {
            it.copy(
                snapshot = payload.snapshot,
                customDomains = payload.custom,
                allowDomains = payload.allow,
                appLabels = payload.labels,
            )
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun dismissError() {
        container.vpnState.clearError()
    }

    fun addCustomDomain(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val added = container.rules.addCustomDomain(input)
        _state.update {
            it.copy(
                message = if (added) "已添加：$input" else "域名无效或已存在",
                customDomains = container.rules.customDomains(),
            )
        }
    }

    fun removeCustomDomain(domain: String) {
        container.rules.removeCustomDomain(domain)
        _state.update {
            it.copy(
                customDomains = container.rules.customDomains(),
                message = "已移除：$domain",
            )
        }
    }

    fun addAllowDomain(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val added = container.rules.addAllowDomain(input)
        _state.update {
            it.copy(
                message = if (added) "已放行：$input" else "域名无效或已存在",
                allowDomains = container.rules.allowDomains(),
            )
        }
    }

    fun removeAllowDomain(domain: String) {
        container.rules.removeAllowDomain(domain)
        _state.update {
            it.copy(
                allowDomains = container.rules.allowDomains(),
                message = "已取消放行：$domain",
            )
        }
    }

    /** 切换 DoH 阻断：规则快照会异步重建，DNS 无需重启。 */
    fun toggleBlockDoh(enabled: Boolean) {
        container.rules.setBlockDoh(enabled)
        _state.update {
            it.copy(message = if (enabled) "已开启 DoH 阻断" else "已关闭 DoH 阻断")
        }
    }

    /** VPN 授权被拒绝时给出明确提示。 */
    fun onVpnPermissionDenied() {
        _state.update { it.copy(error = "未授予 VPN 权限，拦截无法开启") }
    }

    private fun resolveLabels(packages: List<String>): Map<String, String> {
        if (packages.isEmpty()) return emptyMap()
        val pm = getApplication<Application>().packageManager
        val out = HashMap<String, String>(packages.size)
        for (pkg in packages) {
            out[pkg] = runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(pkg)
        }
        return out
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 3_000L
    }
}

class HomeViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(application) as T
    }
}