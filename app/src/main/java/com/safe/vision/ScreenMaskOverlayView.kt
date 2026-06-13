package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    private var reverseStickerLabel: String? = null
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
        canvas.translate(-windowOriginX, -windowOriginY)
        canvas.scale(scaleX, scaleY)
        if (localReverseMode != null && reversePreRender) {
            applyReverseMask(canvas, bitmap, localReverseMode)
        }
        val outlineShapes = mutableListOf<BlurEffects.OutlineShape>()
        val localSingleTask = singleDrawTask
        if (localSingleTask != null) {
            drawTask(canvas, bitmap, localSingleTask)?.let { outlineShapes.add(it) }
        } else {
            drawTasks.forEach { drawTask(canvas, bitmap, it)?.let { outlineShapes.add(it) } }
        }
        if (localReverseMode != null && !reversePreRender) {
            applyReverseMask(canvas, bitmap, localReverseMode)
        }
        if (localReverseMode != null) {
            outlineShapes.addAll(collectReverseOutlineShapes())
        }
        if (outlineShapes.isNotEmpty()) {
            BlurEffects.drawUnionOutline(canvas, outlineShapes)
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
            reverseStickerLabel = frame?.reverseStickerLabel,
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
            reverseStickerLabel = null,
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
            reverseStickerLabel = null,
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
        reverseStickerLabel: String?,
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
        if (drawTasks !== tasks) {
            drawTasks = tasks
            changed = true
        }
        if (this.singleDrawTask !== singleTask) {
            this.singleDrawTask = singleTask
            changed = true
        }
        if (this.reverseMode != reverseMode) {
            this.reverseMode = reverseMode
            changed = true
        }
        if (this.reverseRegions !== reverseRegions) {
            this.reverseRegions = reverseRegions
            changed = true
        }
        if (this.reversePreRender != reversePreRender) {
            this.reversePreRender = reversePreRender
            changed = true
        }
        if (this.reverseStickerLabel != reverseStickerLabel) {
            this.reverseStickerLabel = reverseStickerLabel
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
    ): BlurEffects.OutlineShape? {
        var outlineShape: BlurEffects.OutlineShape? = null
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
                outlineShape = BlurEffects.OutlineShape.CircleShape(task.drawRect)
            }
            return outlineShape
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
            outlineShape = if (task.usesEyeStrip && task.eyePath != null) {
                BlurEffects.OutlineShape.PathShape(task.eyePath, task.drawRect)
            } else {
                BlurEffects.OutlineShape.RectShape(task.drawRect)
            }
        }
        return outlineShape
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
                val sticker = StickerLoader.loadSticker(appContext, privacySettings, reverseStickerLabel)
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
                clearCircularRegion(canvas, clearRegion.rect)
            } else if (clearRegion.path != null) {
                clearPathRegion(canvas, clearRegion.path)
            } else {
                clearRegion(canvas, clearRegion.rect)
            }
        }
    }

    private fun collectReverseOutlineShapes(): List<BlurEffects.OutlineShape> {
        return reverseRegions.mapNotNull { clearRegion ->
            if (!clearRegion.drawOutline) return@mapNotNull null
            if (clearRegion.circular) {
                BlurEffects.OutlineShape.CircleShape(clearRegion.rect)
            } else if (clearRegion.path != null) {
                BlurEffects.OutlineShape.PathShape(clearRegion.path, clearRegion.rect)
            } else {
                BlurEffects.OutlineShape.RectShape(clearRegion.rect)
            }
        }
    }

    private fun clearRegion(canvas: Canvas, rect: Rect) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        canvas.drawRect(rect, clearPaint)
    }

    private fun clearCircularRegion(canvas: Canvas, rect: Rect) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        canvas.drawCircle(
            rect.exactCenterX(),
            rect.exactCenterY(),
            kotlin.math.hypot(rect.width() / 2f, rect.height() / 2f),
            clearPaint
        )
    }

    private fun clearPathRegion(canvas: Canvas, path: Path) {
        canvas.drawPath(path, clearPaint)
    }
}
