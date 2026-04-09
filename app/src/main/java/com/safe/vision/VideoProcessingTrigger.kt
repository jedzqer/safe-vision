package com.safe.vision

import android.net.Uri
import kotlinx.coroutines.flow.first

class VideoProcessingTrigger(
    private val videoProcessingManager: VideoProcessingManager,
    private val privacySettingsManager: PrivacySettingsManager,
    private val appSettingsManager: AppSettingsManager
) {
    suspend fun processSequentially(
        videoUris: List<Uri>,
        onLog: (String) -> Unit
    ) {
        onLog("开始处理 ${videoUris.size} 个视频")
        for ((index, videoUri) in videoUris.withIndex()) {
            onLog("处理视频 ${index + 1}/${videoUris.size}")
            val options = VideoProcessingManager.VideoProcessingOptions(
                blockedLabels = privacySettingsManager.getBlockedLabels(),
                reverseLabels = privacySettingsManager.getReverseLabels(),
                blurMode = privacySettingsManager.getBlurMode(),
                labelEffectOverrides = privacySettingsManager.getLabelEffectOverrides(),
                skipStride = appSettingsManager.getVideoSkipStride(),
                highLoadMode = appSettingsManager.isVideoHighLoadEnabled()
            )
            videoProcessingManager.startProcessing(videoUri, options)
            videoProcessingManager.state.first { state ->
                state is VideoProcessingManager.VideoProcessingState.Completed ||
                    state is VideoProcessingManager.VideoProcessingState.Error ||
                    state is VideoProcessingManager.VideoProcessingState.Cancelled
            }
        }
    }
}
