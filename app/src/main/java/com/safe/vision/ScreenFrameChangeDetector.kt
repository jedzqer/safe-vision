package com.safe.vision

import kotlin.math.abs

/** Compares a small RGB sample with the last inferred frame, never with a skipped frame. */
internal class ScreenFrameChangeDetector(private val size: Int) {
    private val baseline = IntArray(size * size)
    private val tileSums = IntArray(64)
    private var hasBaseline = false

    init {
        require(size >= 8 && size % 8 == 0)
    }

    @Synchronized
    fun hasChanged(pixels: IntArray): Boolean {
        require(pixels.size == baseline.size)
        if (!hasBaseline) return true
        tileSums.fill(0)
        var total = 0
        var strongChanges = 0
        val tileSize = size / 8
        for (i in pixels.indices) {
            val current = pixels[i]
            val previous = baseline[i]
            val difference = maxOf(
                abs(((current shr 16) and 255) - ((previous shr 16) and 255)),
                abs(((current shr 8) and 255) - ((previous shr 8) and 255)),
                abs((current and 255) - (previous and 255))
            )
            total += difference
            tileSums[(i / size / tileSize) * 8 + (i % size / tileSize)] += difference
            if (difference >= 12) strongChanges++
        }
        return total >= pixels.size * 2 || strongChanges >= 8 ||
            tileSums.any { it >= tileSize * tileSize * 3 }
    }

    @Synchronized
    fun markDetected(pixels: IntArray) {
        require(pixels.size == baseline.size)
        pixels.copyInto(baseline)
        hasBaseline = true
    }

    @Synchronized
    fun reset() {
        hasBaseline = false
    }
}
