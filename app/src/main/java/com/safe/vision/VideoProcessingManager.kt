package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 视频处理管理器，负责接收视频任务并在后台线程中处理/导出。
 */
class VideoProcessingManager private constructor(private val context: Context) {
    private val sessionIdFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    private val _state = MutableStateFlow<VideoProcessingState>(VideoProcessingState.Idle)
    val state: StateFlow<VideoProcessingState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(VideoProgress(0, 0, 0))
    val progress: StateFlow<VideoProgress> = _progress.asStateFlow()

    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var yoloRunner: YoloOnnxRunner? = null
    private var isModelLoaded = false
    private var loadedModelVariant: DetectionModelVariant? = null
    private var modelVariantLabel: String = "unknown"
    private val privacySettings = PrivacySettingsManager.getInstance(context)
    private val detectionProcessor = VideoDetectionProcessor(privacySettings)

    private data class EncoderProfile(
        val frameRate: Int,
        val bitrate: Int
    )

    private data class DecodedSegment(
        val segmentIndex: Int,
        val detectionIndex: Int,
        val frames: List<DecodedVideoFrame>
    ) {
        val isEvenSegment: Boolean
            get() = segmentIndex % 2 == 0
    }

    private data class PreparedFrame(
        val frame: DecodedVideoFrame,
        val detections: List<YoloOnnxRunner.Detection>
    )

    private data class PreparedSegment(
        val frames: List<PreparedFrame>
    )

    private data class RenderedFrame(
        val index: Int,
        val bitmap: Bitmap,
        val presentationTimeUs: Long
    )

    private data class PipelineProfile(
        val label: String,
        val decodeWorkers: Int,
        val detectWorkers: Int,
        val renderWorkers: Int,
        val encodeWorkers: Int,
        val decodeQueueCapacity: Int,
        val detectQueueCapacity: Int,
        val renderQueueCapacity: Int
    )

