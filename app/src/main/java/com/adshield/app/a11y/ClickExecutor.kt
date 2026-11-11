package com.adshield.app.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 点击执行：优先无障碍原生点击，失败再退化为手势点击。
 *
 * 不用 `GLOBAL_ACTION_BACK` 关广告——那会把用户直接弹出 App，体验极差且易误操作。
 */
class ClickExecutor(private val service: AccessibilityService) {

    enum class Method { CLICK, ANCESTOR_CLICK, GESTURE, NONE }

    fun performClick(node: AccessibilityNodeInfo): Method {
        val clicked = runCatching {
            node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (clicked) return Method.CLICK

        // 广告按钮常见结构：外层容器可点击，内层 TextView 不可点击
        var current = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            // 用 val 副本消除"可空 var 在 lambda 中被捕获"导致的智能转换失败
            val candidate = current
            val ok = runCatching {
                candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (ok) return Method.ANCESTOR_CLICK
            current = runCatching { candidate.parent }.getOrNull()
            depth++
        }

        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        if (rect.width() <= 0 || rect.height() <= 0) return Method.NONE

        val path = Path().apply {
            moveTo(rect.exactCenterX(), rect.exactCenterY())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()

        val dispatched = runCatching {
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
        return if (dispatched) Method.GESTURE else Method.NONE
    }

    companion object {
        private const val MAX_ANCESTOR_DEPTH = 3
    }
}