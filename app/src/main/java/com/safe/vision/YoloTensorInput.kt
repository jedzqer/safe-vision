package com.safe.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs ARGB pixels as normalized, interleaved RGB for a FLOAT32 NHWC tensor. */
internal object YoloTensorInput {
    fun requireShape(shape: IntArray, inputSize: Int) {
        require(shape.contentEquals(intArrayOf(1, inputSize, inputSize, 3))) {
            "不支持的 TFLite 输入形状: ${shape.contentToString()}，期望 [1, $inputSize, $inputSize, 3] (NHWC)"
        }
    }

    fun fromArgb(pixels: IntArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            buffer.putFloat(((pixel ushr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel ushr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }
}
