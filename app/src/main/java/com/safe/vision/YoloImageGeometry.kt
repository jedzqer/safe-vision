package com.safe.vision

import kotlin.math.max

/** The input image is placed at the top left of a square, with padding on the right/bottom. */
internal data class YoloImageGeometry(val originalWidth: Int, val originalHeight: Int) {
    val squareSize = max(originalWidth, originalHeight)

    /** Both bundled TFLite models return coordinates normalized to the padded square. */
    fun restoreBox(left: Float, top: Float, right: Float, bottom: Float): FloatArray? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        val x1 = (left * squareSize).coerceIn(0f, originalWidth.toFloat())
        val y1 = (top * squareSize).coerceIn(0f, originalHeight.toFloat())
        val x2 = (right * squareSize).coerceIn(0f, originalWidth.toFloat())
        val y2 = (bottom * squareSize).coerceIn(0f, originalHeight.toFloat())
        if (x2 <= x1 || y2 <= y1) return null
        return floatArrayOf(x1, y1, x2, y2)
    }
}
