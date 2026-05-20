package com.safe.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF

data class DetectionFrame(
    val centerIndex: Int,
    val detections: List<YoloOnnxRunner.Detection>
)

class VideoDetectionProcessor(
    private val privacySettings: PrivacySettingsManager
) {
    private val renderEngine = DetectionRenderEngine(privacySettings)
    private val highIouThreshold = 0.7f
    private val midIouThreshold = 0.4f
    private var reverseModeMixedLogged = false
    private var reverseStickerFallbackLogged = false

    fun resetSessionFlags() {
        reverseModeMixedLogged = false
        reverseStickerFallbackLogged = false
    }

    fun shouldEnrichFaceLandmarks(
        blockedLabels: List<String>,
        defaultBlurMode: Int,
        labelEffectOverrides: Map<String, Int>
    ): Boolean {
        val eyeModeByLabel = blockedLabels.contains(DetectionConfig.EYE_REGION_LABEL)
        val eyeModeByDefault = eyeModeByLabel && defaultBlurMode == PrivacySettingsManager.BLUR_MODE_EYES
        val eyeModeByOverride = eyeModeByLabel && (
            labelEffectOverrides[DetectionConfig.EYE_REGION_LABEL] == PrivacySettingsManager.BLUR_MODE_EYES
        )
        return eyeModeByLabel || eyeModeByDefault || eyeModeByOverride
    }

    fun blendDetections(prev: DetectionFrame, next: DetectionFrame, targetIndex: Int): List<YoloOnnxRunner.Detection> {
        if (next.centerIndex == prev.centerIndex) return prev.detections
        val t = ((targetIndex - prev.centerIndex).toFloat() / (next.centerIndex - prev.centerIndex))
            .coerceIn(0f, 1f)
        val results = mutableListOf<YoloOnnxRunner.Detection>()
        val usedNext = BooleanArray(next.detections.size)
        var matched = 0
        prev.detections.forEach { prevDet ->
            var bestIdx = -1
            var bestIou = 0f
            next.detections.forEachIndexed { idx, nextDet ->
                if (usedNext[idx]) return@forEachIndexed
                if (prevDet.className != nextDet.className) return@forEachIndexed
                val iouVal = iou(prevDet.box, nextDet.box)
                if (iouVal > bestIou) {
                    bestIou = iouVal
                    bestIdx = idx
                }
            }
            if (bestIdx >= 0 && bestIou >= midIouThreshold) {
                matched++
                usedNext[bestIdx] = true
                val nextDet = next.detections[bestIdx]
                val box = if (bestIou >= highIouThreshold) {
                    if (t < 0.5f) prevDet.box else nextDet.box
                } else {
                    interpolateBox(prevDet.box, nextDet.box, t)
                }
                results.add(
                    YoloOnnxRunner.Detection(
                        className = prevDet.className,
                        score = (prevDet.score + nextDet.score) / 2f,
                        box = box,
                        leftEye = interpolatePoint(prevDet.leftEye, nextDet.leftEye, t),
                        rightEye = interpolatePoint(prevDet.rightEye, nextDet.rightEye, t),
                        eyeBar = interpolateRect(prevDet.eyeBar, nextDet.eyeBar, t)
                    )
                )
            }
        }
        return if (matched == 0) emptyList() else results
    }

    fun applyDetections(
        bitmap: Bitmap,
        detections: List<YoloOnnxRunner.Detection>,
        defaultBlurMode: Int,
        labelEffectOverrides: Map<String, Int>,
        reverseLabels: Set<String>,
        stickerProvider: (String?) -> Bitmap?
    ): Bitmap {
        return renderEngine.applyDetections(
            sourceBitmap = bitmap,
            detections = detections.map { detection ->
                DetectionRenderEngine.DetectionRenderItem(
                    className = detection.className,
                    rect = Rect(
                        detection.box.left.toInt(),
                        detection.box.top.toInt(),
                        detection.box.right.toInt(),
                        detection.box.bottom.toInt()
                    ),
                    leftEye = detection.leftEye,
                    rightEye = detection.rightEye
                )
            },
            settings = DetectionRenderEngine.RenderSettings(
                defaultBlurMode = defaultBlurMode,
                labelEffectOverrides = labelEffectOverrides,
                reverseLabels = reverseLabels,
                useCircularMask = privacySettings.isCircularMaskEnabled(),
                reversePreRenderEnabled = privacySettings.isReversePreRenderEnabled(),
                maskOutlineEnabled = privacySettings.isMaskOutlineEnabled(),
                maskOutlineLabels = privacySettings.getMaskOutlineLabels().toSet()
            ),
            stickerProvider = stickerProvider,
            normalEffectSourceProvider = { it },
            callbacks = DetectionRenderEngine.RenderCallbacks(
                onReverseModeMixed = { modeToUse ->
                    if (!reverseModeMixedLogged) {
                        DebugLogManager.addLog("视频处理", "反向遮挡存在多种效果，已使用默认效果: ${privacySettings.getBlurModeName(modeToUse)}")
                        reverseModeMixedLogged = true
                    }
                },
                onReverseStickerFallback = {
                    if (!reverseStickerFallbackLogged) {
                        DebugLogManager.addLog("视频处理", "反向遮挡贴纸加载失败，退回马赛克")
                        reverseStickerFallbackLogged = true
                    }
                }
            )
        )
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = kotlin.math.max(a.left, b.left)
        val interTop = kotlin.math.max(a.top, b.top)
        val interRight = kotlin.math.min(a.right, b.right)
        val interBottom = kotlin.math.min(a.bottom, b.bottom)
        val interWidth = kotlin.math.max(0f, interRight - interLeft)
        val interHeight = kotlin.math.max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight
        if (interArea <= 0f) return 0f
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    private fun interpolateBox(a: RectF, b: RectF, t: Float): RectF {
        return RectF(
            a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t,
            a.bottom + (b.bottom - a.bottom) * t
        )
    }

    private fun interpolateRect(a: RectF?, b: RectF?, t: Float): RectF? {
        return when {
            a != null && b != null -> interpolateBox(a, b, t)
            a != null && b == null -> if (t < 0.5f) a else null
            a == null && b != null -> if (t >= 0.5f) b else null
            else -> null
        }
    }

    private fun interpolatePoint(a: PointF?, b: PointF?, t: Float): PointF? {
        return when {
            a != null && b != null -> PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            a != null && b == null -> if (t < 0.5f) a else null
            a == null && b != null -> if (t >= 0.5f) b else null
            else -> null
        }
    }

}
