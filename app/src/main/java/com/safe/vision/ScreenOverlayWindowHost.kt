package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

internal class ScreenOverlayWindowHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val touchThrough: Boolean
) {
    private var maskOverlayView: ScreenMaskOverlayView? = null
    private val maskRegionOverlayViews = mutableListOf<ScreenMaskOverlayView>()

    fun showFullscreenOverlay(bitmap: Bitmap, metrics: OverlayMetrics) {
        if (maskOverlayView == null) {
            maskOverlayView = createOverlayView()
            windowManager.addView(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
        } else {
            windowManager.updateViewLayout(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
        }
        maskOverlayView?.setContentOffset(metrics.contentOffsetX, metrics.contentOffsetY)
        maskOverlayView?.setOverlayBitmap(bitmap)
        maskOverlayView?.visibility = View.VISIBLE
    }

    fun showRegionOverlays(bitmap: Bitmap, regions: List<Rect>, metrics: OverlayMetrics) {
        val safeRegions = regions.mapNotNull { region ->
            val safe = BlurEffects.clampRect(region, bitmap.width, bitmap.height)
            if (safe.width() > 0 && safe.height() > 0) safe else null
        }

        while (maskRegionOverlayViews.size < safeRegions.size) {
            val view = createOverlayView().apply {
                visibility = View.INVISIBLE
            }
            maskRegionOverlayViews += view
            windowManager.addView(view, createRegionMaskLayoutParams(Rect(0, 0, 1, 1), metrics))
        }

        while (maskRegionOverlayViews.size > safeRegions.size) {
            val view = maskRegionOverlayViews.removeLast()
            runCatching { windowManager.removeView(view) }
            view.release()
        }

        safeRegions.forEachIndexed { index, region ->
            val view = maskRegionOverlayViews[index]
            val cropped = Bitmap.createBitmap(
                bitmap,
                region.left,
                region.top,
                region.width(),
                region.height()
            )
            view.visibility = View.INVISIBLE
            windowManager.updateViewLayout(view, createRegionMaskLayoutParams(region, metrics))
            view.setContentOffset(0, 0)
            view.setOverlayBitmap(cropped)
            view.visibility = View.VISIBLE
        }
        bitmap.recycle()
    }

    fun clearMaskOverlays() {
        clearFullscreenOverlay()
        clearRegionOverlays()
    }

    fun clearRegionOverlays() {
        maskRegionOverlayViews.forEach { view ->
            view.release()
            view.visibility = View.INVISIBLE
        }
    }

    fun clearFullscreenOverlay() {
        maskOverlayView?.release()
        maskOverlayView?.visibility = View.INVISIBLE
    }

    fun removeOverlayViews() {
        maskOverlayView?.let { view ->
            view.release()
            runCatching { windowManager.removeView(view) }
        }
        maskRegionOverlayViews.forEach { view ->
            view.release()
            runCatching { windowManager.removeView(view) }
        }
        maskOverlayView = null
        maskRegionOverlayViews.clear()
    }

    private fun createOverlayView(): ScreenMaskOverlayView {
        return ScreenMaskOverlayView(context).apply {
            isClickable = !touchThrough
            isFocusable = false
            isFocusableInTouchMode = false
            if (!touchThrough) {
                setOnTouchListener { _, _ -> true }
            }
        }
    }

    private fun createFullscreenMaskLayoutParams(metrics: OverlayMetrics): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            windowType,
            baseWindowFlags(),
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
            windowType,
            baseWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = region.left - metrics.contentOffsetX
            y = region.top - metrics.contentOffsetY
            alpha = 1f
        }
    }

    private fun baseWindowFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (touchThrough) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }
}
