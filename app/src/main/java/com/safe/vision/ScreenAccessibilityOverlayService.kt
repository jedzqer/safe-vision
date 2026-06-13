package com.safe.vision

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ScreenAccessibilityOverlayService : AccessibilityService() {
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
        if (packageName != null) {
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
