package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.roundToInt

class ScreenPrivacyMaskRenderer(context: Context) {
    data class OverlayRegion(
        val label: String,
        val rect: Rect
    )

    data class OverlayFrame(
        val bitmap: Bitmap,
        val regions: List<OverlayRegion>,
        val requiresFullscreenOverlay: Boolean
    )

    companion object {
        private const val EYE_STRIP_ASPECT_MAX = 3f
    }

    private val appContext = context.applicationContext
    private val privacySettings = PrivacySettingsManager.getInstance(appContext)
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun render(
        sourceBitmap: Bitmap,
        detections: List<YoloOnnxRunner.Detection>,
        labelProfile: DetectionConfig.LabelProfile
    ): OverlayFrame? {
        val defaultBlurMode = privacySettings.getBlurMode(labelProfile)
        val labelOverrides = privacySettings.getLabelEffectOverrides(labelProfile)
        val reverseLabels = privacySettings.getReverseLabels(labelProfile).toSet()
        val useCircularMask = privacySettings.isCircularMaskEnabled()
        val maskOutlineEnabled = privacySettings.isMaskOutlineEnabled()
        val maskOutlineLabels = privacySettings.getMaskOutlineLabels(labelProfile).toSet()

        fun shouldOutline(label: String): Boolean {
            if (!maskOutlineEnabled) return false
            if (maskOutlineLabels.isEmpty()) return true
            return maskOutlineLabels.contains(label)
        }

        fun resolveRenderMode(mode: Int, fallback: Int): Int {
            if (mode != PrivacySettingsManager.BLUR_MODE_EYES) return mode
            return if (fallback == PrivacySettingsManager.BLUR_MODE_EYES) {
                PrivacySettingsManager.BLUR_MODE_MOSAIC
            } else {
                fallback
            }
        }

        data class EyeTarget(
            val rect: Rect,
            val path: Path? = null,
            val rotationDegrees: Float = 0f
        )

        data class NormalRenderTask(
            val className: String,
            val renderMode: Int,
            val drawRect: Rect,
            val allowCircular: Boolean,
            val usesEyeStrip: Boolean,
            val eyePath: Path?,
            val rotationDegrees: Float
        )

        data class ReverseRegion(
            val rect: Rect,
            val circular: Boolean,
            val label: String,
            val path: Path? = null
        )

        fun resolveEyeTarget(detection: YoloOnnxRunner.Detection, faceRect: Rect): EyeTarget {
            val leftEye = detection.leftEye
            val rightEye = detection.rightEye
            if (leftEye != null && rightEye != null) {
                val strip = BlurEffects.eyeStripFromEyes(
                    faceRect,
                    leftEye,
                    rightEye,
                    sourceBitmap.width,
                    sourceBitmap.height
                )
                if (strip != null && strip.bounds.width() > 0 && strip.bounds.height() > 0) {
                    return EyeTarget(
                        rect = strip.bounds,
                        path = strip.path,
                        rotationDegrees = Math.toDegrees(
                            atan2(
                                (rightEye.y - leftEye.y).toDouble(),
                                (rightEye.x - leftEye.x).toDouble()
                            )
                        ).toFloat()
                    )
                }
            }

            return EyeTarget(
                BlurEffects.capAspectRatio(
                    BlurEffects.cropToEyeStrip(faceRect, sourceBitmap.width, sourceBitmap.height),
                    EYE_STRIP_ASPECT_MAX,
                    sourceBitmap.width,
                    sourceBitmap.height
                )
            )
        }

        val normalTasks = mutableListOf<NormalRenderTask>()
        val reverseRegions = mutableListOf<ReverseRegion>()
        var reverseBlurMode: Int? = null
        var reverseModeMixed = false

        detections.forEach { detection ->
            val className = detection.className
            if (!privacySettings.isLabelBlocked(className, labelProfile)) return@forEach

            val rect = BlurEffects.clampRect(
                Rect(
                    detection.box.left.roundToInt(),
                    detection.box.top.roundToInt(),
                    detection.box.right.roundToInt(),
                    detection.box.bottom.roundToInt()
                ),
                sourceBitmap.width,
                sourceBitmap.height
            )
            if (rect.width() <= 0 || rect.height() <= 0) return@forEach

            val blurMode = labelOverrides[className] ?: defaultBlurMode
            val usesEyeStrip = DetectionConfig.isEyeRegionLabel(className)
            val eyeTarget = if (usesEyeStrip) resolveEyeTarget(detection, rect) else null
            val targetRect = eyeTarget?.rect ?: rect
            val maskScale = privacySettings.getEffectiveMaskScale(className)
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
            val renderMode = resolveRenderMode(blurMode, defaultBlurMode)
            val allowCircular = useCircularMask && !usesEyeStrip

            if (reverseLabels.contains(className)) {
                reverseRegions.add(
                    ReverseRegion(
                        rect = scaledTargetRect,
                        circular = allowCircular,
                        label = className,
                        path = if (usesEyeStrip) scaledEyePath else null
                    )
                )
                if (reverseBlurMode == null) {
                    reverseBlurMode = renderMode
                } else if (reverseBlurMode != renderMode) {
                    reverseModeMixed = true
                }
            } else {
                normalTasks.add(
                    NormalRenderTask(
                        className = className,
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

        if (normalTasks.isEmpty() && reverseRegions.isEmpty()) {
            return null
        }

        val regionRects = normalTasks.mapNotNull { task ->
            val rect = BlurEffects.clampRect(task.drawRect, sourceBitmap.width, sourceBitmap.height)
            if (rect.width() > 0 && rect.height() > 0) {
                OverlayRegion(task.className, rect)
            } else {
                null
            }
        }.sortedWith(
            compareBy<OverlayRegion> { it.label }
                .thenBy { it.rect.centerX() }
                .thenBy { it.rect.centerY() }
        )

        val outputBitmap = Bitmap.createBitmap(
            sourceBitmap.width,
            sourceBitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(outputBitmap)

        fun applyEffect(
            targetCanvas: Canvas,
            mode: Int,
            rect: Rect,
            className: String,
            usesEyeStrip: Boolean,
            rotationDegrees: Float
        ) {
            when (mode) {
                PrivacySettingsManager.BLUR_MODE_MOSAIC -> {
                    BlurEffects.drawMosaic(targetCanvas, sourceBitmap, rect, privacySettings.getMosaicBlockSize())
                }
                PrivacySettingsManager.BLUR_MODE_BLACK -> BlurEffects.drawBlack(targetCanvas, rect)
                PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                    BlurEffects.drawGaussian(targetCanvas, sourceBitmap, rect, privacySettings.getGaussianRadius())
                }
                PrivacySettingsManager.BLUR_MODE_STICKER -> {
                    val sticker = StickerLoader.loadSticker(appContext, privacySettings, className)
                    if (sticker != null) {
                        // Pre-composite the current screen content with the sticker so
                        // semi-transparent PNGs don't rely on system overlay blending.
                        targetCanvas.drawBitmap(sourceBitmap, rect, rect, null)
                        BlurEffects.drawSticker(
                            targetCanvas,
                            sticker,
                            rect,
                            sourceBitmap.width,
                            sourceBitmap.height,
                            fitInsideRect = usesEyeStrip,
                            rotationDegrees = rotationDegrees
                        )
                    } else {
                        BlurEffects.drawMosaic(targetCanvas, sourceBitmap, rect, privacySettings.getMosaicBlockSize())
                    }
                }
                PrivacySettingsManager.BLUR_MODE_SOBEL -> {
                    BlurEffects.drawSobelEdge(targetCanvas, sourceBitmap, rect)
                }
                else -> {
                    BlurEffects.drawMosaic(targetCanvas, sourceBitmap, rect, privacySettings.getMosaicBlockSize())
                }
            }
        }

        fun clearRegion(targetCanvas: Canvas, rect: Rect) {
            if (rect.width() <= 0 || rect.height() <= 0) return
            targetCanvas.drawRect(rect, clearPaint)
        }

        fun renderNormalTask(task: NormalRenderTask) {
            if (task.allowCircular) {
                val circleBounds = BlurEffects.circumscribedCircleBounds(
                    task.drawRect,
                    sourceBitmap.width,
                    sourceBitmap.height
                )
                BlurEffects.drawWithCircularClip(canvas, task.drawRect) {
                    applyEffect(canvas, task.renderMode, circleBounds, task.className, task.usesEyeStrip, task.rotationDegrees)
                }
                if (shouldOutline(task.className) && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
                    BlurEffects.drawCircularOutline(canvas, task.drawRect)
                }
                return
            }

            if (task.usesEyeStrip && task.eyePath != null && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
                val checkpoint = canvas.save()
                canvas.clipPath(task.eyePath)
                applyEffect(canvas, task.renderMode, task.drawRect, task.className, task.usesEyeStrip, task.rotationDegrees)
                canvas.restoreToCount(checkpoint)
            } else {
                applyEffect(canvas, task.renderMode, task.drawRect, task.className, task.usesEyeStrip, task.rotationDegrees)
            }
            if (shouldOutline(task.className) && task.renderMode != PrivacySettingsManager.BLUR_MODE_STICKER) {
                BlurEffects.drawRectOutline(canvas, task.drawRect)
            }
        }

        fun clearRegion(region: ReverseRegion) {
            if (region.circular) {
                BlurEffects.drawWithCircularClip(canvas, region.rect) {
                    clearRegion(canvas, region.rect)
                }
            } else if (region.path != null) {
                val checkpoint = canvas.save()
                canvas.clipPath(region.path)
                clearRegion(canvas, region.rect)
                canvas.restoreToCount(checkpoint)
            } else {
                clearRegion(canvas, region.rect)
            }

            if (shouldOutline(region.label)) {
                if (region.circular) {
                    BlurEffects.drawCircularOutline(canvas, region.rect)
                } else {
                    BlurEffects.drawRectOutline(canvas, region.rect)
                }
            }
        }

        fun applyReverseMask(mode: Int) {
            val fullRect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
            when (mode) {
                PrivacySettingsManager.BLUR_MODE_MOSAIC -> {
                    BlurEffects.drawMosaic(canvas, sourceBitmap, fullRect, privacySettings.getMosaicBlockSize())
                }
                PrivacySettingsManager.BLUR_MODE_BLACK -> canvas.drawColor(Color.BLACK)
                PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> {
                    BlurEffects.drawGaussian(canvas, sourceBitmap, fullRect, privacySettings.getGaussianRadius())
                }
                PrivacySettingsManager.BLUR_MODE_STICKER -> {
                    val sticker = StickerLoader.loadSticker(appContext, privacySettings)
                    if (sticker != null) {
                        // Render the sampled screen frame first, then blend the sticker
                        // inside our bitmap to keep alpha behavior consistent in overlay mode.
                        canvas.drawBitmap(sourceBitmap, 0f, 0f, null)
                        BlurEffects.drawSticker(canvas, sticker, fullRect, sourceBitmap.width, sourceBitmap.height)
                    } else {
                        BlurEffects.drawMosaic(canvas, sourceBitmap, fullRect, privacySettings.getMosaicBlockSize())
                    }
                }
                PrivacySettingsManager.BLUR_MODE_SOBEL -> {
                    BlurEffects.drawSobelEdge(canvas, sourceBitmap, fullRect)
                }
                else -> BlurEffects.drawMosaic(canvas, sourceBitmap, fullRect, privacySettings.getMosaicBlockSize())
            }

            reverseRegions.forEach(::clearRegion)
        }

        if (reverseRegions.isNotEmpty() && reverseModeMixed) {
            DebugLogManager.addLog(
                "屏幕遮挡",
                "反向遮挡存在多种效果，已使用默认效果: ${privacySettings.getBlurModeName(defaultBlurMode)}"
            )
        }

        if (reverseRegions.isNotEmpty() && privacySettings.isReversePreRenderEnabled()) {
            applyReverseMask(if (reverseModeMixed) defaultBlurMode else reverseBlurMode ?: defaultBlurMode)
        }
        normalTasks.forEach(::renderNormalTask)
        if (reverseRegions.isNotEmpty() && !privacySettings.isReversePreRenderEnabled()) {
            applyReverseMask(if (reverseModeMixed) defaultBlurMode else reverseBlurMode ?: defaultBlurMode)
        }
        return OverlayFrame(
            bitmap = outputBitmap,
            regions = regionRects,
            requiresFullscreenOverlay = reverseRegions.isNotEmpty()
        )
    }
}
