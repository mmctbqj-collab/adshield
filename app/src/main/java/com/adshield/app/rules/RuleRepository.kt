package com.adshield.app.rules

import com.adshield.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则仓库：合并内置广告库、（可选）DoH 域名库、用户自定义域名，
 * 产出一个不可变的 [DomainMatcher] 快照。
 *
 * 线程安全：快照通过 StateFlow 发布，DNS 引擎每次判定都读取最新快照，
 * 因此规则变更（含 DoH 开关）无需重启 VPN。
 */
class RuleRepository(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val _matcher = MutableStateFlow(DomainMatcher.EMPTY)
    val matcher: StateFlow<DomainMatcher> = _matcher

    private val _stats = MutableStateFlow(RuleStats(0, 0, false))
    val stats: StateFlow<RuleStats> = _stats

    data class RuleStats(
        val blocked: Int,
        val custom: Int,
        val blockDoh: Boolean,
    )

    init {
        scope.launch { rebuild() }
        scope.launch { settings.revision.collect { rebuild() } }
    }

    fun current(): DomainMatcher = _matcher.value

    suspend fun rebuild() = withContext(Dispatchers.Default) {
        val rules = ArrayList<DomainRule>(4096)
        HostsParser.parse(BuiltinRules.ADS, DomainMatcher.SOURCE_BUILTIN, rules)

        val custom = settings.customDomains()
        custom.forEach { rules.add(DomainRule(it, DomainMatcher.SOURCE_CUSTOM)) }

        // 白名单最后加入：匹配时白名单优先，不受规则顺序影响
        settings.allowDomains().forEach { rules.add(DomainRule(it, DomainMatcher.SOURCE_ALLOW)) }

        val blockDoh = settings.blockDoh
        if (blockDoh) {
            HostsParser.parse(BuiltinRules.DOH, DomainMatcher.SOURCE_DOH, rules)
        }

        val matcher = DomainMatcher.fromRules(rules)
        _matcher.value = matcher
        _stats.value = RuleStats(matcher.blockedCount, custom.size, blockDoh)
    }

    fun addCustomDomain(raw: String): Boolean = settings.addCustomDomain(raw)

    fun removeCustomDomain(domain: String): Boolean = settings.removeCustomDomain(domain)

    fun customDomains(): List<String> = settings.customDomains()

    fun allowDomains(): List<String> = settings.allowDomains()

    fun addAllowDomain(raw: String): Boolean = settings.addAllowDomain(raw)

    fun removeAllowDomain(domain: String): Boolean = settings.removeAllowDomain(domain)

    fun setBlockDoh(enabled: Boolean) {
        settings.blockDoh = enabled
    }
}