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
    private var reverseMode: Int? = null
    private var reverseRegions: List<ScreenPrivacyMaskRenderer.ClearRegion> = emptyList()
    private var reversePreRender: Boolean = false
    private var contentOffsetX: Float = 0f
    private var contentOffsetY: Float = 0f
    private var regionLeft: Int = 0
    private var regionTop: Int = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap?.takeIf { !it.isRecycled } ?: return
        val localReverseMode = reverseMode
        val offsetX = -contentOffsetX - regionLeft
        val offsetY = -contentOffsetY - regionTop

        canvas.save()
        canvas.translate(offsetX, offsetY)
        if (localReverseMode != null && reversePreRender) {
            applyReverseMask(canvas, bitmap, localReverseMode)
        }
        drawTasks.forEach { drawTask(canvas, bitmap, it) }
        if (localReverseMode != null && !reversePreRender) {
            applyReverseMask(canvas, bitmap, localReverseMode)
        }
        canvas.restore()
    }

    fun setFrame(frame: ScreenPrivacyMaskRenderer.OverlayFrame?) {
        updateSourceBitmap(frame?.sourceBitmap)
        drawTasks = frame?.drawTasks.orEmpty()
        reverseMode = frame?.reverseMode
        reverseRegions = frame?.reverseRegions.orEmpty()
        reversePreRender = frame?.reversePreRender == true
        invalidate()
    }

    fun setRegionBounds(left: Int, top: Int) {
        if (regionLeft == left && regionTop == top) return
        regionLeft = left
        regionTop = top
        invalidate()
    }

    fun setContentOffset(x: Int, y: Int) {
        val newX = x.toFloat()
        val newY = y.toFloat()
        if (contentOffsetX == newX && contentOffsetY == newY) return
        contentOffsetX = newX
        contentOffsetY = newY
        invalidate()
    }

    fun release() {
        sourceBitmap = null
        drawTasks = emptyList()
        reverseMode = null
        reverseRegions = emptyList()
        reversePreRender = false
        regionLeft = 0
        regionTop = 0
        contentOffsetX = 0f
        contentOffsetY = 0f
        invalidate()
    }

    private fun updateSourceBitmap(bitmap: Bitmap?) {
        sourceBitmap = bitmap
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
