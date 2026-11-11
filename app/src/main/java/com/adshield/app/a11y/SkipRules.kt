package com.adshield.app.a11y

/**
 * 界面广告规则（M2 内置版）。
 *
 * 设计上的取舍：
 * - 全局规则只做"高确定性"的文案（跳过 / 跳过广告 / 关闭广告），不做模糊匹配；
 * - App 定向规则优先于全局规则；
 * - 危险词是**硬红线**：即使规则命中，只要节点文本含支付/开通/同意类字样，也绝不点击。
 *
 * 规则格式为后续 M3 的订阅版本预留（id / packageFilter / 三种选择器 / confidence）。
 */
data class SkipRule(
    val id: String,
    val packageFilter: String? = null,
    val textRegex: Regex? = null,
    val descRegex: Regex? = null,
    val viewIdRegex: Regex? = null,
    val confidence: Int = 80,
)

object SkipRules {

    /** 永不点击的文案（财产与合规红线）。 */
    private val DANGEROUS = Regex(
        "支付|付款|开通|购买|续费|充值|领取|同意|允许|下载|安装|授权|登录|注销|退出|删除|卸载|确认下单|立即购买"
    )

    /** 这些包的界面一律不处理。 */
    val SYSTEM_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.providers.settings",
        "com.android.incallui",
        "com.android.dialer",
    )

    /**
     * 内置规则，按"App 定向优先、置信度降序"排列。
     */
    val builtin: List<SkipRule> = listOf(
        SkipRule(
            id = "bili.splash.skip",
            packageFilter = "tv.danmaku.bili",
            // 兼容"跳过"、"跳过 5"、"5s 跳过"、"5 | 跳过"等文案
            textRegex = Regex("""^\s*(\d{1,2}\s*[s秒]?\s*[|｜]?\s*)?(跳过|关闭|关闭广告)\s*(\d{1,2}\s*[s秒]?)?\s*$"""),
            confidence = 95,
        ),
        SkipRule(
            id = "generic.skip.viewid",
            // 刻意不含裸 "skip"：音乐/视频播放器里的 skip_next（下一曲）会被误点
            viewIdRegex = Regex(""".*(ad_skip|splash_skip|countdown_skip|skip_ad).*""", RegexOption.IGNORE_CASE),
            confidence = 90,
        ),
        SkipRule(
            id = "generic.skip.text",
            // 只匹配"关闭广告"，不匹配裸"关闭"：避免点在正常弹窗/页面的关闭按钮上
            textRegex = Regex("""^\s*(\d{1,2}\s*[s秒]?\s*[|｜]?\s*)?(跳过|跳过广告|关闭广告)\s*(\d{1,2}\s*[s秒]?)?\s*$"""),
            confidence = 85,
        ),
        SkipRule(
            id = "generic.skip.desc",
            descRegex = Regex("""跳过(广告)?"""),
            confidence = 75,
        ),
    ).sortedWith(
        compareByDescending<SkipRule> { it.packageFilter != null }
            .thenByDescending { it.confidence }
    )

    fun isDangerous(text: String?): Boolean = text != null && DANGEROUS.containsMatchIn(text)

    fun matchesPackage(rule: SkipRule, pkg: String): Boolean =
        rule.packageFilter == null || rule.packageFilter == pkg
}