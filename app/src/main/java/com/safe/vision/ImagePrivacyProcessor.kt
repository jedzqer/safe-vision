package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
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
            for (i in 0 until detections.length()) {
                val detection = detections.getJSONObject(i)
                val className = detection.getString("class")
                if (privacySettings.isLabelBlocked(className, labelProfile)) {
                    val box = detection.getJSONArray("box")
                    val x = box.getInt(0)
                    val y = box.getInt(1)
                    val width = box.getInt(2)
                    val height = box.getInt(3)
                    val rect = BlurEffects.clampRect(Rect(x, y, x + width, y + height), originalBitmap.width, originalBitmap.height)
                    if (rect.width() > 0 && rect.height() > 0) {
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
                        renderItems.add(
                            DetectionRenderEngine.DetectionRenderItem(
                                className = className,
                                rect = rect,
                                leftEye = leftEye,
                                rightEye = rightEye
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
