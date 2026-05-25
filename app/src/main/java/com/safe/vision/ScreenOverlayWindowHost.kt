package com.safe.vision

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
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
        private const val MAX_POOLED_REGION_SLOTS = 12
    }

    private data class RegionOverlaySlot(
        val view: ScreenMaskOverlayView,
        val layoutParams: WindowManager.LayoutParams,
        var label: String = "",
        val lastRegion: Rect = Rect(),
        var attached: Boolean = false,
        var inUse: Boolean = false
    )

    private var maskOverlayView: ScreenMaskOverlayView? = null
    private val maskRegionOverlaySlots = mutableListOf<RegionOverlaySlot>()
    private var activeFrameBitmap: android.graphics.Bitmap? = null

    fun resolveOverlayMetrics(): OverlayMetrics {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val displayMetrics = context.resources.displayMetrics
            return OverlayMetrics(
                widthPixels = displayMetrics.widthPixels,
                heightPixels = displayMetrics.heightPixels,
                densityDpi = displayMetrics.densityDpi,
                contentOffsetX = 0,
                contentOffsetY = 0
            )
        }

        val windowMetrics = windowManager.maximumWindowMetrics
        val bounds = windowMetrics.bounds
        val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val availableWidth = (bounds.width() - insets.left - insets.right).coerceAtLeast(1)
        val availableHeight = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        return OverlayMetrics(
            widthPixels = availableWidth,
            heightPixels = availableHeight,
            densityDpi = context.resources.displayMetrics.densityDpi,
            contentOffsetX = bounds.left + insets.left,
            contentOffsetY = bounds.top + insets.top
        )
    }

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
        maskOverlayView?.bindFrame(
            frame = frame,
            windowOriginX = metrics.contentOffsetX,
            windowOriginY = metrics.contentOffsetY
        )
        maskOverlayView?.visibility = View.VISIBLE
    }

    fun showRegionOverlays(
        frame: ScreenPrivacyMaskRenderer.OverlayFrame,
        metrics: OverlayMetrics
    ) {
        swapActiveFrameBitmap(frame.sourceBitmap)
        markAllRegionSlotsUnused()

        frame.drawTasks.forEach { task ->
            val safeRect = clampTaskRect(task.drawRect, frame.sourceBitmap.width, frame.sourceBitmap.height)
            if (safeRect.width() <= 0 || safeRect.height() <= 0) {
                return@forEach
            }

            val safeTask = if (safeRect == task.drawRect) task else task.copy(drawRect = safeRect)
            val slot = takeBestSlot(safeTask, safeRect, metrics) ?: obtainRegionSlot(metrics)
            attachRegionSlotIfNeeded(slot)
            slot.inUse = true
            slot.view.alpha = 0f
            slot.view.visibility = View.INVISIBLE
            updateRegionLayout(slot.layoutParams, safeRect, metrics)
            windowManager.updateViewLayout(slot.view, slot.layoutParams)
            slot.view.bindRegionTask(
                bitmap = frame.sourceBitmap,
                task = safeTask,
                windowOriginX = safeRect.left,
                windowOriginY = safeRect.top
            )
            slot.view.alpha = 1f
            slot.view.visibility = View.VISIBLE
            slot.label = safeTask.label
            slot.lastRegion.set(safeRect)
        }

        recycleUnusedRegionSlots()
    }

    fun clearMaskOverlays() {
        clearFullscreenOverlay()
        clearRegionOverlays()
        releaseActiveFrameBitmap()
    }

    fun clearRegionOverlays() {
        maskRegionOverlaySlots.forEach { slot ->
            slot.inUse = false
            if (slot.attached) {
                slot.view.visibility = View.INVISIBLE
                slot.view.release(false)
            }
        }
        trimRegionSlotPool(0)
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
            if (slot.attached) {
                slot.view.release(false)
                runCatching { windowManager.removeView(slot.view) }
            }
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
        targetRect: Rect,
        metrics: OverlayMetrics
    ): RegionOverlaySlot? {
        var bestSameLabel: RegionOverlaySlot? = null
        var bestSameLabelDistance = Long.MAX_VALUE
        var bestAny: RegionOverlaySlot? = null
        var bestAnyDistance = Long.MAX_VALUE

        maskRegionOverlaySlots.forEach { slot ->
            if (slot.inUse || !slot.attached || !canReuseSlot(slot.lastRegion, targetRect, metrics)) {
                return@forEach
            }
            val distance = movementDistanceSquared(slot.lastRegion, targetRect)
            if (slot.label == task.label && distance < bestSameLabelDistance) {
                bestSameLabel = slot
                bestSameLabelDistance = distance
            }
            if (distance < bestAnyDistance) {
                bestAny = slot
                bestAnyDistance = distance
            }
        }
        return bestSameLabel ?: bestAny
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

    private fun markAllRegionSlotsUnused() {
        maskRegionOverlaySlots.forEach { it.inUse = false }
    }

    private fun recycleUnusedRegionSlots() {
        maskRegionOverlaySlots.forEach { slot ->
            if (slot.inUse || !slot.attached) return@forEach
            slot.view.visibility = View.INVISIBLE
            slot.view.release(false)
        }
        trimRegionSlotPool(MAX_POOLED_REGION_SLOTS)
    }

    private fun trimRegionSlotPool(maxSlots: Int) {
        if (maskRegionOverlaySlots.size <= maxSlots) return
        val iterator = maskRegionOverlaySlots.iterator()
        while (maskRegionOverlaySlots.size > maxSlots && iterator.hasNext()) {
            val slot = iterator.next()
            if (slot.inUse || !slot.attached) continue
            runCatching { windowManager.removeView(slot.view) }
            slot.attached = false
            iterator.remove()
        }
    }

    private fun obtainRegionSlot(metrics: OverlayMetrics): RegionOverlaySlot {
        val slot = maskRegionOverlaySlots.firstOrNull { !it.inUse && !it.attached }
        if (slot != null) {
            return slot
        }
        return RegionOverlaySlot(
            view = createOverlayView().apply {
                visibility = View.INVISIBLE
            },
            layoutParams = createRegionMaskLayoutParams(Rect(0, 0, 1, 1), metrics)
        ).also { maskRegionOverlaySlots += it }
    }

    private fun attachRegionSlotIfNeeded(slot: RegionOverlaySlot) {
        if (slot.attached) return
        windowManager.addView(slot.view, slot.layoutParams)
        slot.attached = true
    }

    private fun clampTaskRect(region: Rect, width: Int, height: Int): Rect {
        val left = region.left.coerceAtLeast(0)
        val top = region.top.coerceAtLeast(0)
        val right = region.right.coerceAtMost(width)
        val bottom = region.bottom.coerceAtMost(height)
        return Rect(left, top, right, bottom)
    }

    private fun updateRegionLayout(
        layoutParams: WindowManager.LayoutParams,
        region: Rect,
        metrics: OverlayMetrics
    ) {
        layoutParams.width = region.width()
        layoutParams.height = region.height()
        layoutParams.x = region.left - metrics.contentOffsetX
        layoutParams.y = region.top - metrics.contentOffsetY
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
