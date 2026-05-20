package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import java.io.File

/**
 * 图片隐私处理器，用于对图片中的敏感区域进行遮挡处理
 */
class ImagePrivacyProcessor {
    private val privacySettings: PrivacySettingsManager
    private val renderEngine: DetectionRenderEngine
    private val context: Context
    
    constructor(context: Context) {
        this.context = context.applicationContext
        privacySettings = PrivacySettingsManager.getInstance(this.context)
        renderEngine = DetectionRenderEngine(privacySettings)
    }
    
    /**
     * 对图片应用隐私遮挡
     * @param originalBitmap 原始图片
     * @param metadataFile 对应的元数据文件，可为空
     * @return 处理后的图片
     */
    fun applyPrivacyBlur(originalBitmap: Bitmap, metadataFile: File?): Bitmap {
        if (metadataFile == null || !metadataFile.exists()) {
            return originalBitmap
        }
        
        try {
            val metadata = metadataFile.readText()
            val metadataDoc = DetectionMetadataFormat.parse(metadata)
            val detections = metadataDoc.detections
            val labelProfile = metadataDoc.labelProfile
            val stickerCache = mutableMapOf<String?, Bitmap?>()

            fun getStickerBitmap(label: String?): Bitmap? {
                if (stickerCache.containsKey(label)) return stickerCache[label]
                val loaded = StickerLoader.loadSticker(context, privacySettings, label)
                stickerCache[label] = loaded
                return loaded
            }

            val renderItems = mutableListOf<DetectionRenderEngine.DetectionRenderItem>()
            val rawRenderItems = mutableListOf<DetectionRenderEngine.DetectionRenderItem>()
            for (i in 0 until detections.length()) {
                val detection = detections.getJSONObject(i)
                val className = detection.getString("class")
                val box = detection.getJSONArray("box")
                val x = box.getInt(0)
                val y = box.getInt(1)
                val width = box.getInt(2)
                val height = box.getInt(3)
                val rect = BlurEffects.clampRect(Rect(x, y, x + width, y + height), originalBitmap.width, originalBitmap.height)
                if (rect.width() <= 0 || rect.height() <= 0) continue
                val boxRotationDegrees = if (detection.has("box_rotation")) {
                    detection.optDouble("box_rotation", 0.0).toFloat()
                } else if (detection.has("eye_bar_rotation") && detection.has("eye_bar")) {
                    detection.optDouble("eye_bar_rotation", 0.0).toFloat()
                } else {
                    null
                }
                val eyes = detection.optJSONArray("eyes")
                val leftEye = eyes?.optJSONArray(0)?.takeIf { it.length() >= 2 }?.let {
                    PointF(
                        it.optDouble(0, rect.exactCenterX().toDouble()).toFloat(),
                        it.optDouble(1, rect.exactCenterY().toDouble()).toFloat()
                    )
                }
                val rightEye = eyes?.optJSONArray(1)?.takeIf { it.length() >= 2 }?.let {
                    PointF(
                        it.optDouble(0, rect.exactCenterX().toDouble()).toFloat(),
                        it.optDouble(1, rect.exactCenterY().toDouble()).toFloat()
                    )
                }
                val eyeBar = detection.optJSONArray("eye_bar")?.takeIf { it.length() >= 4 }?.let {
                    val x = it.optDouble(0, rect.left.toDouble()).toFloat()
                    val y = it.optDouble(1, rect.top.toDouble()).toFloat()
                    val width = it.optDouble(2, rect.width().toDouble()).toFloat()
                    val height = it.optDouble(3, rect.height().toDouble()).toFloat()
                    if (width > 0f && height > 0f) {
                        RectF(x, y, x + width, y + height)
                    } else {
                        null
                    }
                }
                val eyeBarRotationDegrees = if (detection.has("eye_bar_rotation")) {
                    detection.optDouble("eye_bar_rotation", 0.0).toFloat()
                } else {
                    null
                }
                rawRenderItems.add(
                    DetectionRenderEngine.DetectionRenderItem(
                        className = className,
                        rect = rect,
                        boxRotationDegrees = boxRotationDegrees,
                        leftEye = leftEye,
                        rightEye = rightEye,
                        eyeBarRect = eyeBar,
                        eyeBarRotationDegrees = eyeBarRotationDegrees
                    )
                )
            }

            val hasExplicitEyeRegion = rawRenderItems.any { DetectionConfig.isEyeRegionLabel(it.className) }
            rawRenderItems.forEach { item ->
                if (privacySettings.isLabelBlocked(item.className, labelProfile)) {
                    renderItems.add(item)
                }
                if (!hasExplicitEyeRegion && DetectionConfig.canDeriveEyeRegion(item.className)) {
                    val derivedRect = EyeRegionHelper.deriveEyeRegion(
                        EyeRegionHelper.EyeRegionSource(
                            sourceLabel = item.className,
                            faceRect = RectF(item.rect),
                            boxRotationDegrees = item.boxRotationDegrees,
                            eyes = item.leftEye?.let { left -> item.rightEye?.let { right -> left to right } },
                            eyeBar = item.eyeBarRect,
                            eyeBarRotationDegrees = item.eyeBarRotationDegrees
                        ),
                        originalBitmap.width,
                        originalBitmap.height
                    )
                    if (derivedRect != null && privacySettings.isLabelBlocked(DetectionConfig.EYE_REGION_LABEL, labelProfile)) {
                        renderItems.add(
                            DetectionRenderEngine.DetectionRenderItem(
                                className = DetectionConfig.EYE_REGION_LABEL,
                                rect = Rect(
                                    derivedRect.left.toInt(),
                                    derivedRect.top.toInt(),
                                    derivedRect.right.toInt(),
                                    derivedRect.bottom.toInt()
                                ),
                                boxRotationDegrees = item.eyeBarRotationDegrees ?: item.boxRotationDegrees,
                                leftEye = item.leftEye,
                                rightEye = item.rightEye,
                                eyeBarRect = derivedRect,
                                eyeBarRotationDegrees = item.eyeBarRotationDegrees
                            )
                        )
                    }
                }
            }

            return renderEngine.applyDetections(
                sourceBitmap = originalBitmap,
                detections = renderItems,
                settings = DetectionRenderEngine.RenderSettings(
                    defaultBlurMode = privacySettings.getBlurMode(labelProfile),
                    labelEffectOverrides = privacySettings.getLabelEffectOverrides(labelProfile),
                    reverseLabels = privacySettings.getReverseLabels(labelProfile).toSet(),
                    useCircularMask = privacySettings.isCircularMaskEnabled(),
                    reversePreRenderEnabled = privacySettings.isReversePreRenderEnabled(),
                    maskOutlineEnabled = privacySettings.isMaskOutlineEnabled(),
                    maskOutlineLabels = privacySettings.getMaskOutlineLabels(labelProfile).toSet()
                ),
                stickerProvider = ::getStickerBitmap,
                normalEffectSourceProvider = { originalBitmap },
                callbacks = DetectionRenderEngine.RenderCallbacks(
                    onReverseModeMixed = { modeToUse ->
                        DebugLogManager.addLog(
                            "图片处理",
                            "反向遮挡存在多种效果，已使用默认效果: ${privacySettings.getBlurModeName(modeToUse)}"
                        )
                    },
                    onReverseStickerFallback = {
                        DebugLogManager.addLog("图片处理", "反向遮挡贴纸加载失败，退回马赛克")
                    },
                    onNormalStickerFallback = {
                        DebugLogManager.addLog("图片处理", "贴纸加载失败，退回马赛克")
                    }
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            DebugLogManager.addLog("图片处理", "应用隐私遮挡失败: ${e.message}")
            return originalBitmap
        }
    }

}
