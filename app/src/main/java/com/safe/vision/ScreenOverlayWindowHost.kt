package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

internal class ScreenOverlayWindowHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val touchThrough: Boolean
) {
    private data class RegionOverlaySlot(
        val view: ScreenMaskOverlayView,
        var label: String,
        var lastRegion: Rect
    )

    private var maskOverlayView: ScreenMaskOverlayView? = null
    private val maskRegionOverlaySlots = mutableListOf<RegionOverlaySlot>()

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

    fun showRegionOverlays(
        bitmap: Bitmap,
        regions: List<ScreenPrivacyMaskRenderer.OverlayRegion>,
        metrics: OverlayMetrics
    ) {
        val safeRegions = regions.mapNotNull { region ->
            val safe = BlurEffects.clampRect(region.rect, bitmap.width, bitmap.height)
            if (safe.width() > 0 && safe.height() > 0) {
                ScreenPrivacyMaskRenderer.OverlayRegion(region.label, safe)
            } else {
                null
            }
        }

        val reusableSlots = maskRegionOverlaySlots.toMutableList()
        val nextSlots = ArrayList<RegionOverlaySlot>(safeRegions.size)

        safeRegions.forEach { region ->
            val slot = takeBestSlot(region, reusableSlots, metrics)
            val view = slot.view
            val cropped = Bitmap.createBitmap(
                bitmap,
                region.rect.left,
                region.rect.top,
                region.rect.width(),
                region.rect.height()
            )
            view.visibility = View.INVISIBLE
            windowManager.updateViewLayout(view, createRegionMaskLayoutParams(region.rect, metrics))
            view.setContentOffset(0, 0)
            view.setOverlayBitmap(cropped)
            view.visibility = View.VISIBLE
            slot.label = region.label
            slot.lastRegion = Rect(region.rect)
            nextSlots += slot
        }

        reusableSlots.forEach { slot ->
            runCatching { windowManager.removeView(slot.view) }
            slot.view.release()
        }
        maskRegionOverlaySlots.clear()
        maskRegionOverlaySlots += nextSlots
        bitmap.recycle()
    }

    fun clearMaskOverlays() {
        clearFullscreenOverlay()
        clearRegionOverlays()
    }

    fun clearRegionOverlays() {
        maskRegionOverlaySlots.forEach { slot ->
            slot.view.release()
            slot.view.visibility = View.INVISIBLE
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
        maskRegionOverlaySlots.forEach { slot ->
            slot.view.release()
            runCatching { windowManager.removeView(slot.view) }
        }
        maskOverlayView = null
        maskRegionOverlaySlots.clear()
    }

    private fun takeBestSlot(
        region: ScreenPrivacyMaskRenderer.OverlayRegion,
        reusableSlots: MutableList<RegionOverlaySlot>,
        metrics: OverlayMetrics
    ): RegionOverlaySlot {
        val sameLabelIndex = reusableSlots
            .withIndex()
            .filter { it.value.label == region.label }
            .minByOrNull { movementDistanceSquared(it.value.lastRegion, region.rect) }
            ?.index

        val slotIndex = sameLabelIndex ?: reusableSlots
            .withIndex()
            .minByOrNull { movementDistanceSquared(it.value.lastRegion, region.rect) }
            ?.index

        if (slotIndex != null) {
            return reusableSlots.removeAt(slotIndex)
        }

        val view = createOverlayView().apply {
            visibility = View.INVISIBLE
        }
        windowManager.addView(view, createRegionMaskLayoutParams(Rect(0, 0, 1, 1), metrics))
        return RegionOverlaySlot(view, region.label, Rect(region.rect))
    }

    private fun movementDistanceSquared(previous: Rect, current: Rect): Long {
        val dx = previous.centerX().toLong() - current.centerX().toLong()
        val dy = previous.centerY().toLong() - current.centerY().toLong()
        val dw = previous.width().toLong() - current.width().toLong()
        val dh = previous.height().toLong() - current.height().toLong()
        return dx * dx + dy * dy + abs(dw) * abs(dw) / 4L + abs(dh) * abs(dh) / 4L
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
