package com.adshield.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 轻量设置存储。M1 阶段用 SharedPreferences：
 * 写入量小、同步读取、无异步初始化问题；M3 迁移到 DataStore Proto。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)
    /** 规则相关设置变化时自增，规则仓库据此重建快照。 */
    val revision: StateFlow<Int> = _revision

    var vpnWanted: Boolean
        get() = prefs.getBoolean(KEY_VPN_WANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_VPN_WANTED, value).apply()

    var countUnattributed: Boolean
        get() = prefs.getBoolean(KEY_COUNT_UNATTRIBUTED, true)
        set(value) = prefs.edit().putBoolean(KEY_COUNT_UNATTRIBUTED, value).apply()

    /** 阻断浏览器/App 的加密 DNS（DoH/DoT）。默认关闭，开启可能影响部分 App 联网。 */
    var blockDoh: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_DOH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_DOH, value).apply()
            _revision.value++
        }

    fun customDomains(): List<String> =
        prefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()).orEmpty().sorted()

    /** 白名单：优先于任何黑名单规则（含内置库），用于纠正误拦。 */
    fun allowDomains(): List<String> =
        prefs.getStringSet(KEY_ALLOW_DOMAINS, emptySet()).orEmpty().sorted()

    fun addAllowDomain(raw: String): Boolean {
        val domain = normalizeDomain(raw)
        if (domain.isEmpty()) return false
        val current = prefs.getStringSet(KEY_ALLOW_DOMAINS, emptySet()).orEmpty().toMutableSet()
        if (!current.add(domain)) return false
        prefs.edit().putStringSet(KEY_ALLOW_DOMAINS, current).apply()
        _revision.value++
        return true
    }

    fun removeAllowDomain(domain: String): Boolean {
        val current = prefs.getStringSet(KEY_ALLOW_DOMAINS, emptySet()).orEmpty().toMutableSet()
        if (!current.remove(domain)) return false
        prefs.edit().putStringSet(KEY_ALLOW_DOMAINS, current).apply()
        _revision.value++
        return true
    }

    private fun normalizeDomain(raw: String): String =
        com.adshield.app.rules.DomainMatcher.normalize(raw)

    fun addCustomDomain(raw: String): Boolean {
        val domain = com.adshield.app.rules.DomainMatcher.normalize(raw)
        if (domain.isEmpty()) return false
        val current = prefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()).orEmpty().toMutableSet()
        if (!current.add(domain)) return false
        prefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).apply()
        _revision.value++
        return true
    }

    fun removeCustomDomain(domain: String): Boolean {
        val current = prefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()).orEmpty().toMutableSet()
        if (!current.remove(domain)) return false
        prefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).apply()
        _revision.value++
        return true
    }

    private companion object {
        const val FILE = "adshield_settings"
        const val KEY_VPN_WANTED = "vpn_wanted"
        const val KEY_CUSTOM_DOMAINS = "custom_domains"
        const val KEY_ALLOW_DOMAINS = "allow_domains"
        const val KEY_COUNT_UNATTRIBUTED = "count_unattributed"
        const val KEY_BLOCK_DOH = "block_doh"
    }
}