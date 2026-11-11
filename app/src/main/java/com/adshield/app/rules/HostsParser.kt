package com.adshield.app.rules

/**
 * hosts 与简易域名列表解析器。
 *
 * 支持的行格式：
 * ```
 * # 注释
 * 0.0.0.0 ads.example.com
 * 127.0.0.1 tracker.example.com
 * ads.example.com            // 纯域名（默认按黑名单处理）
 * @@||example.com^           // AdGuard 风格白名单
 * ```
 */
object HostsParser {

    private const val MAX_LINE_LENGTH = 512

    fun parse(text: String, source: String, into: MutableList<DomainRule>): Int {
        var count = 0
        for (rawLine in text.lineSequence()) {
            if (count > 200_000) break // 防御异常大的订阅文件
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.length > MAX_LINE_LENGTH) continue

            if (line.startsWith("@@||")) {
                // @@||domain^  或  @@||domain^$important
                var d = line.removePrefix("@@||")
                val cut = d.indexOfAny(charArrayOf('^', '$', '/', '|'))
                if (cut >= 0) d = d.substring(0, cut)
                val n = DomainMatcher.normalize(d)
                if (n.isNotEmpty()) {
                    into.add(DomainRule(n, DomainMatcher.SOURCE_ALLOW))
                    count++
                }
                continue
            }

            val parts = line.split(' ', '\t')
            val candidate = when (parts.size) {
                1 -> parts[0]
                else -> {
                    val first = parts[0]
                    // 只有第一段是 IP 时才认定为 hosts 格式
                    if (first == "0.0.0.0" || first == "127.0.0.1" || first == "::" ||
                        first == "::1" || first == "255.255.255.255"
                    ) parts[1] else continue
                }
            }
            // 排除 hosts 里的本机名与明显非域名项
            if (candidate.equals("localhost", ignoreCase = true)) continue
            val n = DomainMatcher.normalize(candidate)
            if (n.isEmpty()) continue
            into.add(DomainRule(n, source))
            count++
        }
        return count
    }
}