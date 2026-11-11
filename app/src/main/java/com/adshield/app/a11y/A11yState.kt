package com.adshield.app.a11y

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 无障碍服务的启用状态（以系统实际设置为准，不是我们自己记的开关）。 */
object A11yState {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun refresh(context: Context) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val services = manager?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        _enabled.value = services
            ?.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } == true
    }

    fun markEnabled(value: Boolean) {
        _enabled.value = value
    }
}