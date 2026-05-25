package com.safe.vision

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

internal class ScreenOverlayWindowHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val touchThrough: Boolean
) {
    companion object {
        private const val MIN_SLOT_REUSE_DISTANCE_DP = 72f
        private const val SLOT_REUSE_DISTANCE_MULTIPLIER = 1.5f
    }

    private data class RegionOverlaySlot(
        val view: ScreenMaskOverlayView,
        var label: String,
        var lastRegion: Rect
    )

    private var maskOverlayView: ScreenMaskOverlayView? = null
    private val maskRegionOverlaySlots = mutableListOf<RegionOverlaySlot>()
    private var activeFrameBitmap: android.graphics.Bitmap? = null

    fun showFullscreenOverlay(
        frame: ScreenPrivacyMaskRenderer.OverlayFrame,
        metrics: OverlayMetrics
    ) {
        swapActiveFrameBitmap(frame.sourceBitmap)
        if (maskOverlayView == null) {
            maskOverlayView = createOverlayView()
            windowManager.addView(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
        } else {
            windowManager.updateViewLayout(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
        }
        maskOverlayView?.setRegionBounds(0, 0)
        maskOverlayView?.setContentOffset(metrics.contentOffsetX, metrics.contentOffsetY)
        maskOverlayView?.setFrame(frame)
        maskOverlayView?.visibility = View.VISIBLE
    }

    fun showRegionOverlays(
        frame: ScreenPrivacyMaskRenderer.OverlayFrame,
        metrics: OverlayMetrics
    ) {
        swapActiveFrameBitmap(frame.sourceBitmap)
        val safeTasks = frame.drawTasks.mapNotNull { task ->
            val safe = BlurEffects.clampRect(task.drawRect, frame.sourceBitmap.width, frame.sourceBitmap.height)
            if (safe.width() > 0 && safe.height() > 0) {
                task.copy(drawRect = Rect(safe))
            } else {
                null
            }
        }

        val reusableSlots = maskRegionOverlaySlots.toMutableList()
        val nextSlots = ArrayList<RegionOverlaySlot>(safeTasks.size)

        safeTasks.forEach { task ->
            val slot = takeBestSlot(task, reusableSlots, metrics)
            val view = slot.view
            view.release()
            view.alpha = 0f
            view.visibility = View.INVISIBLE
            windowManager.updateViewLayout(view, createRegionMaskLayoutParams(task.drawRect, metrics))
            view.setRegionBounds(task.drawRect.left, task.drawRect.top)
            view.setContentOffset(metrics.contentOffsetX, metrics.contentOffsetY)
            view.setFrame(
                ScreenPrivacyMaskRenderer.OverlayFrame(
                    sourceBitmap = frame.sourceBitmap,
                    drawTasks = listOf(task),
                    reverseMode = null,
                    reverseRegions = emptyList(),
                    reversePreRender = false
                )
            )
            view.alpha = 1f
            view.visibility = View.VISIBLE
            slot.label = task.label
            slot.lastRegion = Rect(task.drawRect)
            nextSlots += slot
        }

        reusableSlots.forEach { slot ->
            runCatching { windowManager.removeView(slot.view) }
            slot.view.release()
        }
        maskRegionOverlaySlots.clear()
        maskRegionOverlaySlots += nextSlots
    }

    fun clearMaskOverlays() {
        clearFullscreenOverlay()
        clearRegionOverlays()
        releaseActiveFrameBitmap()
    }

    fun clearRegionOverlays() {
        maskRegionOverlaySlots.forEach { slot ->
            slot.view.release()
            runCatching { windowManager.removeView(slot.view) }
        }
        maskRegionOverlaySlots.clear()
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
        releaseActiveFrameBitmap()
    }

    private fun swapActiveFrameBitmap(bitmap: android.graphics.Bitmap) {
        if (activeFrameBitmap === bitmap) return
        releaseActiveFrameBitmap()
        activeFrameBitmap = bitmap
    }

    private fun releaseActiveFrameBitmap() {
        activeFrameBitmap?.takeIf { !it.isRecycled }?.recycle()
        activeFrameBitmap = null
    }

    private fun takeBestSlot(
        task: ScreenPrivacyMaskRenderer.DrawTask,
        reusableSlots: MutableList<RegionOverlaySlot>,
        metrics: OverlayMetrics
    ): RegionOverlaySlot {
        val sameLabelIndex = reusableSlots
            .withIndex()
            .filter {
                it.value.label == task.label &&
                    canReuseSlot(it.value.lastRegion, task.drawRect, metrics)
            }
            .minByOrNull { movementDistanceSquared(it.value.lastRegion, task.drawRect) }
            ?.index

        val slotIndex = sameLabelIndex ?: reusableSlots
            .withIndex()
            .filter { canReuseSlot(it.value.lastRegion, task.drawRect, metrics) }
            .minByOrNull { movementDistanceSquared(it.value.lastRegion, task.drawRect) }
            ?.index

        if (slotIndex != null) {
            return reusableSlots.removeAt(slotIndex)
        }

        val view = createOverlayView().apply {
            visibility = View.INVISIBLE
        }
        windowManager.addView(view, createRegionMaskLayoutParams(Rect(0, 0, 1, 1), metrics))
        return RegionOverlaySlot(view, task.label, Rect(task.drawRect))
    }

    private fun canReuseSlot(previous: Rect, current: Rect, metrics: OverlayMetrics): Boolean {
        val minReuseDistancePx = MIN_SLOT_REUSE_DISTANCE_DP * metrics.densityDpi / 160f
        val sizeBasedReuseDistancePx = max(
            max(previous.width(), previous.height()),
            max(current.width(), current.height())
        ) * SLOT_REUSE_DISTANCE_MULTIPLIER
        val maxReuseDistancePx = max(minReuseDistancePx, sizeBasedReuseDistancePx)
        return movementDistanceSquared(previous, current) <=
            maxReuseDistancePx.toLong() * maxReuseDistancePx.toLong()
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
