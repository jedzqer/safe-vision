package com.safe.vision

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenAccessibilityOverlayService : AccessibilityService() {
    companion object {
        private val _foregroundAppPackage = MutableStateFlow<String?>(null)
        val foregroundAppPackage: StateFlow<String?> = _foregroundAppPackage.asStateFlow()

        private val _appSwitchEventCount = MutableStateFlow(0)
        val appSwitchEventCount: StateFlow<Int> = _appSwitchEventCount.asStateFlow()
    }

    private var lastReportedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenOverlayController.bindAccessibilityService(this)
        DebugLogManager.addLog("屏幕检测", "无障碍遮挡服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // 窗口列表改变，可能是浮窗出现/消失，暂不处理
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (packageName != null && packageName != lastReportedPackage) {
            lastReportedPackage = packageName
            _foregroundAppPackage.value = packageName
            _appSwitchEventCount.value = _appSwitchEventCount.value + 1

            DebugLogManager.addLog(
                "屏幕检测",
                "应用切换: $packageName (className: ${event.className})"
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ScreenOverlayController.unbindAccessibilityService()
        super.onDestroy()
    }
}

data class OverlayMetrics(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    val contentOffsetX: Int,
    val contentOffsetY: Int
)
