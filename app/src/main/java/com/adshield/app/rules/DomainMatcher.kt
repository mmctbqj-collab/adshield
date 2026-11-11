package com.adshield.app.rules

/**
 * 一条域名规则。
 *
 * @param domain 规范化后的域名（小写、无前导点、无尾点）
 * @param source 来源标记，用于诊断与统计（builtin / custom / doh / allow）
 */
data class DomainRule(
    val domain: String,
    val source: String,
)

/**
 * 域名匹配器（不可变快照）。
 *
 * 匹配规则：
 * 1. 黑名单按"最长后缀优先"生效：规则 `ad.qq.com` 会拦住 `ad.qq.com` 与 `x.ad.qq.com`。
 *    这对广告 SDK 的随机子域（`a1b2.ads.example.com`）是必需的。
 * 2. 任意白名单条目命中即放行，白名单优先于黑名单。
 * 3. 命中时返回带来源的 ruleId（如 `block:custom`），便于记录页诊断"是哪条库拦的"。
 *
 * 复杂度：O(label 数量)，与规则条数无关（哈希查找）。
 */
class DomainMatcher private constructor(
    private val blockedSuffixes: Map<String, String>,
    private val allowedSuffixes: Set<String>,
) {

    data class Verdict(
        val blocked: Boolean,
        val ruleId: String? = null,
    )

    fun match(rawDomain: String): Verdict {
        val domain = normalize(rawDomain)
        if (domain.isEmpty() || !domain.contains('.')) return Verdict(false, null)

        if (matchesAnySuffix(domain, allowedSuffixes)) {
            return Verdict(false, ALLOW_RULE_ID)
        }
        val source = findBySuffix(domain, blockedSuffixes)
        return if (source != null) Verdict(true, BLOCK_RULE_PREFIX + source) else Verdict(false, null)
    }

    private fun matchesAnySuffix(domain: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        if (set.contains(domain)) return true
        var idx = domain.indexOf('.')
        while (idx in 1 until domain.length - 1) {
            if (set.contains(domain.substring(idx + 1))) return true
            idx = domain.indexOf('.', idx + 1)
        }
        return false
    }

    /** 最长后缀优先：先查完整域名，再逐段去前缀。 */
    private fun findBySuffix(domain: String, map: Map<String, String>): String? {
        map[domain]?.let { return it }
        var idx = domain.indexOf('.')
        while (idx in 1 until domain.length - 1) {
            map[domain.substring(idx + 1)]?.let { return it }
            idx = domain.indexOf('.', idx + 1)
        }
        return null
    }

    val blockedCount: Int get() = blockedSuffixes.size
    val allowedCount: Int get() = allowedSuffixes.size

    fun hasDomain(domain: String): Boolean = blockedSuffixes.containsKey(normalize(domain))

    companion object {
        const val BLOCK_RULE_PREFIX = "block:"
        const val ALLOW_RULE_ID = "allow:local"

        val EMPTY: DomainMatcher = DomainMatcher(emptyMap(), emptySet())

        /** 规范化：小写、去空白、去前导点与尾点。非法（含空格/通配/路径）返回空串。 */
        fun normalize(raw: String): String {
            var d = raw.trim().lowercase()
            while (d.startsWith('.')) d = d.substring(1)
            while (d.endsWith('.')) d = d.dropLast(1)
            if (d.isEmpty()) return ""
            if (d.any { it == ' ' || it == '\t' || it == '/' || it == '\\' || it == '*' }) return ""
            if (!d.contains('.')) return ""
            return d
        }

        fun fromRules(rules: List<DomainRule>): DomainMatcher {
            val blocked = HashMap<String, String>(rules.size.coerceAtLeast(16))
            val allowed = HashSet<String>(8)
            for (rule in rules) {
                val d = normalize(rule.domain)
                if (d.isEmpty()) continue
                if (rule.source == SOURCE_ALLOW) allowed.add(d) else blocked[d] = rule.source
            }
            return DomainMatcher(blocked, allowed)
        }

        const val SOURCE_BUILTIN = "builtin"
        const val SOURCE_CUSTOM = "custom"
        const val SOURCE_DOH = "doh"
        const val SOURCE_ALLOW = "allow"
    }
}