package com.safe.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.atan2

class DetectionRenderEngine(
    private val privacySettings: PrivacySettingsManager
) {
    data class DetectionRenderItem(
        val className: String,
        val rect: Rect,
        val leftEye: PointF? = null,
        val rightEye: PointF? = null,
        val eyeBarRect: RectF? = null,
        val eyeBarRotationDegrees: Float? = null
    )

    data class RenderSettings(
        val defaultBlurMode: Int,
        val labelEffectOverrides: Map<String, Int>,
        val reverseLabels: Set<String>,
        val useCircularMask: Boolean,
        val reversePreRenderEnabled: Boolean,
        val maskOutlineEnabled: Boolean,
        val maskOutlineLabels: Set<String>
    )

    data class RenderCallbacks(
        val onReverseModeMixed: (Int) -> Unit = {},
        val onReverseStickerFallback: () -> Unit = {},
        val onNormalStickerFallback: () -> Unit = {}
    )

    private data class ReverseRect(val rect: Rect, val circular: Boolean, val label: String)

    private data class NormalRenderTask(
        val className: String,
        val renderMode: Int,
        val drawRect: Rect,
        val allowCircular: Boolean,
        val usesEyeStrip: Boolean,
        val eyePath: Path?,
        val rotationDegrees: Float
    )

    private data class EyeTarget(
        val rect: Rect,
        val path: Path? = null,
        val rotationDegrees: Float = 0f
    )

    companion object {
        private const val EYE_STRIP_ASPECT_MAX = 3f
    }

    fun applyDetections(
        sourceBitmap: Bitmap,
        detections: List<DetectionRenderItem>,
        settings: RenderSettings,
        stickerProvider: (String?) -> Bitmap?,
        normalEffectSourceProvider: (Bitmap) -> Bitmap = { it },
        callbacks: RenderCallbacks = RenderCallbacks()
    ): Bitmap {
        if (detections.isEmpty()) return sourceBitmap

        val reverseRects = mutableListOf<ReverseRect>()
        val normalTasks = mutableListOf<NormalRenderTask>()
        var reverseBlurMode: Int? = null
        var reverseModeMixed = false

        fun shouldOutline(label: String): Boolean {
            if (!settings.maskOutlineEnabled) return false
            if (settings.maskOutlineLabels.isEmpty()) return true
            return settings.maskOutlineLabels.contains(label)
        }

        detections.forEach { detection ->
            val rect = BlurEffects.clampRect(detection.rect, sourceBitmap.width, sourceBitmap.height)
            if (rect.width() <= 0 || rect.height() <= 0) return@forEach

            val blurMode = settings.labelEffectOverrides[detection.className] ?: settings.defaultBlurMode
            val usesEyeStrip = DetectionConfig.FACE_LABELS.contains(detection.className) &&
                (privacySettings.isLabelEyeMode(detection.className) || blurMode == PrivacySettingsManager.BLUR_MODE_EYES)
            val eyeTarget = if (usesEyeStrip) {
                resolveEyeTarget(detection, rect, sourceBitmap.width, sourceBitmap.height)
            } else {
                null
            }
            val targetRect = eyeTarget?.rect ?: rect
            val maskScale = privacySettings.getEffectiveMaskScale(detection.className)
            val scaledTargetRect = BlurEffects.scaleRect(
                targetRect,
                maskScale,
                sourceBitmap.width,
                sourceBitmap.height
            )
            val scaledEyePath = if (usesEyeStrip && eyeTarget?.path != null && maskScale > 1f) {
                Path(eyeTarget.path).apply {
                    transform(
                        Matrix().apply {
                            setScale(
                                maskScale,
                                maskScale,
                                targetRect.exactCenterX(),
                                targetRect.exactCenterY()
                            )
                        }
                    )
                }
            } else {
                eyeTarget?.path
            }
            val renderMode = resolveRenderMode(blurMode, settings.defaultBlurMode)
            val allowCircular = settings.useCircularMask && !usesEyeStrip

            if (settings.reverseLabels.contains(detection.className)) {
                reverseRects.add(ReverseRect(scaledTargetRect, allowCircular, detection.className))
                if (reverseBlurMode == null) {
                    reverseBlurMode = renderMode
                } else if (reverseBlurMode != renderMode) {
                    reverseModeMixed = true
                }
            } else {
                normalTasks.add(
                    NormalRenderTask(
                        className = detection.className,
                        renderMode = renderMode,
                        drawRect = scaledTargetRect,
                        allowCircular = allowCircular,
                        usesEyeStrip = usesEyeStrip,
                        eyePath = scaledEyePath,
                        rotationDegrees = if (usesEyeStrip) eyeTarget?.rotationDegrees ?: 0f else 0f
                    )
                )
            }
        }

        val modeToUse = if (reverseModeMixed) settings.defaultBlurMode else (reverseBlurMode ?: settings.defaultBlurMode)
        if (reverseRects.isNotEmpty() && reverseModeMixed) {
            callbacks.onReverseModeMixed(modeToUse)
        }

        val preRenderReverse = reverseRects.isNotEmpty() && settings.reversePreRenderEnabled
        val outputBitmap = if (preRenderReverse) {
            applyReverseMask(sourceBitmap, reverseRects, modeToUse, stickerProvider, ::shouldOutline, callbacks)
        } else if (normalTasks.isNotEmpty()) {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            sourceBitmap
        }
        if (normalTasks.isNotEmpty()) {
            val outputCanvas = Canvas(outputBitmap)
            val normalEffectSource = normalEffectSourceProvider(outputBitmap)
            normalTasks.forEach { task ->
                renderNormalTask(
                    canvas = outputCanvas,
                    source = normalEffectSource,
                    task = task,
                    stickerProvider = stickerProvider,
                    shouldOutline = ::shouldOutline,
                    callbacks = callbacks
                )
            }
        }

        if (reverseRects.isNotEmpty() && !preRenderReverse) {
            return applyReverseMask(outputBitmap, reverseRects, modeToUse, stickerProvider, ::shouldOutline, callbacks)
        }
        return outputBitmap
    }

    private fun renderNormalTask(
        canvas: Canvas,
        source: Bitmap,
        task: NormalRenderTask,
        stickerProvider: (String?) -> Bitmap?,
        shouldOutline: (String) -> Boolean,
        callbacks: RenderCallbacks
    ) {
        val applyEffect: (Rect) -> Unit = { drawRect ->
            when (task.renderMode) {
                PrivacySettingsManager.BLUR_MODE_BLACK -> BlurEffects.drawBlack(canvas, drawRect)
                PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                    BlurEffects.drawGaussian(canvas, source, drawRect, privacySettings.getGaussianRadius())
                }
                PrivacySettingsManager.BLUR_MODE_SOBEL -> BlurEffects.drawSobelEdge(canvas, source, drawRect)
                PrivacySettingsManager.BLUR_MODE_STICKER -> {
                    val stickerBitmap = stickerProvider(task.className)
                    if (stickerBitmap != null) {
                        BlurEffects.drawSticker(
                            canvas,
                            stickerBitmap,
                            drawRect,
                            source.width,
                            source.height,
                            fitInsideRect = task.usesEyeStrip,
                            rotationDegrees = task.rotationDegrees
                        )
                    } else {
                        callbacks.onNormalStickerFallback()
                        BlurEffects.drawMosaic(canvas, source, drawRect, privacySettings.getMosaicBlockSize())
                    }
                }
                else -> BlurEffects.drawMosaic(canvas, source, drawRect, privacySettings.getMosaicBlockSize())
            }
        }
        if (task.allowCircular) {
            val circleBounds = BlurEffects.circumscribedCircleBounds(task.drawRect, source.width, source.height)
            BlurEffects.drawWithCircularClip(canvas, task.drawRect) { applyEffect(circleBounds) }
            if (shouldOutline(task.className) && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
                BlurEffects.drawCircularOutline(canvas, task.drawRect)
            }
        } else {
            if (task.usesEyeStrip && task.eyePath != null && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
                val save = canvas.save()
                canvas.clipPath(task.eyePath)
                applyEffect(task.drawRect)
                canvas.restoreToCount(save)
            } else {
                applyEffect(task.drawRect)
            }
            if (shouldOutline(task.className) && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
                BlurEffects.drawRectOutline(canvas, task.drawRect)
            }
        }
    }

    private fun applyReverseMask(
        base: Bitmap,
        rects: List<ReverseRect>,
        mode: Int,
        stickerProvider: (String?) -> Bitmap?,
        shouldOutline: (String) -> Boolean,
        callbacks: RenderCallbacks
    ): Bitmap {
        val safeRects = rects.mapNotNull { item ->
            val safeRect = BlurEffects.clampRect(item.rect, base.width, base.height)
            if (safeRect.width() > 0 && safeRect.height() > 0) item.copy(rect = safeRect) else null
        }
        if (safeRects.isEmpty()) return base

        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val outputCanvas = Canvas(output)
        val fullRect = Rect(0, 0, base.width, base.height)
        when (mode) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> {
                BlurEffects.drawMosaic(outputCanvas, base, fullRect, privacySettings.getMosaicBlockSize())
            }
            PrivacySettingsManager.BLUR_MODE_BLACK -> outputCanvas.drawColor(android.graphics.Color.BLACK)
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                BlurEffects.drawGaussian(outputCanvas, base, fullRect, privacySettings.getGaussianRadius())
            }
            PrivacySettingsManager.BLUR_MODE_SOBEL -> BlurEffects.drawSobelEdge(outputCanvas, base, fullRect)
            PrivacySettingsManager.BLUR_MODE_STICKER -> {
                val stickerBitmap = if (safeRects.size == 1) {
                    stickerProvider(safeRects.first().label)
                } else {
                    stickerProvider(null)
                }
                if (stickerBitmap != null) {
                    BlurEffects.drawSticker(outputCanvas, stickerBitmap, fullRect, base.width, base.height)
                } else {
                    callbacks.onReverseStickerFallback()
                    BlurEffects.drawMosaic(outputCanvas, base, fullRect, privacySettings.getMosaicBlockSize())
                }
            }
            else -> BlurEffects.drawMosaic(outputCanvas, base, fullRect, privacySettings.getMosaicBlockSize())
        }

        safeRects.forEach { item ->
            if (item.circular) {
                val circleBounds = BlurEffects.circumscribedCircleBounds(item.rect, base.width, base.height)
                BlurEffects.drawWithCircularClip(outputCanvas, item.rect) {
                    outputCanvas.drawBitmap(base, circleBounds, circleBounds, null)
                }
            } else {
                outputCanvas.drawBitmap(base, item.rect, item.rect, null)
            }
            if (shouldOutline(item.label) && mode != PrivacySettingsManager.BLUR_MODE_STICKER) {
                if (item.circular) BlurEffects.drawCircularOutline(outputCanvas, item.rect)
                else BlurEffects.drawRectOutline(outputCanvas, item.rect)
            }
        }
        return output
    }

    private fun resolveEyeTarget(
        detection: DetectionRenderItem,
        faceRect: Rect,
        width: Int,
        height: Int
    ): EyeTarget {
        val manualEyeBar = detection.eyeBarRect?.let {
            BlurEffects.clampRect(
                Rect(
                    it.left.toInt(),
                    it.top.toInt(),
                    it.right.toInt(),
                    it.bottom.toInt()
                ),
                width,
                height
            )
        }
        if (manualEyeBar != null && manualEyeBar.width() > 0 && manualEyeBar.height() > 0) {
            return EyeTarget(
                rect = manualEyeBar,
                path = BlurEffects.rotatedRectPath(
                    RectF(manualEyeBar),
                    detection.eyeBarRotationDegrees ?: 0f
                ),
                rotationDegrees = detection.eyeBarRotationDegrees ?: 0f
            )
        }

        if (detection.leftEye != null && detection.rightEye != null) {
            val strip = BlurEffects.eyeStripFromEyes(faceRect, detection.leftEye, detection.rightEye, width, height)
            if (strip != null && strip.bounds.width() > 0 && strip.bounds.height() > 0) {
                return EyeTarget(
                    rect = strip.bounds,
                    path = strip.path,
                    rotationDegrees = Math.toDegrees(
                        atan2(
                            (detection.rightEye.y - detection.leftEye.y).toDouble(),
                            (detection.rightEye.x - detection.leftEye.x).toDouble()
                        )
                    ).toFloat()
                )
            }
        }

        return EyeTarget(
            BlurEffects.capAspectRatio(
                BlurEffects.cropToEyeStrip(faceRect, width, height),
                EYE_STRIP_ASPECT_MAX,
                width,
                height
            )
        )
    }

    private fun resolveRenderMode(mode: Int, fallback: Int): Int {
        if (mode != PrivacySettingsManager.BLUR_MODE_EYES) return mode
        return if (fallback == PrivacySettingsManager.BLUR_MODE_EYES) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC
        } else {
            fallback
        }
    }
}