    companion object {
        @Volatile
        private var INSTANCE: VideoProcessingManager? = null

        fun getInstance(context: Context): VideoProcessingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VideoProcessingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
        * 启动视频处理任务
        */
    fun startProcessing(uri: Uri, options: VideoProcessingOptions) {
        cancel()
        detectionProcessor.resetSessionFlags()

        processingJob = processingScope.launch {
            val sessionId = LocalDateTime.now().format(sessionIdFormatter)
            val sessionTag = "[SESSION $sessionId]"
            try {
                _state.value = VideoProcessingState.Initializing
                _progress.value = VideoProgress(0, 0, 0)

                val displayName = queryDisplayName(uri) ?: "video"
                DebugLogManager.addLog(
                    "视频处理",
                    "$sessionTag 开始处理视频: $displayName, uri=$uri"
                )
                DebugLogManager.addLog(
                    "视频处理",
                    "$sessionTag 当前遮挡标签: ${options.blockedLabels.joinToString()}, 反向标签: " +
                        (if (options.reverseLabels.isEmpty()) "无" else options.reverseLabels.joinToString()) +
                        ", 默认效果: ${privacySettings.getBlurModeName(options.blurMode)}, 标签覆盖: " +
                        if (options.labelEffectOverrides.isEmpty()) {
                            "无"
                        } else {
                            options.labelEffectOverrides.entries.joinToString {
                                "${it.key}=${privacySettings.getBlurModeName(it.value)}"
                            }
                        }
                )

                _state.value = VideoProcessingState.LoadingModel
                ensureModelLoaded()

                val effectiveOptions = options.copy(
                    skipStride = options.skipStride.coerceAtLeast(DetectionConfig.VideoProcessing.MIN_SKIP_STRIDE)
                )
                if (effectiveOptions.skipStride != options.skipStride) {
                    DebugLogManager.addLog(
                        "视频处理",
                        "$sessionTag 用户选择跳帧=${options.skipStride}，为性能自动调整为每${effectiveOptions.skipStride}帧检测一次"
                    )
                }

                val (finalOutputFile, tempOutputFile) = prepareOutputFiles(displayName)
                _state.value = VideoProcessingState.Processing(displayName, tempOutputFile)
                DebugLogManager.addLog(
                    "视频处理",
                    "$sessionTag 跳帧设置: 每${effectiveOptions.skipStride.coerceAtLeast(1)}帧检测一次"
                )

                processVideo(sessionId, uri, finalOutputFile, tempOutputFile, effectiveOptions)

                _state.value = VideoProcessingState.Completed(finalOutputFile)
                DebugLogManager.addLog(
                    "视频处理",
                    "$sessionTag 视频处理完成，输出: ${finalOutputFile.name}"
                )
            } catch (_: CancellationException) {
                DebugLogManager.addLog("视频处理", "$sessionTag 任务已取消")
                _state.value = VideoProcessingState.Cancelled
            } catch (e: Exception) {
                val userMessage = when (e.message) {
                    "未找到视频轨道",
                    "视频格式未知" -> "不支持的视频格式"
                    else -> e.message ?: "未知错误"
                }
                DebugLogManager.addLog("视频处理", "[SESSION $sessionId] 处理失败: ${e.message}")
                DebugLogManager.addLog("视频处理", "[SESSION $sessionId] 异常堆栈: ${e.stackTraceToString()}")
                _state.value = VideoProcessingState.Error(userMessage)
            } finally {
                processingJob = null
            }
        }
    }

    fun cancel() {
        val job = processingJob ?: return
        if (!job.isActive) {
            processingJob = null
            return
        }
        job.cancel()
        DebugLogManager.addLog("视频处理", "视频处理已取消")
    }

    private suspend fun ensureModelLoaded() {
        val targetVariant = AppSettingsManager.getInstance(context).getDetectionModelVariant()
        if (isModelLoaded && yoloRunner != null && loadedModelVariant == targetVariant) return
        DebugLogManager.addLog("视频处理", "正在加载检测模型 (${targetVariant.runtimeLabel})")
        val runner = withContext(Dispatchers.IO) {
            YoloModelProvider.getRunner(context, targetVariant)
        }
        yoloRunner = runner
        isModelLoaded = true
        loadedModelVariant = targetVariant
        modelVariantLabel = targetVariant.runtimeLabel
        DebugLogManager.addLog("视频处理", "YOLO 模型加载完成 (${targetVariant.runtimeLabel})")
    }

    private suspend fun processVideo(sessionId: String, uri: Uri, outputFile: File, tempOutputFile: File, options: VideoProcessingOptions) {
        withContext(Dispatchers.IO) {
            val sessionTag = "[SESSION $sessionId]"
            val startAt = System.currentTimeMillis()
            logInputVideoInfo(sessionTag, uri)
            val frameDecoder = SafeVideoFrameDecoder(context, uri)
            val sourceWidth = frameDecoder.width
            val sourceHeight = frameDecoder.height
            val requestedWidth = VideoCodecUtils.alignDimensionToEncoder(sourceWidth)
            val requestedHeight = VideoCodecUtils.alignDimensionToEncoder(sourceHeight)
            val frameRate = frameDecoder.frameRate
            val totalFramesEstimate = frameDecoder.totalFrames
            val durationMs = frameDecoder.durationUs / 1000
            _progress.value = VideoProgress(0, totalFramesEstimate.toLong(), 0)
            val fpsText = "%.2f".format(Locale.US, frameRate)
            DebugLogManager.addLog(
                "视频处理",
                "$sessionTag [VIDEO_CFG] 输入 ${sourceWidth}x${sourceHeight} -> 请求编码 ${requestedWidth}x${requestedHeight} @$fpsText fps duration=${durationMs}ms frames=${if (totalFramesEstimate > 0) totalFramesEstimate else "unknown"} " +
                        "skip=${options.skipStride} model=$modelVariantLabel blurMode=${options.blurMode} overrides=${options.labelEffectOverrides.size}"
            )

            val bitrateByFormula = (
                requestedWidth * requestedHeight * frameRate * DetectionConfig.VideoProcessing.TARGET_BITRATE_FACTOR
            ).roundToInt()
            val initialProfile = EncoderProfile(
                frameRate = frameRate.roundToInt().coerceIn(1, DetectionConfig.VideoProcessing.MAX_PRIMARY_FRAME_RATE),
                bitrate = bitrateByFormula.coerceIn(2_000_000, 20_000_000)
            )
            var videoEncoder: MediaCodec? = null
            var encoderInputSurface: android.view.Surface? = null
            var configuredProfile: EncoderProfile? = null
            var width = requestedWidth
            var height = requestedHeight
            val fallbackProfiles = listOf(
                initialProfile,
                initialProfile.copy(
                    frameRate = minOf(initialProfile.frameRate, DetectionConfig.VideoProcessing.FALLBACK_FRAME_RATE_HIGH),
                    bitrate = maxOf(
                        (initialProfile.bitrate * DetectionConfig.VideoProcessing.FALLBACK_BITRATE_RATIO_HIGH).roundToInt(),
                        DetectionConfig.VideoProcessing.FALLBACK_MIN_BITRATE_HIGH
                    )
                ),
                initialProfile.copy(
                    frameRate = minOf(initialProfile.frameRate, DetectionConfig.VideoProcessing.FALLBACK_FRAME_RATE_LOW),
                    bitrate = maxOf(
                        (initialProfile.bitrate * DetectionConfig.VideoProcessing.FALLBACK_BITRATE_RATIO_LOW).roundToInt(),
                        DetectionConfig.VideoProcessing.FALLBACK_MIN_BITRATE_LOW
                    )
                )
            )
            var lastConfigureError: Exception? = null
            for ((attemptIndex, candidate) in fallbackProfiles.withIndex()) {
                val attemptNo = attemptIndex + 1
                val probeEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val codecInfo = probeEncoder.codecInfo
                val caps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    ?: throw IllegalStateException("编码器不支持视频能力查询: ${codecInfo.name}")
                val (resolvedWidth, resolvedHeight) = VideoCodecUtils.resolveSupportedSize(
                    capabilities = caps,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight
                )
                val fpsRange = runCatching { caps.getSupportedFrameRatesFor(resolvedWidth, resolvedHeight) }.getOrNull()
                val clampedFps = fpsRange?.let { candidate.frameRate.toDouble().coerceIn(it.lower, it.upper).roundToInt() }
                    ?: candidate.frameRate
                val clampedBitrate = candidate.bitrate.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
                val profile = EncoderProfile(
                    frameRate = clampedFps.coerceAtLeast(1),
                    bitrate = clampedBitrate.coerceAtLeast(500_000)
                )
                runCatching { probeEncoder.release() }
                    .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [ENCODER] probeEncoder 释放失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, resolvedWidth, resolvedHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, profile.frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                DebugLogManager.addLog(
                    "视频处理",
                    "$sessionTag [ENCODER] 尝试#$attemptNo codec=${codecInfo.name} size=${resolvedWidth}x${resolvedHeight} color=${VideoCodecUtils.colorFormatToString(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)} bitrate=${profile.bitrate} fps=${profile.frameRate} gop=1"
                )
                try {
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoderInputSurface = encoder.createInputSurface()
                    encoder.start()
                    videoEncoder = encoder
                    configuredProfile = profile
                    width = resolvedWidth
                    height = resolvedHeight
                    break
                } catch (e: Exception) {
                    lastConfigureError = e
                    DebugLogManager.addLog(
                        "视频处理",
                        "$sessionTag [ENCODER] 尝试#$attemptNo configure失败: ${e.message ?: e.javaClass.simpleName}"
                    )
                    runCatching { encoderInputSurface?.release() }
                        .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [ENCODER] encoderInputSurface 释放失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                    encoderInputSurface = null
                    runCatching { encoder.release() }
                        .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [ENCODER] encoder 释放失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                }
                if (videoEncoder != null) break
            }
            val activeEncoder = videoEncoder ?: throw IllegalStateException(
                "视频编码器配置失败: ${lastConfigureError?.message ?: "无可用配置"}"
            )
            val activeInputSurface = encoderInputSurface
                ?: throw IllegalStateException("编码输入 Surface 创建失败")
            val activeProfile = configuredProfile ?: initialProfile
            DebugLogManager.addLog(
                "视频处理",
                "$sessionTag [ENCODER] 已启用 size=${width}x${height}, color=${VideoCodecUtils.colorFormatToString(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)}, bitrate=${activeProfile.bitrate}, fps=${activeProfile.frameRate}, gop=1"
            )

            val muxer = android.media.MediaMuxer(tempOutputFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val audioTrackIndex = VideoCodecUtils.addAudioTrackIfPresent(context, muxer, uri)
            var videoTrackIndex = -1
            var muxerStarted = false
            var muxerStopped = false
            var muxerReleased = false
            var encoderStopped = false
            var encoderReleased = false
            var audioCopied = false
            var completedSuccessfully = false
            DebugLogManager.addLog(
                "视频处理",
                "$sessionTag [MUXER] tempFile=${tempOutputFile.name} audioTrack=${if (audioTrackIndex >= 0) "present" else "absent"}"
            )

            val bufferInfo = MediaCodec.BufferInfo()
            val codecContext = "codec=${activeEncoder.codecInfo.name}, size=${width}x${height}, color=${VideoCodecUtils.colorFormatToString(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)}, bitrate=${activeProfile.bitrate}, fps=${activeProfile.frameRate}"

            fun codecStateFailure(step: String, cause: IllegalStateException): IllegalStateException {
                return IllegalStateException("视频编码器在${step}时进入非法状态 ($codecContext)", cause)
            }

            fun dequeueOutputBufferSafe(): Int {
                return try {
                    activeEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: IllegalStateException) {
                    throw codecStateFailure("dequeueOutputBuffer", e)
                }
            }

            fun releaseOutputBufferSafe(outputIndex: Int) {
                try {
                    activeEncoder.releaseOutputBuffer(outputIndex, false)
                } catch (e: IllegalStateException) {
                    throw codecStateFailure("releaseOutputBuffer", e)
                }
            }

            suspend fun drainEncoder(endOfStream: Boolean = false) {
                currentCoroutineContext().ensureActive()
                var eosQueued = !endOfStream
                while (true) {
                    if (endOfStream && !eosQueued) {
                        activeEncoder.signalEndOfInputStream()
                        eosQueued = true
                        DebugLogManager.addLog("视频处理", "$sessionTag [ENCODER] 已提交EOS")
                    }
                    currentCoroutineContext().ensureActive()
                    val outputIndex = dequeueOutputBufferSafe()
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (eosQueued) continue
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = activeEncoder.outputFormat
                            videoTrackIndex = muxer.addTrack(newFormat)
                            if (!muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outputIndex >= 0 -> {
                            if (bufferInfo.size > 0 && muxerStarted && videoTrackIndex >= 0) {
                                val encodedData = activeEncoder.getOutputBuffer(outputIndex)
                                    ?: throw IllegalStateException("编码缓冲区为空")
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            releaseOutputBufferSafe(outputIndex)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                return
                            }
                        }
                    }
                }
            }

            val runner = yoloRunner ?: throw IllegalStateException("模型未初始化")
            val blocked = options.blockedLabels.toSet()
            val reverseLabels = options.reverseLabels.toSet()
            val needsSticker = options.blurMode == PrivacySettingsManager.BLUR_MODE_STICKER ||
                options.labelEffectOverrides.values.contains(PrivacySettingsManager.BLUR_MODE_STICKER)
            val stickerProvider: (String?) -> android.graphics.Bitmap? = if (needsSticker) {
                { label ->
                    StickerLoader.loadSticker(context, privacySettings, label).also {
                        if (it == null) {
                            DebugLogManager.addLog("视频处理", "贴纸加载失败，将退回马赛克")
                        }
                    }
                }
            } else {
                { null }
            }
            val skipStride = options.skipStride.coerceAtLeast(1)
            val runnerInstance = runner
            val enrichFaceLandmarks = detectionProcessor.shouldEnrichFaceLandmarks(
                blockedLabels = options.blockedLabels,
                defaultBlurMode = options.blurMode,
                labelEffectOverrides = options.labelEffectOverrides
            )
            val pipelineProfile = pipelineProfileFor(options)
            val detectionRunCount = AtomicLong(0L)
            val detectionHitCount = AtomicLong(0L)
            val encodedFrameCount = AtomicLong(0L)
            val decodeChannel = Channel<DecodedSegment>(capacity = pipelineProfile.decodeQueueCapacity)
            val preparedChannel = Channel<PreparedSegment>(capacity = pipelineProfile.detectQueueCapacity)
            val renderChannel = Channel<RenderedFrame>(capacity = pipelineProfile.renderQueueCapacity)
            val decodeDispatcher = newStageDispatcher("video-decode", pipelineProfile.decodeWorkers)
            val detectDispatcher = newStageDispatcher("video-detect", pipelineProfile.detectWorkers)
            val renderDispatcher = newStageDispatcher("video-render", pipelineProfile.renderWorkers)
            val encodeDispatcher = newStageDispatcher("video-encode", pipelineProfile.encodeWorkers)
            DebugLogManager.addLog(
                "视频处理",
                "$sessionTag [PIPELINE] mode=${pipelineProfile.label} decode=${pipelineProfile.decodeWorkers} detect=${pipelineProfile.detectWorkers} render=${pipelineProfile.renderWorkers} encode=${pipelineProfile.encodeWorkers} queue=${pipelineProfile.decodeQueueCapacity}/${pipelineProfile.detectQueueCapacity}/${pipelineProfile.renderQueueCapacity}"
            )

            try {
                coroutineScope {
                    val decodeJob = launch(decodeDispatcher) {
                        decodeSegments(frameDecoder, skipStride, decodeChannel)
                    }
                    val detectJob = launch(detectDispatcher) {
                        detectSegments(
                            input = decodeChannel,
                            output = preparedChannel,
                            runner = runnerInstance,
                            blocked = blocked,
                            enrichFaceLandmarks = enrichFaceLandmarks,
                            detectionRunCount = detectionRunCount,
                            detectionHitCount = detectionHitCount
                        )
                    }
                    val renderJobs = List(pipelineProfile.renderWorkers) { workerIndex ->
                        launch(renderDispatcher) {
                            renderSegments(
                                input = preparedChannel,
                                output = renderChannel,
                                options = options,
                                reverseLabels = reverseLabels,
                                stickerProvider = stickerProvider,
                                targetWidth = width,
                                targetHeight = height,
                                workerLabel = workerIndex + 1
                            )
                        }
                    }
                    val renderCloserJob = launch {
                        renderJobs.forEach { it.join() }
                        renderChannel.close()
                    }
                    val encodeJob = launch(encodeDispatcher) {
                        VideoCodecUtils.SurfaceInputWriter(activeInputSurface).use { encoderInputWriter ->
                            var fallbackTotalFramesEstimate = totalFramesEstimate.toLong().coerceAtLeast(0L)
                            var lastReportedPercentage = 0
                            var nextFrameIndex = 0
                            val pendingFrames = HashMap<Int, RenderedFrame>()
                            try {

                            suspend fun encodeFrame(frame: RenderedFrame) {
                                try {
                                    while (true) {
                                        val queued = try {
                                            encoderInputWriter.renderBitmap(
                                                frame.bitmap,
                                                frame.presentationTimeUs
                                            )
                                            true
                                        } catch (e: IllegalStateException) {
                                            throw codecStateFailure("queueFrame pts=${frame.presentationTimeUs}", e)
                                        }
                                        if (queued) break
                                        currentCoroutineContext().ensureActive()
                                        drainEncoder()
                                    }
                                    val processedFrames = frame.index.toLong() + 1
                                    encodedFrameCount.set(processedFrames)
                                    val totalFramesForUi = when {
                                        totalFramesEstimate > 0 -> maxOf(totalFramesEstimate.toLong(), processedFrames)
                                        fallbackTotalFramesEstimate > 0L -> fallbackTotalFramesEstimate
                                        else -> maxOf(processedFrames * 2, 60L)
                                    }
                                    if (totalFramesEstimate <= 0 && processedFrames >= totalFramesForUi) {
                                        fallbackTotalFramesEstimate = totalFramesForUi * 2
                                    } else if (totalFramesEstimate <= 0) {
                                        fallbackTotalFramesEstimate = totalFramesForUi
                                    }
                                    val rawPercentage = when {
                                        totalFramesEstimate > 0 -> {
                                            ((processedFrames * 100) / totalFramesEstimate.toLong()).toInt().coerceAtMost(99)
                                        }
                                        durationMs > 0 -> {
                                            ((frame.presentationTimeUs / 1000L) * 100 / durationMs).toInt().coerceIn(1, 99)
                                        }
                                        else -> {
                                            ((processedFrames * 100) / totalFramesForUi).toInt().coerceIn(1, 99)
                                        }
                                    }
                                    val percentage = maxOf(lastReportedPercentage, rawPercentage).coerceIn(0, 99)
                                    lastReportedPercentage = percentage
                                    _progress.value = VideoProgress(
                                        processedFrames = processedFrames,
                                        totalFrames = totalFramesForUi,
                                        percentage = percentage
                                    )
                                    drainEncoder()
                                } finally {
                                    if (!frame.bitmap.isRecycled) {
                                        frame.bitmap.recycle()
                                    }
                                }
                            }

                            for (frame in renderChannel) {
                                pendingFrames[frame.index] = frame
                                while (true) {
                                    val nextFrame = pendingFrames.remove(nextFrameIndex) ?: break
                                    encodeFrame(nextFrame)
                                    nextFrameIndex++
                                }
                            }
                            while (true) {
                                val nextFrame = pendingFrames.remove(nextFrameIndex) ?: break
                                encodeFrame(nextFrame)
                                nextFrameIndex++
                            }
                            drainEncoder(endOfStream = true)
                            } finally {
                                pendingFrames.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                                pendingFrames.clear()
                            }
                        }
                    }
                    val allJobs = mutableListOf(decodeJob, detectJob, renderCloserJob, encodeJob)
                    allJobs.addAll(renderJobs)
                    joinAll(*allJobs.toTypedArray())
                }
                currentCoroutineContext().ensureActive()
                if (audioTrackIndex != -1 && muxerStarted && !audioCopied) {
                    DebugLogManager.addLog("视频处理", "$sessionTag [MUXER] 开始复制音轨")
                    VideoCodecUtils.copyAudioToMuxer(context, muxer, audioTrackIndex, uri)
                    audioCopied = true
                }
                _progress.value = _progress.value.copy(percentage = 99)
                if (muxerStarted && !muxerStopped) {
                    muxer.stop()
                    muxerStopped = true
                }
                if (!muxerReleased) {
                    muxer.release()
                    muxerReleased = true
                }
                if (!encoderStopped) {
                    activeEncoder.stop()
                    encoderStopped = true
                }
                if (!encoderReleased) {
                    activeEncoder.release()
                    encoderReleased = true
                }
                frameDecoder.close()

                if (!tempOutputFile.renameTo(outputFile)) {
                    throw IllegalStateException("输出文件重命名失败")
                }
                completedSuccessfully = true
                val durationCostMs = System.currentTimeMillis() - startAt
                DebugLogManager.addLog(
                    "视频处理",
                    "$sessionTag [SUMMARY] frames=${encodedFrameCount.get()} processed in ${durationCostMs}ms, detRuns=${detectionRunCount.get()}, detHits=${detectionHitCount.get()} -> ${outputFile.name}"
                )
            } finally {
                closeStageDispatchers(
                    decodeDispatcher,
                    detectDispatcher,
                    renderDispatcher,
                    encodeDispatcher
                )
                // 保障异常场景释放资源
                runCatching { frameDecoder.close() }
                    .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [CLEANUP] frameDecoder 关闭失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                if (!encoderStopped) {
                    runCatching { activeEncoder.stop() }
                        .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [CLEANUP] encoder stop 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                    encoderStopped = true
                }
                if (!encoderReleased) {
                    runCatching { activeEncoder.release() }
                        .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [CLEANUP] encoder release 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                    encoderReleased = true
                }
                if (muxerStarted && !muxerStopped) {
                    runCatching { muxer.stop() }
                        .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [CLEANUP] muxer stop 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                    muxerStopped = true
                }
                if (!muxerReleased) {
                    runCatching { muxer.release() }
                        .onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [CLEANUP] muxer release 失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                    muxerReleased = true
                }
                if (!completedSuccessfully) {
                    runCatching {
                        if (tempOutputFile.exists()) {
                            tempOutputFile.delete()
                        }
                    }.onFailure { e -> DebugLogManager.addLog("视频处理", "$sessionTag [CLEANUP] 删除临时文件失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
                }
            }
        }
    }

    private fun pipelineProfileFor(options: VideoProcessingOptions): PipelineProfile {
        return if (options.highLoadMode) {
            PipelineProfile(
                label = "high-load",
                decodeWorkers = 1,
                detectWorkers = 1,
                renderWorkers = 2,
                encodeWorkers = 1,
                decodeQueueCapacity = 4,
                detectQueueCapacity = 4,
                renderQueueCapacity = 8
            )
        } else {
            PipelineProfile(
                label = "balanced",
                decodeWorkers = 1,
                detectWorkers = 1,
                renderWorkers = 1,
                encodeWorkers = 1,
                decodeQueueCapacity = 2,
                detectQueueCapacity = 2,
                renderQueueCapacity = 4
            )
        }
    }

    private fun newStageDispatcher(name: String, threads: Int): ExecutorCoroutineDispatcher {
        val executor = if (threads <= 1) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "safe-vision-$name").apply {
                    isDaemon = true
                }
            }
        } else {
            Executors.newFixedThreadPool(threads) { runnable ->
                Thread(runnable, "safe-vision-$name").apply {
                    isDaemon = true
                }
            }
        }
        return executor.asCoroutineDispatcher()
    }

    private fun closeStageDispatchers(vararg dispatchers: ExecutorCoroutineDispatcher) {
        dispatchers.forEach { dispatcher ->
            runCatching { dispatcher.close() }
                .onFailure { e -> DebugLogManager.addLog("视频处理", "Dispatcher 关闭失败: ${e.message}", DebugLogManager.LogLevel.WARN) }
        }
    }

    private suspend fun decodeSegments(
        frameDecoder: SafeVideoFrameDecoder,
        skipStride: Int,
        output: Channel<DecodedSegment>
    ) {
        var frameIndex = 0
        var segmentIndex = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val detectionIndex = if (skipStride == 1) frameIndex else frameIndex + skipStride / 2
                val frames = mutableListOf<DecodedVideoFrame>()
                try {
                    for (offset in 0 until skipStride) {
                        currentCoroutineContext().ensureActive()
                        val nextFrame = frameDecoder.nextFrame(frameIndex + offset) ?: break
                        frames.add(nextFrame)
                    }
                    if (frames.isEmpty()) break
                    output.send(
                        DecodedSegment(
                            segmentIndex = segmentIndex,
                            detectionIndex = detectionIndex,
                            frames = frames
                        )
                    )
                    frameIndex += frames.size
                    segmentIndex++
                } catch (t: Throwable) {
                    frames.forEach { frame ->
                        if (!frame.bitmap.isRecycled) {
                            frame.bitmap.recycle()
                        }
                    }
                    throw t
                }
            }
        } finally {
            output.close()
        }
    }

    private suspend fun detectSegments(
        input: Channel<DecodedSegment>,
        output: Channel<PreparedSegment>,
        runner: YoloOnnxRunner,
        blocked: Set<String>,
        enrichFaceLandmarks: Boolean,
        detectionRunCount: AtomicLong,
        detectionHitCount: AtomicLong
    ) {
        var lastEvenDetection: DetectionFrame? = null
        try {
            for (segment in input) {
                currentCoroutineContext().ensureActive()
                try {
                    val detectionFrameBitmap = segment.frames
                        .firstOrNull { it.index == segment.detectionIndex }
                        ?.bitmap
                        ?: segment.frames.last().bitmap
                    val detections = runner.run(detectionFrameBitmap, enrichFaceLandmarks)
                        .filter { detection -> blocked.contains(detection.className) }
                    detectionRunCount.incrementAndGet()
                    if (detections.isNotEmpty()) {
                        detectionHitCount.incrementAndGet()
                    }

                    val preparedFrames = if (segment.isEvenSegment) {
                        lastEvenDetection = DetectionFrame(segment.detectionIndex, detections)
                        segment.frames.map { frame ->
                            PreparedFrame(
                                frame = frame,
                                detections = lastEvenDetection?.detections ?: emptyList()
                            )
                        }
                    } else {
                        val oddDetection = DetectionFrame(segment.detectionIndex, detections)
                        val currentEven = lastEvenDetection
                        segment.frames.map { frame ->
                            val blended = if (currentEven != null) {
                                detectionProcessor.blendDetections(currentEven, oddDetection, frame.index)
                            } else {
                                emptyList()
                            }
                            PreparedFrame(
                                frame = frame,
                                detections = if (blended.isEmpty()) oddDetection.detections else blended
                            )
                        }
                    }

                    output.send(PreparedSegment(preparedFrames))
                } catch (t: Throwable) {
                    segment.frames.forEach { frame ->
                        if (!frame.bitmap.isRecycled) {
                            frame.bitmap.recycle()
                        }
                    }
                    throw t
                }
            }
        } finally {
            output.close()
        }
    }

    private suspend fun renderSegments(
        input: Channel<PreparedSegment>,
        output: Channel<RenderedFrame>,
        options: VideoProcessingOptions,
        reverseLabels: Set<String>,
        stickerProvider: (String?) -> Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        workerLabel: Int
    ) {
        DebugLogManager.addLog("视频处理", "渲染 worker#$workerLabel 已启动")
        for (segment in input) {
            currentCoroutineContext().ensureActive()
            for (preparedFrame in segment.frames) {
                val sourceBitmap = preparedFrame.frame.bitmap
                var ownedBitmap: Bitmap? = null
                try {
                    val processedBitmap = detectionProcessor.applyDetections(
                        sourceBitmap,
                        preparedFrame.detections,
                        options.blurMode,
                        options.labelEffectOverrides,
                        reverseLabels,
                        stickerProvider
                    )
                    val encoderBitmap = ensureEncoderBitmapSize(processedBitmap, targetWidth, targetHeight)
                    if (encoderBitmap !== processedBitmap && processedBitmap !== sourceBitmap && !processedBitmap.isRecycled) {
                        processedBitmap.recycle()
                    }
                    if (encoderBitmap !== sourceBitmap && !sourceBitmap.isRecycled) {
                        sourceBitmap.recycle()
                    }
                    ownedBitmap = encoderBitmap
                    output.send(
                        RenderedFrame(
                            index = preparedFrame.frame.index,
                            bitmap = encoderBitmap,
                            presentationTimeUs = preparedFrame.frame.presentationTimeUs
                        )
                    )
                    ownedBitmap = null
                } catch (t: Throwable) {
                    if (ownedBitmap != null && !ownedBitmap.isRecycled) {
                        ownedBitmap.recycle()
                    }
                    if (!sourceBitmap.isRecycled) {
                        sourceBitmap.recycle()
                    }
                    throw t
                }
            }
        }
    }

    private fun ensureEncoderBitmapSize(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun logInputVideoInfo(sessionTag: String, uri: Uri) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "unknown"
        DebugLogManager.addLog("视频处理", "$sessionTag [INPUT] contentResolver mime=$mime uri=$uri")
        runCatching {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackMimes = (0 until extractor.trackCount).mapNotNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            }
            DebugLogManager.addLog("视频处理", "$sessionTag [INPUT] tracks=${trackMimes.joinToString()}")
            extractor.release()
        }.onFailure {
            DebugLogManager.addLog("视频处理", "$sessionTag [INPUT] 读取轨道信息失败: ${it.message}")
        }
    }

    private fun prepareOutputFiles(originalName: String): Pair<File, File> {
        val rootDir = context.getExternalFilesDir(null) ?: context.filesDir
        val outputDir = File(rootDir, "SafeVideo").apply { if (!exists()) mkdirs() }
        val baseName = buildBaseFileName(originalName)
        val resolvedBaseName = resolveAvailableBaseName(outputDir, baseName)
        val finalFile = File(outputDir, "$resolvedBaseName.mp4")
        val tempFile = File(outputDir, "$resolvedBaseName.partial")
        return finalFile to tempFile
    }

    private fun buildBaseFileName(originalName: String): String {
        return originalName
            .substringBeforeLast('.')
            .trim()
            .replace('/', '_')
            .replace('\\', '_')
            .takeIf { it.isNotBlank() }
            ?: "video"
    }

    private fun resolveAvailableBaseName(outputDir: File, baseName: String): String {
        var candidate = baseName
        var index = 1
        while (true) {
            val mp4Exists = File(outputDir, "$candidate.mp4").exists()
            val partialExists = File(outputDir, "$candidate.partial").exists()
            if (!mp4Exists && !partialExists) return candidate
            candidate = "${baseName}_$index"
            index++
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }

    private fun querySize(uri: Uri): Long {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: run {
            context.contentResolver.openInputStream(uri)?.use(java.io.InputStream::available)?.toLong() ?: 0L
        }
    }

    data class VideoProcessingOptions(
        val blockedLabels: List<String>,
        val reverseLabels: List<String>,
        val blurMode: Int,
        val labelEffectOverrides: Map<String, Int>,
        val skipStride: Int,
        val highLoadMode: Boolean
    )

    data class VideoProgress(
        val processedFrames: Long,
        val totalFrames: Long,
        val percentage: Int
    )

    sealed class VideoProcessingState {
        object Idle : VideoProcessingState()
        object Initializing : VideoProcessingState()
        object LoadingModel : VideoProcessingState()
        data class Processing(val displayName: String, val outputFile: File) : VideoProcessingState()
        data class Completed(val outputFile: File) : VideoProcessingState()
        data class Error(val message: String) : VideoProcessingState()
        object Cancelled : VideoProcessingState()
    }

}
