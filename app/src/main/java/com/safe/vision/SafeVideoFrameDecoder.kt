package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable

data class DecodedVideoFrame(
    val index: Int,
    val bitmap: Bitmap,
    val presentationTimeUs: Long
)

class SafeVideoFrameDecoder(
    context: Context,
    uri: Uri
) : Closeable {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var argbPixels = IntArray(0)

    val width: Int
    val height: Int
    val frameRate: Float
    val durationUs: Long
    val frameIntervalUs: Long
    val totalFrames: Int

    init {
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
            extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalStateException("未找到视频轨道")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("视频格式未知")
        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)
        frameRate = (if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
        } else {
            24f
        }).coerceAtLeast(1f)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        frameIntervalUs = (1_000_000f / frameRate).toLong()
        totalFrames = if (durationUs > 0L) {
            ((durationUs / 1_000_000f) * frameRate).toInt().coerceAtLeast(1)
        } else {
            0
        }

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    fun nextFrame(indexHint: Int): DecodedVideoFrame? {
        if (outputDone) return null
        while (true) {
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            extractor.sampleFlags
                        )
                        extractor.advance()
                    }
                }
            }

            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        outputDone = true
                        return null
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outputIndex >= 0 -> {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    val image = decoder.getOutputImage(outputIndex)
                    val bitmap = image?.let { yuvToBitmap(it).also { _ -> image.close() } }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (bitmap != null) {
                        return DecodedVideoFrame(indexHint, bitmap, bufferInfo.presentationTimeUs)
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val requiredPixels = width * height
        if (argbPixels.size != requiredPixels) {
            argbPixels = IntArray(requiredPixels)
        }

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var outputIndex = 0
        for (row in 0 until height) {
            val imageRow = crop.top + row
            val yRowStart = yPlane.rowStride * imageRow + yPlane.pixelStride * crop.left
            val uvRow = imageRow / 2
            val uRowStart = uPlane.rowStride * uvRow + uPlane.pixelStride * (crop.left / 2)
            val vRowStart = vPlane.rowStride * uvRow + vPlane.pixelStride * (crop.left / 2)

            for (col in 0 until width) {
                val y = yBuffer.get(yRowStart + col * yPlane.pixelStride).toInt() and 0xFF
                val chromaOffset = (col / 2) * uPlane.pixelStride
                val u = uBuffer.get(uRowStart + chromaOffset).toInt() and 0xFF
                val v = vBuffer.get(vRowStart + (col / 2) * vPlane.pixelStride).toInt() and 0xFF
                argbPixels[outputIndex++] = yuvToArgb(y, u, v)
            }
        }

        return Bitmap.createBitmap(argbPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val yAdjusted = (y - 16).coerceAtLeast(0)
        val uShifted = u - 128
        val vShifted = v - 128
        val c = 1192 * yAdjusted
        val r = (c + 1634 * vShifted).coerceIn(0, 262143)
        val g = (c - 833 * vShifted - 400 * uShifted).coerceIn(0, 262143)
        val b = (c + 2066 * uShifted).coerceIn(0, 262143)
        return -0x1000000 or
            ((r shr 10) shl 16) or
            ((g shr 10) shl 8) or
            (b shr 10)
    }
}
