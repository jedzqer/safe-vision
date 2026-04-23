package com.safe.vision

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenOverlayController {
    @Volatile
    private var accessibilityHost: ScreenOverlayWindowHost? = null

    @Volatile
    private var systemAlertWindowHost: ScreenOverlayWindowHost? = null

    fun bindAccessibilityService(context: Context) {
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return
        accessibilityHost = ScreenOverlayWindowHost(
            context = context,
            windowManager = windowManager,
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            touchThrough = true
        )
    }

    fun unbindAccessibilityService() {
        accessibilityHost?.removeOverlayViews()
        accessibilityHost = null
    }

    fun isAccessibilityServiceConnected(): Boolean = accessibilityHost != null

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
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

    fun canDrawSystemAlertWindow(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun isOverlayPermissionGranted(context: Context, mode: ScreenOverlayMode): Boolean {
        return when (mode) {
            ScreenOverlayMode.ACCESSIBILITY -> isAccessibilityServiceEnabled(context)
            ScreenOverlayMode.SYSTEM_ALERT_WINDOW -> canDrawSystemAlertWindow(context)
        }
    }

    fun isOverlayReady(context: Context, mode: ScreenOverlayMode): Boolean {
        return when (mode) {
            ScreenOverlayMode.ACCESSIBILITY -> isAccessibilityServiceConnected()
            ScreenOverlayMode.SYSTEM_ALERT_WINDOW -> canDrawSystemAlertWindow(context)
        }
    }

    fun showFullscreenOverlay(
        context: Context,
        bitmap: Bitmap,
        metrics: OverlayMetrics,
        mode: ScreenOverlayMode
    ): Boolean {
        val host = hostFor(context, mode) ?: return false
        host.showFullscreenOverlay(bitmap, metrics)
        return true
    }

    fun showRegionOverlays(
        context: Context,
        bitmap: Bitmap,
        regions: List<ScreenPrivacyMaskRenderer.OverlayRegion>,
        metrics: OverlayMetrics,
        mode: ScreenOverlayMode
    ): Boolean {
        val host = hostFor(context, mode) ?: return false
        host.showRegionOverlays(bitmap, regions, metrics)
        return true
    }

    fun clearMaskOverlays(mode: ScreenOverlayMode) {
        hostForExisting(mode)?.clearMaskOverlays()
    }

    fun clearRegionOverlays(mode: ScreenOverlayMode) {
        hostForExisting(mode)?.clearRegionOverlays()
    }

    fun clearFullscreenOverlay(mode: ScreenOverlayMode) {
        hostForExisting(mode)?.clearFullscreenOverlay()
    }

    fun removeOverlayViews() {
        accessibilityHost?.removeOverlayViews()
        systemAlertWindowHost?.removeOverlayViews()
    }

    fun resolveOverlayMetrics(context: Context): OverlayMetrics {
        val displayMetrics = DisplayMetrics()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    private fun hostFor(context: Context, mode: ScreenOverlayMode): ScreenOverlayWindowHost? {
        return when (mode) {
            ScreenOverlayMode.ACCESSIBILITY -> accessibilityHost
            ScreenOverlayMode.SYSTEM_ALERT_WINDOW -> getOrCreateSystemAlertWindowHost(context)
        }
    }

    private fun hostForExisting(mode: ScreenOverlayMode): ScreenOverlayWindowHost? {
        return when (mode) {
            ScreenOverlayMode.ACCESSIBILITY -> accessibilityHost
            ScreenOverlayMode.SYSTEM_ALERT_WINDOW -> systemAlertWindowHost
        }
    }

    private fun getOrCreateSystemAlertWindowHost(context: Context): ScreenOverlayWindowHost? {
        systemAlertWindowHost?.let { return it }
        if (!canDrawSystemAlertWindow(context)) return null
        val appContext = context.applicationContext
        val windowManager = appContext.getSystemService(WindowManager::class.java) ?: return null
        return ScreenOverlayWindowHost(
            context = appContext,
            windowManager = windowManager,
            windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            touchThrough = false
        ).also { systemAlertWindowHost = it }
    }
}
