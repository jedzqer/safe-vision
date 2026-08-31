package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Range
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object VideoCodecUtils {
    abstract class AudioTrackCopier : Closeable {
        abstract val trackIndex: Int
        abstract val audioMime: String
        abstract val audioSamples: Int

        abstract fun copySamplesUpTo(maxPresentationTimeUs: Long)
        abstract fun finish()

        companion object {
            private const val TRANSCODE_TIMEOUT_US = 10_000L
            private const val MAX_TRANSCODE_BYTES = 256L * 1024 * 1024
            private const val DEFAULT_AUDIO_SAMPLE_RATE = 44_100
            private const val DEFAULT_AUDIO_CHANNELS = 2
            private const val PCM_BYTES_PER_SAMPLE = 2
            private const val AUDIO_SAMPLE_BUFFER_BYTES = 1024 * 1024
            private const val MAX_EMPTY_AUDIO_SAMPLES_TO_SKIP = 16

            fun create(
                context: Context,
                muxer: android.media.MediaMuxer,
                uri: Uri
            ): AudioTrackCopier? {
                val extractor = MediaExtractor()
                var foundAudioTrack = false
                var foundReadableAudioTrack = false
                try {
                    extractor.setDataSource(context, uri, null)
                    val trackCount = extractor.trackCount
                    DebugLogManager.addLog(
                        "视频处理",
                        "[AUDIO] 输入共有 $trackCount 个轨道"
                    )
                    for (i in 0 until trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("audio/")) {
                            foundAudioTrack = true
                            extractor.selectTrack(i)
                            val firstSample = findFirstReadableSample(extractor, i)
                            if (firstSample == null) {
                                DebugLogManager.addLog(
                                    "视频处理",
                                    "[AUDIO] 音轨#$i mime=$mime 没有可读取的非空样本，已忽略",
                                    DebugLogManager.LogLevel.WARN
                                )
                                runCatching { extractor.unselectTrack(i) }
                                continue
                            }
                            foundReadableAudioTrack = true
                            DebugLogManager.addLog(
                                "视频处理",
                                "[AUDIO] 发现音轨#$i mime=$mime " +
                                    "sampleRate=${if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else "?"} " +
                                    "channels=${if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else "?"} " +
                                    "bitrate=${if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else "?"} " +
                                    "firstSampleTimeUs=${firstSample.presentationTimeUs} firstSampleBytes=${firstSample.size}"
                            )
                            // MediaMuxer.addTrack() 成功不代表该编码在 MP4 中可被稳定播放。
                            // 只直传 MP4 原生的 AAC，其他音频统一转 AAC。
                            if (canPassthroughToMp4(mime)) {
                                try {
                                    val trackIndex = muxer.addTrack(format)
                                    DebugLogManager.addLog("视频处理", "[AUDIO] AAC 音轨将直接混流")
                                    return PassthroughAudioCopier(extractor, muxer, trackIndex, mime)
                                } catch (e: Exception) {
                                    DebugLogManager.addLog(
                                        "视频处理",
                                        "[AUDIO] AAC 直传初始化失败: ${e.message}，尝试重新转码",
                                        DebugLogManager.LogLevel.WARN
                                    )
                                }
                            } else {
                                DebugLogManager.addLog(
                                    "视频处理",
                                    "[AUDIO] mime=$mime 不适合直传到 MP4，将转码为 AAC"
                                )
                            }
                            // 容器不接受原始音轨时，解码再重编码为 AAC，避免静默丢音
                            val transcoded = transcodeToAac(extractor, muxer, mime)
                            if (transcoded != null) {
                                extractor.release()
                                return transcoded
                            }
                            DebugLogManager.addLog(
                                "视频处理",
                                "[AUDIO] 转码为 AAC 失败 mime=$mime，尝试其他音轨",
                                DebugLogManager.LogLevel.WARN
                            )
                            runCatching { extractor.unselectTrack(i) }
                        }
                    }
                    extractor.release()
                    if (foundReadableAudioTrack) {
                        throw IllegalStateException("输入视频包含音轨，但无法保留或转码该音轨")
                    }
                    if (foundAudioTrack) {
                        DebugLogManager.addLog(
                            "视频处理",
                            "[AUDIO] 输入仅包含无可读样本的空音轨，将输出纯视频",
                            DebugLogManager.LogLevel.WARN
                        )
                        return null
                    }
                    DebugLogManager.addLog("视频处理", "[AUDIO] 未发现音轨，将输出纯视频")
                    return null
                } catch (e: Exception) {
                    DebugLogManager.addLog(
                        "视频处理",
                        "[AUDIO] 无法可靠保留输入音轨: ${e.message}",
                        DebugLogManager.LogLevel.WARN
                    )
                    runCatching { extractor.release() }
                    throw IllegalStateException("无法读取或保留输入视频的音轨", e)
                }
            }

            private fun canPassthroughToMp4(mime: String): Boolean {
                return mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
            }

            private data class AudioSampleProbe(
                val presentationTimeUs: Long,
                val size: Int
            )

            private fun findFirstReadableSample(
                extractor: MediaExtractor,
                trackIndex: Int
            ): AudioSampleProbe? {
                val probeBuffer = ByteBuffer.allocate(AUDIO_SAMPLE_BUFFER_BYTES)

                fun scanFromCurrentPosition(): AudioSampleProbe? {
                    repeat(MAX_EMPTY_AUDIO_SAMPLES_TO_SKIP) {
                        val sampleTimeUs = extractor.sampleTime
                        if (extractor.sampleTrackIndex != trackIndex || sampleTimeUs < 0L) {
                            return null
                        }
                        probeBuffer.clear()
                        val sampleSize = extractor.readSampleData(probeBuffer, 0)
                        if (sampleSize > 0) {
                            return AudioSampleProbe(sampleTimeUs, sampleSize)
                        }
                        if (!extractor.advance()) {
                            return null
                        }
                    }
                    return null
                }

                // A newly selected track is normally already positioned at its first sample.
                // Seeking is only a fallback because some extractors cannot seek audio tracks
                // whose samples do not carry sync flags.
                return scanFromCurrentPosition() ?: run {
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    scanFromCurrentPosition()
                }
            }

            // 解码原始音轨并重编码为 AAC，全部样本先缓冲后再返回，muxer.addTrack 使用编码器输出格式
            private fun transcodeToAac(
                extractor: MediaExtractor,
                muxer: android.media.MediaMuxer,
                sourceMime: String
            ): TranscodedAacCopier? {
                val sourceTrackIndex = extractor.sampleTrackIndex
                if (sourceTrackIndex < 0) {
                    DebugLogManager.addLog("视频处理", "[AUDIO] 音轨没有可读取样本", DebugLogManager.LogLevel.WARN)
                    return null
                }
                val sourceFormat = extractor.getTrackFormat(sourceTrackIndex)
                val sampleRate = if (sourceFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else DEFAULT_AUDIO_SAMPLE_RATE
                val channels = if (sourceFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else DEFAULT_AUDIO_CHANNELS
                val encoderFormat = MediaFormat().apply {
                    setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
                    setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                var decoder: MediaCodec? = null
                var encoder: MediaCodec? = null
                try {
                    val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .findDecoderForFormat(sourceFormat)
                        ?: throw IllegalStateException("无可用音频解码器")
                    decoder = MediaCodec.createByCodecName(decoderName)
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    decoder.configure(sourceFormat, null, null, 0)
                    encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    decoder.start()
                    encoder.start()
                } catch (e: Exception) {
                    DebugLogManager.addLog("视频处理", "[AUDIO] 编解码器初始化失败 mime=$sourceMime: ${e.message}", DebugLogManager.LogLevel.WARN)
                    runCatching { decoder?.release() }
                    runCatching { encoder?.release() }
                    return null
                }
                val activeDecoder = decoder
                val activeEncoder = encoder
                val decoderInfo = MediaCodec.BufferInfo()
                val encoderInfo = MediaCodec.BufferInfo()
                var decoderInputEOS = false
                var decoderOutputEOS = false
                var encoderInputEOS = false
                var encoderOutputEOS = false
                var pendingDecoderOutput = -1
                var pendingDecoderFlags = 0
                var pendingDecoderPtsUs = 0L
                var pendingDecoderBytes = 0
                val samples = ArrayList<TranscodedAacCopier.Sample>()
                var totalBytes = 0L
                var encodedAudioFormat: MediaFormat? = null
                var lastQueuedAudioEndPtsUs = 0L
                try {
                    while (!encoderOutputEOS) {
                        if (!decoderInputEOS) {
                            val inIndex = activeDecoder.dequeueInputBuffer(TRANSCODE_TIMEOUT_US)
                            if (inIndex >= 0) {
                                val inBuf = activeDecoder.getInputBuffer(inIndex) ?: continue
                                inBuf.clear()
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    activeDecoder.queueInputBuffer(
                                        inIndex,
                                        0,
                                        0,
                                        lastQueuedAudioEndPtsUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    decoderInputEOS = true
                                } else {
                                    activeDecoder.queueInputBuffer(
                                        inIndex,
                                        0,
                                        size,
                                        extractor.sampleTime,
                                        0
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        if (!decoderOutputEOS && pendingDecoderOutput < 0) {
                            val outIndex = activeDecoder.dequeueOutputBuffer(decoderInfo, TRANSCODE_TIMEOUT_US)
                            when {
                                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                                outIndex >= 0 -> {
                                    if (decoderInfo.size > 0) {
                                        pendingDecoderOutput = outIndex
                                        pendingDecoderFlags = decoderInfo.flags
                                        pendingDecoderPtsUs = decoderInfo.presentationTimeUs
                                        pendingDecoderBytes = 0
                                        activeDecoder.getOutputBuffer(outIndex)?.apply {
                                            position(decoderInfo.offset)
                                            limit(decoderInfo.offset + decoderInfo.size)
                                        } ?: throw IllegalStateException("音频解码器输出缓冲区为空")
                                    } else {
                                        activeDecoder.releaseOutputBuffer(outIndex, false)
                                        if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                            decoderOutputEOS = true
                                        }
                                    }
                                }
                            }
                        }

                        if (pendingDecoderOutput >= 0) {
                            val outBuf = activeDecoder.getOutputBuffer(pendingDecoderOutput)
                                ?: throw IllegalStateException("音频解码器输出缓冲区已失效")
                            outBuf.position(decoderInfo.offset + pendingDecoderBytes)
                            outBuf.limit(decoderInfo.offset + decoderInfo.size)
                            val inIndex = activeEncoder.dequeueInputBuffer(TRANSCODE_TIMEOUT_US)
                            if (inIndex >= 0) {
                                val inBuf = activeEncoder.getInputBuffer(inIndex)
                                    ?: throw IllegalStateException("音频编码器输入缓冲区为空")
                                inBuf.clear()
                                val chunkSize = minOf(outBuf.remaining(), inBuf.capacity())
                                val chunk = outBuf.duplicate().apply {
                                    limit(position() + chunkSize)
                                }
                                inBuf.put(chunk)
                                val bytesPerFrame = (channels * PCM_BYTES_PER_SAMPLE).coerceAtLeast(1)
                                val chunkPtsUs = pendingDecoderPtsUs +
                                    (pendingDecoderBytes.toLong() / bytesPerFrame) * 1_000_000L / sampleRate
                                activeEncoder.queueInputBuffer(inIndex, 0, chunkSize, chunkPtsUs, 0)
                                lastQueuedAudioEndPtsUs = chunkPtsUs +
                                    (chunkSize.toLong() / bytesPerFrame) * 1_000_000L / sampleRate
                                outBuf.position(outBuf.position() + chunkSize)
                                pendingDecoderBytes += chunkSize
                                if (!outBuf.hasRemaining()) {
                                    val reachedEos = pendingDecoderFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    activeDecoder.releaseOutputBuffer(pendingDecoderOutput, false)
                                    pendingDecoderOutput = -1
                                    if (reachedEos) {
                                        decoderOutputEOS = true
                                    }
                                }
                            }
                        }

                        if (decoderOutputEOS && pendingDecoderOutput < 0 && !encoderInputEOS) {
                            val inIndex = activeEncoder.dequeueInputBuffer(TRANSCODE_TIMEOUT_US)
                            if (inIndex >= 0) {
                                activeEncoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    lastQueuedAudioEndPtsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                encoderInputEOS = true
                            }
                        }

                        drainEncoder(activeEncoder, encoderInfo, samples, { fmt ->
                            if (encodedAudioFormat != null) {
                                throw IllegalStateException("音频编码器多次变更输出格式")
                            }
                            encodedAudioFormat = fmt
                        }, { totalBytes += it; totalBytes <= MAX_TRANSCODE_BYTES }, { encoderOutputEOS = true })
                        if (totalBytes > MAX_TRANSCODE_BYTES) {
                            DebugLogManager.addLog("视频处理", "[AUDIO] 转码 AAC 缓冲超过上限，放弃音轨", DebugLogManager.LogLevel.WARN)
                            return null
                        }
                    }
                } catch (e: Exception) {
                    DebugLogManager.addLog("视频处理", "[AUDIO] AAC 转码过程异常: ${e.message}", DebugLogManager.LogLevel.WARN)
                    return null
                } finally {
                    if (pendingDecoderOutput >= 0) {
                        runCatching { activeDecoder.releaseOutputBuffer(pendingDecoderOutput, false) }
                    }
                    runCatching { activeDecoder.stop() }
                    runCatching { activeDecoder.release() }
                    runCatching { activeEncoder.stop() }
                    runCatching { activeEncoder.release() }
                }
                val outputFormat = encodedAudioFormat
                if (outputFormat == null || samples.isEmpty()) {
                    DebugLogManager.addLog("视频处理", "[AUDIO] AAC 转码无可输出样本", DebugLogManager.LogLevel.WARN)
                    return null
                }
                val outputTrackIndex = try {
                    muxer.addTrack(outputFormat)
                } catch (e: Exception) {
                    DebugLogManager.addLog(
                        "视频处理",
                        "[AUDIO] 转码后的 AAC 音轨无法加入 MP4: ${e.message}",
                        DebugLogManager.LogLevel.WARN
                    )
                    return null
                }
                DebugLogManager.addLog("视频处理", "[AUDIO] 已转码为 AAC，共 ${samples.size} 个样本")
                return TranscodedAacCopier(muxer, outputTrackIndex, samples)
            }

            // 拉取编码器输出；onFormat 在编码器输出格式就绪时回调；
            // acceptSample 返回 false 时丢弃该样本（用于缓冲上限控制）；onEos 在收到 EOS 时回调
            private fun drainEncoder(
                encoder: MediaCodec,
                info: MediaCodec.BufferInfo,
                samples: ArrayList<TranscodedAacCopier.Sample>,
                onFormat: (MediaFormat) -> Unit,
                acceptSample: (sampleBytes: Int) -> Boolean,
                onEos: () -> Unit
            ) {
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, TRANSCODE_TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            onFormat(encoder.outputFormat)
                        }
                        outIndex >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIndex)
                            val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (outBuf != null && info.size > 0 && !isCodecConfig && acceptSample(info.size)) {
                                val data = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(data)
                                samples.add(TranscodedAacCopier.Sample(info.presentationTimeUs, data, info.flags))
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                onEos()
                                return
                            }
                        }
                    }
                }
            }
        }
    }

    // 原始音轨直传：按视频 PTS 交错拷贝未改写的样本
    private class PassthroughAudioCopier(
        private val extractor: MediaExtractor,
        private val muxer: android.media.MediaMuxer,
        override val trackIndex: Int,
        override val audioMime: String
    ) : AudioTrackCopier() {
        private val buffer = ByteBuffer.allocate(1024 * 1024)
        private val info = MediaCodec.BufferInfo()
        private var finished = false
        @Volatile
        override var audioSamples: Int = 0
            private set

        override fun copySamplesUpTo(maxPresentationTimeUs: Long) {
            copyInternal { sampleTimeUs -> sampleTimeUs <= maxPresentationTimeUs }
        }

        override fun finish() {
            copyInternal { true }
        }

        override fun close() {
            extractor.release()
        }

        private fun copyInternal(shouldCopy: (Long) -> Boolean) {
            if (finished) return
            while (true) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0) {
                    finished = true
                    break
                }
                if (!shouldCopy(sampleTimeUs)) {
                    return
                }
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    finished = true
                    break
                }
                buffer.position(0)
                buffer.limit(sampleSize)
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = sampleTimeUs
                info.flags = 0
                muxer.writeSampleData(trackIndex, buffer, info)
                audioSamples++
                extractor.advance()
            }
        }
    }

    // 转码为 AAC 后缓冲的音轨：样本已按 PTS 升序排好，按视频 PTS 交错写出
    private class TranscodedAacCopier(
        private val muxer: android.media.MediaMuxer,
        override val trackIndex: Int,
        private val samples: List<Sample>
    ) : AudioTrackCopier() {
        data class Sample(val ptsUs: Long, val data: ByteArray, val flags: Int)

        override val audioMime: String = MediaFormat.MIMETYPE_AUDIO_AAC
        private val buffer = ByteBuffer.allocate(1024 * 1024)
        private val info = MediaCodec.BufferInfo()
        private var cursor = 0
        @Volatile
        override var audioSamples: Int = 0
            private set

        override fun copySamplesUpTo(maxPresentationTimeUs: Long) {
            copyInternal { ptsUs -> ptsUs <= maxPresentationTimeUs }
        }

        override fun finish() {
            copyInternal { true }
        }

        override fun close() {
            // 无需释放 extractor（已在 create() 内释放）
        }

        private fun copyInternal(shouldCopy: (Long) -> Boolean) {
            while (cursor < samples.size) {
                val sample = samples[cursor]
                if (!shouldCopy(sample.ptsUs)) {
                    return
                }
                buffer.clear()
                val len = sample.data.size.coerceAtMost(buffer.capacity())
                buffer.put(sample.data, 0, len)
                buffer.position(0)
                buffer.limit(len)
                info.offset = 0
                info.size = len
                info.presentationTimeUs = sample.ptsUs
                info.flags = sample.flags
                muxer.writeSampleData(trackIndex, buffer, info)
                audioSamples++
                cursor++
            }
        }
    }

    class SurfaceInputWriter(
        private val inputSurface: Surface
    ) : Closeable {
        private val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val eglContext: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val program: Int
        private val positionHandle: Int
        private val texCoordHandle: Int
        private val textureHandle: Int
        private val vertexBuffer: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )
        private val texCoordBuffer: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        )

        init {
            require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Cannot get EGL display" }
            val version = IntArray(2)
            require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "EGL initialization failed" }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            require(EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
                "EGL config selection failed"
            }
            val eglConfig = configs[0] ?: error("No available EGL config found")
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            require(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, surfaceAttribs, 0)
            require(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL surface creation failed" }
            makeCurrent()
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = createTexture()
        }

        fun renderBitmap(bitmap: Bitmap, presentationTimeUs: Long) {
            makeCurrent()
            GLES20.glViewport(0, 0, bitmap.width, bitmap.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)

            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000L)
            require(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
        }

        override fun close() {
            runCatching {
                makeCurrent()
                GLES20.glDeleteTextures(1, intArrayOf(textureHandle), 0)
                GLES20.glDeleteProgram(program)
            }
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            runCatching { inputSurface.release() }
        }

        private fun makeCurrent() {
            require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed: ${eglErrorString(EGL14.eglGetError())}"
            }
        }

        private fun createTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            require(textureId != 0) { "Texture creation failed" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return textureId
        }

        private fun createProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
            val programId = GLES20.glCreateProgram()
            require(programId != 0) { "GL program creation failed" }
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
            require(linkStatus[0] == GLES20.GL_TRUE) {
                val message = GLES20.glGetProgramInfoLog(programId)
                GLES20.glDeleteProgram(programId)
                "GL program link failed: $message"
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return programId
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            require(shader != 0) { "Shader creation failed type=$type" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            require(compileStatus[0] == GLES20.GL_TRUE) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                "Shader compilation failed: $message"
            }
            return shader
        }

        private fun createFloatBuffer(values: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }

        private companion object {
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """
        }
    }

    private fun eglErrorString(error: Int): String {
        return when (error) {
            EGL14.EGL_SUCCESS -> "EGL_SUCCESS"
            EGL14.EGL_NOT_INITIALIZED -> "EGL_NOT_INITIALIZED"
            EGL14.EGL_BAD_ACCESS -> "EGL_BAD_ACCESS"
            EGL14.EGL_BAD_ALLOC -> "EGL_BAD_ALLOC"
            EGL14.EGL_BAD_ATTRIBUTE -> "EGL_BAD_ATTRIBUTE"
            EGL14.EGL_BAD_CONFIG -> "EGL_BAD_CONFIG"
            EGL14.EGL_BAD_CONTEXT -> "EGL_BAD_CONTEXT"
            EGL14.EGL_BAD_CURRENT_SURFACE -> "EGL_BAD_CURRENT_SURFACE"
            EGL14.EGL_BAD_DISPLAY -> "EGL_BAD_DISPLAY"
            EGL14.EGL_BAD_MATCH -> "EGL_BAD_MATCH"
            EGL14.EGL_BAD_NATIVE_PIXMAP -> "EGL_BAD_NATIVE_PIXMAP"
            EGL14.EGL_BAD_NATIVE_WINDOW -> "EGL_BAD_NATIVE_WINDOW"
            EGL14.EGL_BAD_PARAMETER -> "EGL_BAD_PARAMETER"
            EGL14.EGL_BAD_SURFACE -> "EGL_BAD_SURFACE"
            EGL14.EGL_CONTEXT_LOST -> "EGL_CONTEXT_LOST"
            else -> "0x${error.toString(16)}"
        }
    }

    fun alignDimensionToEncoder(value: Int): Int {
        if (value <= 1) return 2
        return if (value % 2 == 0) value else value - 1
    }

    fun clampDimensionToRange(value: Int, range: Range<Int>, alignment: Int): Int {
        val safeAlignment = alignment.coerceAtLeast(1)
        val clamped = value.coerceIn(range.lower, range.upper)
        val alignedDown = clamped - (clamped % safeAlignment)
        if (alignedDown in range) {
            return alignedDown.coerceAtLeast(safeAlignment)
        }
        val alignedUp = alignedDown + safeAlignment
        if (alignedUp in range) {
            return alignedUp
        }
        return range.lower
    }

    fun resolveSupportedSize(
        capabilities: MediaCodecInfo.VideoCapabilities,
        requestedWidth: Int,
        requestedHeight: Int
    ): Pair<Int, Int> {
        val widthAlignment = capabilities.widthAlignment.coerceAtLeast(1)
        val heightAlignment = capabilities.heightAlignment.coerceAtLeast(1)
        val alignedWidth = clampDimensionToRange(requestedWidth, capabilities.supportedWidths, widthAlignment)
        val alignedHeight = clampDimensionToRange(requestedHeight, capabilities.supportedHeights, heightAlignment)
        return alignedWidth to alignedHeight
    }

    @Suppress("DEPRECATION")
    fun colorFormatToString(format: Int): String {
        return when (format) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "Surface"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "YUV420SemiPlanar(NV12)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "YUV420Planar(I420)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "YUV420PackedPlanar"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "YUV420PackedSemiPlanar(NV21)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420Flexible"
            else -> "Unknown($format)"
        }
    }

}
