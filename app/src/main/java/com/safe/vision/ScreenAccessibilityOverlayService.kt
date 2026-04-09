package com.safe.vision

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class ScreenAccessibilityOverlayService : AccessibilityService() {
    companion object {
        @Volatile
        private var instance: ScreenAccessibilityOverlayService? = null

        fun isConnected(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (enabledServices.isEmpty()) return false
            val expected = ComponentName(
                context,
                ScreenAccessibilityOverlayService::class.java
            ).flattenToString()
            return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun showFullscreenOverlay(bitmap: android.graphics.Bitmap, metrics: OverlayMetrics) {
            instance?.showFullscreenOverlayInternal(bitmap, metrics)
        }

        fun showRegionOverlays(bitmap: android.graphics.Bitmap, regions: List<Rect>, metrics: OverlayMetrics) {
            instance?.showRegionOverlaysInternal(bitmap, regions, metrics)
        }

        fun clearMaskOverlays() {
            instance?.clearMaskOverlaysInternal()
        }

        fun clearRegionOverlays() {
            instance?.clearRegionOverlaysInternal()
        }

        fun clearFullscreenOverlay() {
            instance?.clearFullscreenOverlayInternal()
        }

        fun removeOverlayViews() {
            instance?.removeOverlayViewsInternal()
        }

        fun resolveOverlayMetrics(context: Context): OverlayMetrics {
            val displayMetrics = DisplayMetrics()
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val windowMetrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
                val bounds = windowMetrics.bounds
                OverlayMetrics(
                    widthPixels = bounds.width(),
                    heightPixels = bounds.height(),
                    densityDpi = context.resources.displayMetrics.densityDpi,
                    contentOffsetX = 0,
                    contentOffsetY = 0
                )
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(WindowManager::class.java).defaultDisplay?.getRealMetrics(displayMetrics)
                OverlayMetrics(
                    widthPixels = displayMetrics.widthPixels,
                    heightPixels = displayMetrics.heightPixels,
                    densityDpi = displayMetrics.densityDpi,
                    contentOffsetX = 0,
                    contentOffsetY = 0
                )
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var maskOverlayView: ScreenMaskOverlayView? = null
    private val maskRegionOverlayViews = mutableListOf<ScreenMaskOverlayView>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
        DebugLogManager.addLog("屏幕检测", "无障碍遮挡服务已连接")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeOverlayViewsInternal()
        instance = null
        super.onDestroy()
    }

    private fun showFullscreenOverlayInternal(bitmap: android.graphics.Bitmap, metrics: OverlayMetrics) {
        val manager = windowManager ?: return
        if (maskOverlayView == null) {
            maskOverlayView = ScreenMaskOverlayView(this)
            manager.addView(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
        } else {
            manager.updateViewLayout(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
        }
        maskOverlayView?.setContentOffset(metrics.contentOffsetX, metrics.contentOffsetY)
        maskOverlayView?.setOverlayBitmap(bitmap)
        maskOverlayView?.visibility = View.VISIBLE
    }

    private fun showRegionOverlaysInternal(bitmap: android.graphics.Bitmap, regions: List<Rect>, metrics: OverlayMetrics) {
        val manager = windowManager ?: return
        val safeRegions = regions.mapNotNull { region ->
            val safe = BlurEffects.clampRect(region, bitmap.width, bitmap.height)
            if (safe.width() > 0 && safe.height() > 0) safe else null
        }

        while (maskRegionOverlayViews.size < safeRegions.size) {
            val view = ScreenMaskOverlayView(this)
            view.visibility = View.INVISIBLE
            maskRegionOverlayViews += view
            manager.addView(view, createRegionMaskLayoutParams(Rect(0, 0, 1, 1), metrics))
        }

        while (maskRegionOverlayViews.size > safeRegions.size) {
            val view = maskRegionOverlayViews.removeLast()
            runCatching { manager.removeView(view) }
            view.release()
        }

        safeRegions.forEachIndexed { index, region ->
            val view = maskRegionOverlayViews[index]
            val cropped = android.graphics.Bitmap.createBitmap(
                bitmap,
                region.left,
                region.top,
                region.width(),
                region.height()
            )
            view.visibility = View.INVISIBLE
            manager.updateViewLayout(view, createRegionMaskLayoutParams(region, metrics))
            view.setContentOffset(0, 0)
            view.setOverlayBitmap(cropped)
            view.visibility = View.VISIBLE
        }
        bitmap.recycle()
    }

    private fun clearMaskOverlaysInternal() {
        clearFullscreenOverlayInternal()
        clearRegionOverlaysInternal()
    }

    private fun clearFullscreenOverlayInternal() {
        maskOverlayView?.release()
        maskOverlayView?.visibility = View.INVISIBLE
    }

    private fun clearRegionOverlaysInternal() {
        maskRegionOverlayViews.forEach { view ->
            view.release()
            view.visibility = View.INVISIBLE
        }
    }

    private fun removeOverlayViewsInternal() {
        val manager = windowManager
        maskOverlayView?.let { view ->
            view.release()
            runCatching { manager?.removeView(view) }
        }
        maskRegionOverlayViews.forEach { view ->
            view.release()
            runCatching { manager?.removeView(view) }
        }
        maskOverlayView = null
        maskRegionOverlayViews.clear()
    }

    private fun createFullscreenMaskLayoutParams(metrics: OverlayMetrics): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
        }
    }

    private fun createRegionMaskLayoutParams(
        region: Rect,
        metrics: OverlayMetrics
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            region.width(),
            region.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = region.left - metrics.contentOffsetX
            y = region.top - metrics.contentOffsetY
            alpha = 1f
        }
    }
}

data class OverlayMetrics(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    val contentOffsetX: Int,
    val contentOffsetY: Int
)
