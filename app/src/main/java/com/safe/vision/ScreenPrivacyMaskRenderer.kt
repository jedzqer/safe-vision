package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.roundToInt

class ScreenPrivacyMaskRenderer(context: Context) {
    data class DrawTask(
        val label: String,
        val renderMode: Int,
        val drawRect: Rect,
        val allowCircular: Boolean,
        val usesEyeStrip: Boolean,
        val eyePath: Path?,
        val rotationDegrees: Float,
        val drawOutline: Boolean
    )

    data class ClearRegion(
        val rect: Rect,
        val circular: Boolean,
        val path: Path?,
        val drawOutline: Boolean
    )

    data class OverlayFrame(
        val sourceBitmap: Bitmap,
        val drawTasks: List<DrawTask>,
        val reverseMode: Int?,
        val reverseRegions: List<ClearRegion>,
        val reversePreRender: Boolean
    ) {
        val requiresFullscreenOverlay: Boolean
            get() = reverseMode != null
    }

    companion object {
        private const val EYE_STRIP_ASPECT_MAX = 3f
    }

    private val privacySettings = PrivacySettingsManager.getInstance(context.applicationContext)

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

        data class PendingTask(
            val label: String,
            val renderMode: Int,
            val drawRect: Rect,
            val allowCircular: Boolean,
            val usesEyeStrip: Boolean,
            val eyePath: Path?,
            val rotationDegrees: Float
        )

        data class PendingReverseRegion(
            val rect: Rect,
            val circular: Boolean,
            val path: Path?,
            val drawOutline: Boolean
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

        val drawTasks = mutableListOf<DrawTask>()
        val reverseRegions = mutableListOf<PendingReverseRegion>()
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
            val safeRect = BlurEffects.clampRect(
                scaledTargetRect,
                sourceBitmap.width,
                sourceBitmap.height
            )
            if (safeRect.width() <= 0 || safeRect.height() <= 0) return@forEach

            if (reverseLabels.contains(className)) {
                reverseRegions.add(
                    PendingReverseRegion(
                        rect = Rect(safeRect),
                        circular = allowCircular,
                        path = scaledEyePath?.let(::Path),
                        drawOutline = shouldOutline(className)
                    )
                )
                if (reverseBlurMode == null) {
                    reverseBlurMode = renderMode
                } else if (reverseBlurMode != renderMode) {
                    reverseModeMixed = true
                }
            } else {
                drawTasks.add(
                    DrawTask(
                        label = className,
                        renderMode = renderMode,
                        drawRect = Rect(safeRect),
                        allowCircular = allowCircular,
                        usesEyeStrip = usesEyeStrip,
                        eyePath = scaledEyePath?.let(::Path),
                        rotationDegrees = if (usesEyeStrip) eyeTarget?.rotationDegrees ?: 0f else 0f,
                        drawOutline = shouldOutline(className) &&
                            renderMode != PrivacySettingsManager.BLUR_MODE_STICKER
                    )
                )
            }
        }

        if (drawTasks.isEmpty() && reverseRegions.isEmpty()) {
            sourceBitmap.recycle()
            return null
        }

        if (reverseRegions.isNotEmpty() && reverseModeMixed) {
            DebugLogManager.addLog(
                "屏幕遮挡",
                "反向遮挡存在多种效果，已使用默认效果: ${privacySettings.getBlurModeName(defaultBlurMode)}"
            )
        }

        return OverlayFrame(
            sourceBitmap = sourceBitmap,
            drawTasks = drawTasks.sortedWith(
                compareBy<DrawTask> { it.label }
                    .thenBy { it.drawRect.centerX() }
                    .thenBy { it.drawRect.centerY() }
            ),
            reverseMode = if (reverseRegions.isNotEmpty()) {
                if (reverseModeMixed) defaultBlurMode else reverseBlurMode ?: defaultBlurMode
            } else {
                null
            },
            reverseRegions = reverseRegions.map { region ->
                ClearRegion(
                    rect = Rect(region.rect),
                    circular = region.circular,
                    path = region.path?.let(::Path),
                    drawOutline = region.drawOutline
                )
            },
            reversePreRender = privacySettings.isReversePreRenderEnabled()
        )
    }
}
