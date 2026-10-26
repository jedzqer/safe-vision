package com.safe.vision

import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloTensorInputTest {
    @Test
    fun packsPixelsInRowMajorInterleavedRgbOrder() {
        // A 2x2 image: red, green / blue, mixed color. Planar RGB corrupts this ordering.
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF804020.toInt())
        val buffer = YoloTensorInput.fromArgb(pixels)

        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(0, buffer.position())
        assertEquals(2 * 2 * 3 * Float.SIZE_BYTES, buffer.remaining())
        val actual = FloatArray(12)
        buffer.asFloatBuffer().get(actual)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 128f / 255f, 64f / 255f, 32f / 255f),
            actual,
            0.000001f
        )
    }

    @Test
    fun blackLetterboxAndWhitePixelsStayWithinNormalizedRange() {
        val buffer = YoloTensorInput.fromArgb(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        val actual = FloatArray(6)
        buffer.asFloatBuffer().get(actual)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), actual, 0f)
    }

    @Test
    fun acceptsBundledModelInputShape() {
        YoloTensorInput.requireShape(intArrayOf(1, 320, 320, 3), 320)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlanarInputEvenThoughByteCountMatches() {
        YoloTensorInput.requireShape(intArrayOf(1, 3, 320, 320), 320)
    }
}
