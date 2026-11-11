package com.adshield.app.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 有界视图树遍历。
 *
 * 约束（防止拖慢目标 App）：
 * - 最多 [maxNodes] 个节点；
 * - 最多 [maxDepth] 层；
 * - 不可见且无信息的容器子树直接剪枝。
 *
 * 命中优先级：App 定向规则 > 全局规则 > 置信度高的规则（规则列表已排好序）。
 */
class NodeTreeWalker(
    private val density: Float,
    private val maxNodes: Int = 220,
    private val maxDepth: Int = 14,
) {

    data class Match(
        val node: AccessibilityNodeInfo,
        val rule: SkipRule,
        val text: String?,
        val viewId: String?,
        val fingerprint: String,
    )

    fun find(root: AccessibilityNodeInfo?, pkg: String, rules: List<SkipRule>): Match? {
        if (root == null) return null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++

            if (isUsable(node)) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val viewId = node.viewIdResourceName

                if (text != null || desc != null || viewId != null) {
                    for (rule in rules) {
                        if (!SkipRules.matchesPackage(rule, pkg)) continue
                        if (!matchesRule(rule, text, desc, viewId)) continue
                        if (!isLargeEnough(node)) continue
                        if (SkipRules.isDangerous(text) || SkipRules.isDangerous(desc)) continue
                        return Match(
                            node = node,
                            rule = rule,
                            text = text ?: desc,
                            viewId = viewId,
                            fingerprint = buildFingerprint(pkg, text ?: desc, viewId, node),
                        )
                    }
                }
            }

            if (depth < maxDepth && shouldDescend(node)) {
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return null
    }

    private fun isUsable(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isVisibleToUser && node.isEnabled }.getOrDefault(false)

    /** 无文本、无描述、无 id 且不可点击的容器，多半只是布局壳子；但仍可能是父容器，需要下探。 */
    private fun shouldDescend(node: AccessibilityNodeInfo): Boolean = true

    private fun matchesRule(rule: SkipRule, text: String?, desc: String?, viewId: String?): Boolean {
        rule.viewIdRegex?.let { regex ->
            if (viewId != null && regex.matches(viewId)) return true
        }
        rule.textRegex?.let { regex ->
            if (text != null && regex.matches(text)) return true
        }
        rule.descRegex?.let { regex ->
            if (desc != null && regex.containsMatchIn(desc)) return true
            if (text != null && regex.containsMatchIn(text)) return true
        }
        return false
    }

    private fun isLargeEnough(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        return runCatching {
            node.getBoundsInScreen(rect)
            rect.width() >= (MIN_SIZE_DP * density).toInt() &&
                rect.height() >= (MIN_SIZE_DP * density).toInt()
        }.getOrDefault(false)
    }

    private fun buildFingerprint(
        pkg: String,
        text: String?,
        viewId: String?,
        node: AccessibilityNodeInfo,
    ): String {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return "$pkg|${text ?: ""}|${viewId ?: ""}|${rect.left},${rect.top}"
    }

    companion object {
        private const val MIN_SIZE_DP = 16
    }
}