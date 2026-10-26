package com.safe.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.os.Trace
import android.util.AttributeSet
import android.view.View

/** Displays immutable prepared patches and cheap geometry; never processes capture pixels. */
class ScreenMaskOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var frame: ScreenPrivacyMaskRenderer.OverlayFrame? = null
    private var singleTask: ScreenPrivacyMaskRenderer.DrawTask? = null
    private var captureTasks: List<ScreenPrivacyMaskRenderer.DrawTask> = emptyList()
    private var screenTasks: List<ScreenPrivacyMaskRenderer.DrawTask> = emptyList()
    private var outlines: List<BlurEffects.OutlineShape> = emptyList()
    private val blackPaint = Paint().apply { color = android.graphics.Color.BLACK }
    private var originX = 0f
    private var originY = 0f
    private var captureScaleX = 1f
    private var captureScaleY = 1f
    private var submittedAtNs = 0L
    private var logStartNs = 0L
    private var drawSamples = 0
    private var waitTotalNs = 0L
    private var waitMaxNs = 0L
    private var drawTotalNs = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val start = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("ScreenMask.draw")
        try {
            val checkpoint = canvas.save()
            canvas.translate(-originX, -originY)
            canvas.scale(captureScaleX, captureScaleY)
            frame?.preRenderedMaskBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            captureTasks.forEach { task ->
                val patch = task.renderedBitmap
                if (patch != null) {
                    canvas.drawBitmap(patch, task.drawRect.left.toFloat(), task.drawRect.top.toFloat(), null)
                } else if (task.renderMode == PrivacySettingsManager.BLUR_MODE_BLACK) {
                    val clip = canvas.save()
                    // Match the bounds of the previous pre-rendered patch, including circular masks.
                    canvas.clipRect(task.drawRect)
                    if (task.allowCircular) {
                        BlurEffects.drawWithCircularClip(canvas, task.drawRect) {
                            canvas.drawRect(task.drawRect, blackPaint)
                        }
                    } else {
                        task.eyePath?.let { canvas.clipPath(it) }
                        canvas.drawRect(task.drawRect, blackPaint)
                    }
                    canvas.restoreToCount(clip)
                }
            }
            if (outlines.isNotEmpty()) BlurEffects.drawUnionOutline(canvas, outlines)
            canvas.restoreToCount(checkpoint)
            screenTasks.forEach { task ->
                task.renderedBitmap?.let { patch ->
                    canvas.drawBitmap(patch, task.drawRect.left * captureScaleX - originX,
                        task.drawRect.top * captureScaleY - originY, null)
                }
            }
        } finally {
            Trace.endSection()
        }
        if (submittedAtNs != 0L) {
            val wait = start - submittedAtNs
            submittedAtNs = 0L
            if (logStartNs == 0L) logStartNs = start
            drawSamples++
            waitTotalNs += wait
            waitMaxNs = maxOf(waitMaxNs, wait)
            drawTotalNs += SystemClock.elapsedRealtimeNanos() - start
            if (start - logStartNs >= 5_000_000_000L) {
                DebugLogManager.addLog("屏幕绘制",
                    "$drawSamples 帧: 提交到绘制=${waitTotalNs / drawSamples / 1_000_000}ms, " +
                        "最长=${waitMaxNs / 1_000_000}ms, View绘制=${drawTotalNs / drawSamples / 1_000_000}ms, " +
                        "硬件加速=$isHardwareAccelerated（不含GPU执行/系统呈现等待）")
                logStartNs = start
                drawSamples = 0
                waitTotalNs = 0L
                waitMaxNs = 0L
                drawTotalNs = 0L
            }
        }
    }

    fun bindFrame(frame: ScreenPrivacyMaskRenderer.OverlayFrame?, windowOriginX: Int,
                  windowOriginY: Int, scaleX: Float = 1f, scaleY: Float = 1f) {
        // Equal black geometry / cached stickers need no new display list, even after a recheck.
        val changed = this.frame?.preRenderedMaskBitmap !== frame?.preRenderedMaskBitmap ||
            this.frame?.drawTasks != frame?.drawTasks || singleTask != null
        this.frame = frame
        singleTask = null
        if (changed) updateTasks(frame?.drawTasks.orEmpty())
        bindCoordinates(changed, windowOriginX, windowOriginY, scaleX, scaleY)
    }

    fun bindRegionTask(task: ScreenPrivacyMaskRenderer.DrawTask?, windowOriginX: Int,
                       windowOriginY: Int, scaleX: Float = 1f, scaleY: Float = 1f) {
        val changed = singleTask != task || frame != null
        frame = null
        singleTask = task
        if (changed) updateTasks(task?.let(::listOf).orEmpty())
        bindCoordinates(changed, windowOriginX, windowOriginY, scaleX, scaleY)
    }

    private fun updateTasks(tasks: List<ScreenPrivacyMaskRenderer.DrawTask>) {
        captureTasks = tasks.filter { it.renderedBitmapSpace == ScreenPrivacyMaskRenderer.RenderedBitmapSpace.CAPTURE }
        screenTasks = tasks.filter { it.renderedBitmapSpace == ScreenPrivacyMaskRenderer.RenderedBitmapSpace.SCREEN }
        outlines = captureTasks.mapNotNull { task ->
            if (!task.drawOutline) null
            else if (task.allowCircular) BlurEffects.OutlineShape.CircleShape(task.drawRect)
            else if (task.usesEyeStrip && task.eyePath != null) BlurEffects.OutlineShape.PathShape(task.eyePath, task.drawRect)
            else BlurEffects.OutlineShape.RectShape(task.drawRect)
        }
    }

    private fun bindCoordinates(contentChanged: Boolean, x: Int, y: Int, sx: Float, sy: Float) {
        val changed = contentChanged || originX != x.toFloat() || originY != y.toFloat() ||
            captureScaleX != sx || captureScaleY != sy
        originX = x.toFloat()
        originY = y.toFloat()
        captureScaleX = sx
        captureScaleY = sy
        if (changed) {
            // Keep the first submission timestamp through a pre-draw coordinate correction.
            if (submittedAtNs == 0L) submittedAtNs = SystemClock.elapsedRealtimeNanos()
            invalidate() // ViewRoot coalesces invalidations for the next vsync; no extra callback hop.
        }
    }

    fun release(shouldInvalidate: Boolean = true) {
        val hadContent = frame != null || singleTask != null
        frame = null
        singleTask = null
        captureTasks = emptyList()
        screenTasks = emptyList()
        outlines = emptyList()
        submittedAtNs = 0L
        if (hadContent && shouldInvalidate) invalidate()
    }
}
