package com.safe.vision

import android.accessibilityservice.AccessibilityService

class ScreenAccessibilityOverlayService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenOverlayController.bindAccessibilityService(this)
        DebugLogManager.addLog("屏幕检测", "无障碍遮挡服务已连接")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

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
