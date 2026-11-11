package com.adshield.app.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.adshield.app.AdShieldApp
import com.adshield.app.AppContainer
import com.adshield.app.data.PopupRepository
import com.adshield.app.data.StatsRepository
import com.adshield.app.data.db.PopupEventEntity

/**
 * 界面侧引擎：识别开屏 / 弹窗广告中的"跳过 / 关闭"控件并自动点击。
 *
 * 关键约束：
 * 1. **只在目标 App 进入前台后的 8 秒内动作**——避开用户日常操作中的"关闭""取消"按钮；
 * 2. 事件去抖 250ms + 遍历上限 220 节点，避免拖慢目标 App；
 * 3. 所有点击都过 [SafetyGuard]（危险词、频率、指纹去重）；
 * 4. 命中但被安全阀拦下的，也要落一条 `SAFETY_BLOCKED` 记录，便于事后解释"为什么没点"。
 */
class AdShieldA11yService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val guard = SafetyGuard()

    private lateinit var walker: NodeTreeWalker
    private lateinit var clickExecutor: ClickExecutor
    private lateinit var container: AppContainer

    private var frontPackage: String = ""
    private var frontSince: Long = 0L
    private var lastAttemptAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        walker = NodeTreeWalker(resources.displayMetrics.density)
        clickExecutor = ClickExecutor(this)
        container = (application as AdShieldApp).container
        A11yState.markEnabled(true)
        Log.i(TAG, "a11y service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::walker.isInitialized) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in SkipRules.SYSTEM_PACKAGES) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != frontPackage) {
            frontPackage = pkg
            frontSince = now
        }
        if (pkg != frontPackage) return
        if (now - frontSince > FRONT_WINDOW_MS) return
        if (now - lastAttemptAt < DEBOUNCE_MS) return
        lastAttemptAt = now

        handler.postDelayed({ attempt(pkg) }, SETTLE_DELAY_MS)
    }

    private fun attempt(pkg: String) {
        if (!A11yState.enabled.value) return

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        if (root.packageName?.toString() != pkg) return

        val match = runCatching { walker.find(root, pkg, SkipRules.builtin) }.getOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        val decision = guard.check(pkg, match.text, match.fingerprint, now)

        if (!decision.allowed) {
            record(
                pkg = pkg,
                ruleId = match.rule.id,
                text = match.text,
                outcome = PopupRepository.OUTCOME_SAFETY_BLOCKED,
                method = "NONE",
            )
            Log.i(TAG, "blocked by safety: ${decision.reason} pkg=$pkg")
            return
        }

        val method = clickExecutor.performClick(match.node)
        record(
            pkg = pkg,
            ruleId = match.rule.id,
            text = match.text,
            outcome = if (method == ClickExecutor.Method.NONE) {
                PopupRepository.OUTCOME_FAILED
            } else {
                PopupRepository.OUTCOME_SKIPPED
            },
            method = method.name,
        )
        Log.i(TAG, "click pkg=$pkg rule=${match.rule.id} text=${match.text} method=$method")
    }

    private fun record(
        pkg: String,
        ruleId: String,
        text: String?,
        outcome: String,
        method: String,
    ) {
        val now = System.currentTimeMillis()
        runCatching {
            container.popups.record(
                PopupEventEntity(
                    ts = now,
                    day = StatsRepository.dayOf(now),
                    pkg = pkg,
                    activity = null,
                    ruleId = ruleId,
                    nodeText = text?.take(64),
                    outcome = outcome,
                    method = method,
                )
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        A11yState.markEnabled(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yState.markEnabled(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdShieldA11y"
        private const val FRONT_WINDOW_MS = 8_000L
        private const val DEBOUNCE_MS = 250L
        private const val SETTLE_DELAY_MS = 120L
    }
}