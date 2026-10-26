package com.safe.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFrameChangeDetectorTest {
    private val detector = ScreenFrameChangeDetector(64)
    private fun pixels(color: Int) = IntArray(64 * 64) { color }

    @Test fun firstFrameAndResetRequireDetection() {
        val frame = pixels(0x505050)
        assertTrue(detector.hasChanged(frame))
        detector.markDetected(frame)
        assertFalse(detector.hasChanged(frame))
        detector.reset()
        assertTrue(detector.hasChanged(frame))
    }

    @Test fun ignoresSmallPixelNoiseAndAlpha() {
        detector.markDetected(pixels(0x505050))
        val noise = IntArray(64 * 64) { if (it % 2 == 0) 0xff51504f.toInt() else 0xff4f5051.toInt() }
        assertFalse(detector.hasChanged(noise))
    }

    @Test fun skippedSlowChangesAccumulateAgainstLastDetection() {
        detector.markDetected(pixels(0x505050))
        assertFalse(detector.hasChanged(pixels(0x515151)))
        assertTrue(detector.hasChanged(pixels(0x525252)))
    }

    @Test fun detectsLocalMotionHiddenByWholeScreenAverage() {
        detector.markDetected(pixels(0x505050))
        val frame = pixels(0x505050)
        // An 8x8 tile moves slightly; whole-frame difference is only 0.0625.
        for (y in 24 until 32) for (x in 16 until 24) frame[y * 64 + x] = 0x545454
        assertTrue(detector.hasChanged(frame))
    }

    @Test fun detectsColorChangesWithAlmostIdenticalLuminance() {
        detector.markDetected(pixels(0xff0000))
        val frame = pixels(0xff0000)
        // Red luminance ~76.25; green luminance ~76.31.
        for (i in 0 until 8) frame[i * 65] = 0x008200
        assertTrue(detector.hasChanged(frame))
    }

    @Test fun baselineOwnsItsPixels() {
        val frame = pixels(0x505050)
        detector.markDetected(frame)
        frame.fill(0xffffff)
        assertTrue(detector.hasChanged(frame))
        detector.markDetected(frame)
        assertFalse(detector.hasChanged(frame))
    }
}
