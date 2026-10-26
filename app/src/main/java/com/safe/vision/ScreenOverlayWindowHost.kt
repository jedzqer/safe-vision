package com.safe.vision

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.doOnPreDraw
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ScreenOverlayWindowHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val touchThrough: Boolean
) {
    companion object {
        private const val MAX_IDLE_REGION_SLOTS = 4
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
    private var fullscreenMetrics: OverlayMetrics? = null
    private val maskRegionOverlaySlots = mutableListOf<RegionOverlaySlot>()
    private var activeOverlayFrame: ScreenPrivacyMaskRenderer.OverlayFrame? = null
    private var renderGeneration: Long = 0L

    // 采集面被降分辨率后，遮挡坐标处于采集像素空间，叠加到屏幕时按此比例放大还原。
    private var captureScaleX: Float = 1f
    private var captureScaleY: Float = 1f

    private fun updateCaptureScale(metrics: OverlayMetrics, frame: ScreenPrivacyMaskRenderer.OverlayFrame) {
        captureScaleX = metrics.widthPixels.toFloat() / frame.captureWidth
        captureScaleY = metrics.heightPixels.toFloat() / frame.captureHeight
    }

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
        if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            return OverlayMetrics(
                widthPixels = bounds.width().coerceAtLeast(1),
                heightPixels = bounds.height().coerceAtLeast(1),
                densityDpi = context.resources.displayMetrics.densityDpi,
                contentOffsetX = bounds.left,
                contentOffsetY = bounds.top
            )
        }

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
        val generation = ++renderGeneration
        val previousFrame = replaceActiveOverlayFrame(frame)
        updateCaptureScale(metrics, frame)
        val layoutChanged = maskOverlayView == null || fullscreenMetrics != metrics
        if (maskOverlayView == null) {
            maskOverlayView = createOverlayView()
            try {
                windowManager.addView(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
                fullscreenMetrics = metrics
            } catch (e: WindowManager.BadTokenException) {
                DebugLogManager.addLog("屏幕遮挡", "addView 全屏遮挡失败: ${e.message}", DebugLogManager.LogLevel.WARN)
                maskOverlayView = null
                fullscreenMetrics = null
                activeOverlayFrame = previousFrame
                if (previousFrame !== frame) {
                    frame.release()
                }
                return
            }
        } else if (fullscreenMetrics != metrics) {
            try {
                windowManager.updateViewLayout(maskOverlayView, createFullscreenMaskLayoutParams(metrics))
                fullscreenMetrics = metrics
            } catch (e: WindowManager.BadTokenException) {
                DebugLogManager.addLog("屏幕遮挡", "updateViewLayout 全屏遮挡失败: ${e.message}", DebugLogManager.LogLevel.WARN)
                runCatching { windowManager.removeView(maskOverlayView) }
                maskOverlayView = null
                fullscreenMetrics = null
                activeOverlayFrame = previousFrame
                if (previousFrame !== frame) {
                    frame.release()
                }
                return
            }
        }
        maskOverlayView?.let { view ->
            view.translationX = 0f
            view.translationY = 0f
            bindFullscreenFrame(view, frame, metrics, generation, layoutChanged)
            view.visibility = View.VISIBLE
        }
        releaseOverlayFrame(previousFrame, frame)
    }

    fun showRegionOverlays(
        frame: ScreenPrivacyMaskRenderer.OverlayFrame,
        metrics: OverlayMetrics
    ) {
        val generation = ++renderGeneration
        val previousFrame = replaceActiveOverlayFrame(frame)
        updateCaptureScale(metrics, frame)
        markAllRegionSlotsUnused()

        frame.drawTasks.forEach { task ->
            val safeRect = clampTaskRect(task.drawRect, frame.captureWidth, frame.captureHeight)
            if (safeRect.width() <= 0 || safeRect.height() <= 0) {
                return@forEach
            }

            val safeTask = if (safeRect == task.drawRect) task else task.copy(drawRect = safeRect)
            val slot = takeBestSlot(safeTask, safeRect) ?: obtainRegionSlot(metrics)
            val wasAttached = slot.attached
            val layoutChanged = updateRegionLayout(slot.layoutParams, safeRect, metrics)
            // Attach directly at the target bounds; avoid a 1x1 add followed by a relayout.
            attachRegionSlotIfNeeded(slot)
            if (!slot.attached) {
                return@forEach
            }
            slot.inUse = true
            slot.view.translationX = 0f
            slot.view.translationY = 0f
            if (wasAttached && layoutChanged) {
                try {
                    windowManager.updateViewLayout(slot.view, slot.layoutParams)
                } catch (e: WindowManager.BadTokenException) {
                    DebugLogManager.addLog("屏幕遮挡", "updateViewLayout 区域遮挡失败: ${e.message}", DebugLogManager.LogLevel.WARN)
                    runCatching { windowManager.removeView(slot.view) }
                    slot.attached = false
                    slot.inUse = false
                    return@forEach
                }
            }
            bindRegionTask(slot.view, safeTask, safeRect, generation, layoutChanged || !wasAttached)
            slot.view.visibility = View.VISIBLE
            slot.label = safeTask.label
            slot.lastRegion.set(safeRect)
        }

        recycleUnusedRegionSlots()
        releaseOverlayFrame(previousFrame, frame)
    }

    fun clearMaskOverlays() {
        renderGeneration++
        clearFullscreenOverlay()
        clearRegionOverlays()
        releaseActiveOverlayFrame()
    }

    fun applyMotionShift(dxPx: Float, dyPx: Float) {
        maskOverlayView?.let { view ->
            if (view.isAttachedToWindow) {
                view.translationX = dxPx
                view.translationY = dyPx
            }
        }
        maskRegionOverlaySlots.forEach { slot ->
            if (slot.attached) {
                slot.view.translationX = dxPx
                slot.view.translationY = dyPx
            }
        }
    }

    fun resetMotionShift() {
        maskOverlayView?.translationX = 0f
        maskOverlayView?.translationY = 0f
        maskRegionOverlaySlots.forEach { slot ->
            slot.view.translationX = 0f
            slot.view.translationY = 0f
        }
    }

    fun clearRegionOverlays() {
        if (maskRegionOverlaySlots.none { it.inUse }) return
        renderGeneration++
        markAllRegionSlotsUnused()
        recycleUnusedRegionSlots()
    }

    fun clearFullscreenOverlay() {
        if (maskOverlayView?.visibility != View.VISIBLE) return
        renderGeneration++
        maskOverlayView?.release()
        maskOverlayView?.visibility = View.INVISIBLE
    }

    fun removeOverlayViews() {
        renderGeneration++
        maskOverlayView?.let { view ->
            view.release()
            runCatching { windowManager.removeView(view) }
        }
        removeAllRegionSlots()
        maskOverlayView = null
        fullscreenMetrics = null
        releaseActiveOverlayFrame()
    }

    private fun replaceActiveOverlayFrame(
        frame: ScreenPrivacyMaskRenderer.OverlayFrame
    ): ScreenPrivacyMaskRenderer.OverlayFrame? {
        if (activeOverlayFrame === frame) return null
        val previousFrame = activeOverlayFrame
        activeOverlayFrame = frame
        return previousFrame
    }

    private fun releaseOverlayFrame(
        previousFrame: ScreenPrivacyMaskRenderer.OverlayFrame?,
        currentFrame: ScreenPrivacyMaskRenderer.OverlayFrame
    ) {
        if (previousFrame == null || previousFrame === currentFrame) return
        previousFrame.release()
    }

    private fun releaseActiveOverlayFrame() {
        activeOverlayFrame?.release()
        activeOverlayFrame = null
    }

    private fun takeBestSlot(
        task: ScreenPrivacyMaskRenderer.DrawTask,
        targetRect: Rect
    ): RegionOverlaySlot? {
        var bestSameLabel: RegionOverlaySlot? = null
        var bestSameLabelDistance = Long.MAX_VALUE
        var bestAny: RegionOverlaySlot? = null
        var bestAnyDistance = Long.MAX_VALUE

        maskRegionOverlaySlots.forEach { slot ->
            if (slot.inUse || !slot.attached) {
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
        var idleCount = 0
        val iterator = maskRegionOverlaySlots.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            if (slot.inUse) continue
            slot.view.visibility = View.INVISIBLE // Hidden windows must not consume touches.
            slot.view.release(false)
            if (!slot.attached || ++idleCount > MAX_IDLE_REGION_SLOTS) {
                removeRegionSlot(slot)
                iterator.remove()
            }
        }
    }

    private fun obtainRegionSlot(metrics: OverlayMetrics): RegionOverlaySlot {
        return RegionOverlaySlot(
            view = createOverlayView().apply {
                visibility = View.INVISIBLE
            },
            layoutParams = createRegionMaskLayoutParams(Rect(0, 0, 1, 1), metrics)
        ).also { maskRegionOverlaySlots += it }
    }

    private fun attachRegionSlotIfNeeded(slot: RegionOverlaySlot) {
        if (slot.attached) return
        try {
            windowManager.addView(slot.view, slot.layoutParams)
            slot.attached = true
        } catch (e: WindowManager.BadTokenException) {
            DebugLogManager.addLog("屏幕遮挡", "addView 区域遮挡失败: ${e.message}", DebugLogManager.LogLevel.WARN)
            slot.attached = false
        }
    }

    private fun removeAllRegionSlots() {
        val iterator = maskRegionOverlaySlots.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            removeRegionSlot(slot)
            iterator.remove()
        }
    }

    private fun removeRegionSlot(slot: RegionOverlaySlot) {
        slot.inUse = false
        slot.view.visibility = View.INVISIBLE
        slot.view.release(false)
        if (slot.attached) {
            runCatching { windowManager.removeView(slot.view) }
            slot.attached = false
        }
        slot.label = ""
        slot.lastRegion.setEmpty()
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
    ): Boolean {
        // region 处于采集像素空间，乘以采集->屏幕比例还原为屏幕像素，再减去内容偏移。
        val width = (region.width() * captureScaleX).roundToInt().coerceAtLeast(1)
        val height = (region.height() * captureScaleY).roundToInt().coerceAtLeast(1)
        val x = (region.left * captureScaleX).roundToInt() - metrics.contentOffsetX
        val y = (region.top * captureScaleY).roundToInt() - metrics.contentOffsetY
        if (
            layoutParams.width == width &&
            layoutParams.height == height &&
            layoutParams.x == x &&
            layoutParams.y == y
        ) {
            return false
        }
        layoutParams.width = width
        layoutParams.height = height
        layoutParams.x = x
        layoutParams.y = y
        return true
    }

    private fun bindFullscreenFrame(
        view: ScreenMaskOverlayView,
        frame: ScreenPrivacyMaskRenderer.OverlayFrame,
        metrics: OverlayMetrics,
        generation: Long,
        layoutChanged: Boolean
    ) {
        val (originX, originY) = resolveViewOrigin(view, metrics.contentOffsetX, metrics.contentOffsetY)
        view.bindFrame(
            frame = frame,
            windowOriginX = originX,
            windowOriginY = originY,
            scaleX = captureScaleX,
            scaleY = captureScaleY
        )
        if (!layoutChanged && !view.isLayoutRequested) return
        view.doOnPreDraw {
            if (maskOverlayView !== view || renderGeneration != generation || !view.isAttachedToWindow) {
                return@doOnPreDraw
            }
            val (resolvedX, resolvedY) = resolveViewOrigin(view, metrics.contentOffsetX, metrics.contentOffsetY)
            view.bindFrame(
                frame = frame,
                windowOriginX = resolvedX,
                windowOriginY = resolvedY,
                scaleX = captureScaleX,
                scaleY = captureScaleY
            )
        }
    }

    private fun bindRegionTask(
        view: ScreenMaskOverlayView,
        task: ScreenPrivacyMaskRenderer.DrawTask,
        region: Rect,
        generation: Long,
        layoutChanged: Boolean
    ) {
        // 窗口已放置在屏幕像素坐标，getLocationOnScreen 取不到时回退用缩放后的屏幕坐标。
        val fallbackX = (region.left * captureScaleX).roundToInt()
        val fallbackY = (region.top * captureScaleY).roundToInt()
        // updateViewLayout 的位置可能还未生效，先使用目标位置，布局完成后再校正。
        val (originX, originY) = if (layoutChanged || view.isLayoutRequested) fallbackX to fallbackY
            else resolveViewOrigin(view, fallbackX, fallbackY)
        view.bindRegionTask(
            task = task,
            windowOriginX = originX,
            windowOriginY = originY,
            scaleX = captureScaleX,
            scaleY = captureScaleY
        )
        if (!layoutChanged && !view.isLayoutRequested) return
        view.doOnPreDraw {
            if (renderGeneration != generation || !view.isAttachedToWindow) {
                return@doOnPreDraw
            }
            val (resolvedX, resolvedY) = resolveViewOrigin(view, fallbackX, fallbackY)
            view.bindRegionTask(
                task = task,
                windowOriginX = resolvedX,
                windowOriginY = resolvedY,
                scaleX = captureScaleX,
                scaleY = captureScaleY
            )
        }
    }

    private fun resolveViewOrigin(
        view: View,
        fallbackX: Int,
        fallbackY: Int
    ): Pair<Int, Int> {
        if (!view.isAttachedToWindow) return fallbackX to fallbackY
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[0] to location[1]
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
            baseWindowFlags() or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.contentOffsetX
            y = metrics.contentOffsetY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
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
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (touchThrough) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }
}
