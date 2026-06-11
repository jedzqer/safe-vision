package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class ScreenMaskOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val appContext = context.applicationContext
    private val privacySettings = PrivacySettingsManager.getInstance(appContext)
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var sourceBitmap: Bitmap? = null
    private var drawTasks: List<ScreenPrivacyMaskRenderer.DrawTask> = emptyList()
    private var singleDrawTask: ScreenPrivacyMaskRenderer.DrawTask? = null
    private var reverseMode: Int? = null
    private var reverseRegions: List<ScreenPrivacyMaskRenderer.ClearRegion> = emptyList()
    private var reversePreRender: Boolean = false
    private var windowOriginX: Float = 0f
    private var windowOriginY: Float = 0f
    // 采集面降分辨率后,draw 坐标处于采集像素空间,绘制时按该比例放大到屏幕像素。
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap?.takeIf { !it.isRecycled } ?: return
        val localReverseMode = reverseMode

        canvas.save()
        // 屏幕像素 = 缩放(采集坐标) - 窗口屏幕原点。先平移再缩放,使内层绘制坐标保持采集像素空间。
        canvas.translate(-windowOriginX, -windowOriginY)
        canvas.scale(scaleX, scaleY)
        if (localReverseMode != null && reversePreRender) {
            applyReverseMask(canvas, bitmap, localReverseMode)
        }
        val localSingleTask = singleDrawTask
        if (localSingleTask != null) {
            drawTask(canvas, bitmap, localSingleTask)
        } else {
            drawTasks.forEach { drawTask(canvas, bitmap, it) }
        }
        if (localReverseMode != null && !reversePreRender) {
            applyReverseMask(canvas, bitmap, localReverseMode)
        }
        canvas.restore()
    }

    fun bindFrame(
        frame: ScreenPrivacyMaskRenderer.OverlayFrame?,
        windowOriginX: Int,
        windowOriginY: Int,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        val changed = updateState(
            bitmap = frame?.sourceBitmap,
            tasks = frame?.drawTasks.orEmpty(),
            singleTask = null,
            reverseMode = frame?.reverseMode,
            reverseRegions = frame?.reverseRegions.orEmpty(),
            reversePreRender = frame?.reversePreRender == true,
            windowOriginX = windowOriginX.toFloat(),
            windowOriginY = windowOriginY.toFloat(),
            scaleX = scaleX,
            scaleY = scaleY
        )
        if (changed) invalidate()
    }

    fun bindRegionTask(
        bitmap: Bitmap?,
        task: ScreenPrivacyMaskRenderer.DrawTask?,
        windowOriginX: Int,
        windowOriginY: Int,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ) {
        val changed = updateState(
            bitmap = bitmap,
            tasks = emptyList(),
            singleTask = task,
            reverseMode = null,
            reverseRegions = emptyList(),
            reversePreRender = false,
            windowOriginX = windowOriginX.toFloat(),
            windowOriginY = windowOriginY.toFloat(),
            scaleX = scaleX,
            scaleY = scaleY
        )
        if (changed) invalidate()
    }

    fun release(shouldInvalidate: Boolean = true) {
        val changed = updateState(
            bitmap = null,
            tasks = emptyList(),
            singleTask = null,
            reverseMode = null,
            reverseRegions = emptyList(),
            reversePreRender = false,
            windowOriginX = 0f,
            windowOriginY = 0f,
            scaleX = 1f,
            scaleY = 1f
        )
        if (changed && shouldInvalidate) invalidate()
    }

    private fun updateState(
        bitmap: Bitmap?,
        tasks: List<ScreenPrivacyMaskRenderer.DrawTask>,
        singleTask: ScreenPrivacyMaskRenderer.DrawTask?,
        reverseMode: Int?,
        reverseRegions: List<ScreenPrivacyMaskRenderer.ClearRegion>,
        reversePreRender: Boolean,
        windowOriginX: Float,
        windowOriginY: Float,
        scaleX: Float,
        scaleY: Float
    ): Boolean {
        var changed = false
        if (sourceBitmap !== bitmap) {
            sourceBitmap = bitmap
            changed = true
        }
        if (drawTasks != tasks) {
            drawTasks = tasks
            changed = true
        }
        if (this.singleDrawTask != singleTask) {
            this.singleDrawTask = singleTask
            changed = true
        }
        if (this.reverseMode != reverseMode) {
            this.reverseMode = reverseMode
            changed = true
        }
        if (this.reverseRegions != reverseRegions) {
            this.reverseRegions = reverseRegions
            changed = true
        }
        if (this.reversePreRender != reversePreRender) {
            this.reversePreRender = reversePreRender
            changed = true
        }
        if (this.windowOriginX != windowOriginX || this.windowOriginY != windowOriginY) {
            this.windowOriginX = windowOriginX
            this.windowOriginY = windowOriginY
            changed = true
        }
        if (this.scaleX != scaleX || this.scaleY != scaleY) {
            this.scaleX = scaleX
            this.scaleY = scaleY
            changed = true
        }
        return changed
    }

    private fun drawTask(
        canvas: Canvas,
        bitmap: Bitmap,
        task: ScreenPrivacyMaskRenderer.DrawTask
    ) {
        if (task.allowCircular) {
            val circleBounds = BlurEffects.circumscribedCircleBounds(
                task.drawRect,
                bitmap.width,
                bitmap.height
            )
            BlurEffects.drawWithCircularClip(canvas, task.drawRect) {
                applyEffect(canvas, bitmap, task.renderMode, circleBounds, task)
            }
            if (task.drawOutline) {
                BlurEffects.drawCircularOutline(canvas, task.drawRect)
            }
            return
        }

        if (task.usesEyeStrip && task.eyePath != null && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
            val checkpoint = canvas.save()
            canvas.clipPath(task.eyePath)
            applyEffect(canvas, bitmap, task.renderMode, task.drawRect, task)
            canvas.restoreToCount(checkpoint)
        } else {
            applyEffect(canvas, bitmap, task.renderMode, task.drawRect, task)
        }
        if (task.drawOutline) {
            BlurEffects.drawRectOutline(canvas, task.drawRect)
        }
    }

    private fun applyEffect(
        canvas: Canvas,
        bitmap: Bitmap,
        mode: Int,
        rect: Rect,
        task: ScreenPrivacyMaskRenderer.DrawTask
    ) {
        when (mode) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> {
                BlurEffects.drawMosaic(canvas, bitmap, rect, privacySettings.getMosaicBlockSize())
            }
            PrivacySettingsManager.BLUR_MODE_BLACK -> BlurEffects.drawBlack(canvas, rect)
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                BlurEffects.drawGaussian(canvas, bitmap, rect, privacySettings.getGaussianRadius())
            }
            PrivacySettingsManager.BLUR_MODE_STICKER -> {
                val sticker = StickerLoader.loadSticker(appContext, privacySettings, task.label)
                if (sticker != null) {
                    BlurEffects.drawSticker(
                        canvas,
                        sticker,
                        rect,
                        bitmap.width,
                        bitmap.height,
                        fitInsideRect = task.usesEyeStrip,
                        rotationDegrees = task.rotationDegrees
                    )
                } else {
                    BlurEffects.drawMosaic(canvas, bitmap, rect, privacySettings.getMosaicBlockSize())
                }
            }
            PrivacySettingsManager.BLUR_MODE_SOBEL -> BlurEffects.drawSobelEdge(canvas, bitmap, rect)
            else -> BlurEffects.drawMosaic(canvas, bitmap, rect, privacySettings.getMosaicBlockSize())
        }
    }

    private fun applyReverseMask(canvas: Canvas, bitmap: Bitmap, mode: Int) {
        val fullRect = Rect(0, 0, bitmap.width, bitmap.height)
        when (mode) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> {
                BlurEffects.drawMosaic(canvas, bitmap, fullRect, privacySettings.getMosaicBlockSize())
            }
            PrivacySettingsManager.BLUR_MODE_BLACK -> canvas.drawColor(Color.BLACK)
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                BlurEffects.drawGaussian(canvas, bitmap, fullRect, privacySettings.getGaussianRadius())
            }
            PrivacySettingsManager.BLUR_MODE_STICKER -> {
                val sticker = StickerLoader.loadSticker(appContext, privacySettings)
                if (sticker != null) {
                    BlurEffects.drawSticker(canvas, sticker, fullRect, bitmap.width, bitmap.height)
                } else {
                    BlurEffects.drawMosaic(canvas, bitmap, fullRect, privacySettings.getMosaicBlockSize())
                }
            }
            PrivacySettingsManager.BLUR_MODE_SOBEL -> BlurEffects.drawSobelEdge(canvas, bitmap, fullRect)
            else -> BlurEffects.drawMosaic(canvas, bitmap, fullRect, privacySettings.getMosaicBlockSize())
        }

        reverseRegions.forEach { clearRegion ->
            if (clearRegion.circular) {
                BlurEffects.drawWithCircularClip(canvas, clearRegion.rect) {
                    clearRegion(canvas, clearRegion.rect)
                }
            } else if (clearRegion.path != null) {
                val checkpoint = canvas.save()
                canvas.clipPath(clearRegion.path)
                clearRegion(canvas, clearRegion.rect)
                canvas.restoreToCount(checkpoint)
            } else {
                clearRegion(canvas, clearRegion.rect)
            }

            if (clearRegion.drawOutline) {
                if (clearRegion.circular) {
                    BlurEffects.drawCircularOutline(canvas, clearRegion.rect)
                } else {
                    BlurEffects.drawRectOutline(canvas, clearRegion.rect)
                }
            }
        }
    }

    private fun clearRegion(canvas: Canvas, rect: Rect) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        canvas.drawRect(rect, clearPaint)
    }
}
