package com.adshield.app.a11y

/**
 * 点击前的安全阀。
 *
 * 这是整个 M2 里最容易被忽略、但风险最高的部分：
 * 一个错误的点击可能造成扣款、开通会员、同意隐私协议。因此所有拦截条件都是"默认拒绝"。
 */
class SafetyGuard {

    data class Decision(val allowed: Boolean, val reason: String? = null)

    private val perPackageClicks = HashMap<String, ArrayDeque<Long>>(8)
    private val recentFingerprints = HashMap<String, Long>(16)

    /**
     * @param pkg 目标应用包名
     * @param text 命中节点的文本（可能为 null，仅 viewId 命中时）
     * @param now 单调时钟（SystemClock.uptimeMillis）
     */
    @Synchronized
    fun check(pkg: String, text: String?, fingerprint: String, now: Long): Decision {
        if (pkg in SkipRules.SYSTEM_PACKAGES) return Decision(false, "system_package")
        if (SkipRules.isDangerous(text)) return Decision(false, "dangerous_text")

        // 同一节点 30 秒内只处理一次
        val last = recentFingerprints[fingerprint]
        if (last != null && now - last < FINGERPRINT_COOLDOWN_MS) {
            return Decision(false, "duplicate")
        }

        // 同一 App 10 秒内最多 3 次
        val clicks = perPackageClicks.getOrPut(pkg) { ArrayDeque(4) }
        while (clicks.isNotEmpty() && now - clicks.first() > RATE_WINDOW_MS) {
            clicks.removeFirst()
        }
        if (clicks.size >= MAX_CLICKS_PER_WINDOW) return Decision(false, "rate_limited")

        clicks.addLast(now)
        recentFingerprints[fingerprint] = now
        if (recentFingerprints.size > 128) {
            recentFingerprints.entries.removeAll { now - it.value > FINGERPRINT_COOLDOWN_MS }
        }
        return Decision(true, null)
    }

    companion object {
        private const val RATE_WINDOW_MS = 10_000L
        private const val MAX_CLICKS_PER_WINDOW = 3
        private const val FINGERPRINT_COOLDOWN_MS = 30_000L
    }
}