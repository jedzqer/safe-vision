package com.safe.vision

import kotlin.math.abs

object MotionEstimator {
    const val SIGNATURE_SIZE = 32
    const val SEARCH_RANGE = 4
    const val MIN_CONFIDENCE = 0.3f

    data class MotionVector(
        val dxPx: Float,
        val dyPx: Float,
        val confidence: Float
    )

    fun estimate(
        current: FloatArray,
        previous: FloatArray,
        screenWidth: Int,
        screenHeight: Int,
        size: Int = SIGNATURE_SIZE,
        searchRange: Int = SEARCH_RANGE
    ): MotionVector? {
        if (current.size < size * size || previous.size < size * size) return null

        val sizeF = size.toFloat()

        val curRows = FloatArray(size)
        val prevRows = FloatArray(size)
        val curCols = FloatArray(size)
        val prevCols = FloatArray(size)
        for (r in 0 until size) {
            var cr = 0f
            var pr = 0f
            var cc = 0f
            var pc = 0f
            for (c in 0 until size) {
                val rowIdx = r * size + c
                cr += current[rowIdx]
                pr += previous[rowIdx]
                val colIdx = c * size + r
                cc += current[colIdx]
                pc += previous[colIdx]
            }
            curRows[r] = cr
            prevRows[r] = pr
            curCols[r] = cc
            prevCols[r] = pc
        }

        val (bestDy, bestRowCost) = findBestOffset(curRows, prevRows, size, searchRange)
        val (bestDx, bestColCost) = findBestOffset(curCols, prevCols, size, searchRange)
        val zeroRowCost = costAtOffset(curRows, prevRows, size, 0)
        val zeroColCost = costAtOffset(curCols, prevCols, size, 0)

        val rowConfidence = improvementRatio(zeroRowCost, bestRowCost)
        val colConfidence = improvementRatio(zeroColCost, bestColCost)
        val confidence = minOf(rowConfidence, colConfidence)
        if (confidence < MIN_CONFIDENCE) return null

        val scaleX = screenWidth / sizeF
        val scaleY = screenHeight / sizeF
        return MotionVector(
            dxPx = bestDx * scaleX,
            dyPx = bestDy * scaleY,
            confidence = confidence
        )
    }

    private fun findBestOffset(
        current: FloatArray,
        previous: FloatArray,
        size: Int,
        searchRange: Int
    ): Pair<Int, Float> {
        var bestOffset = 0
        var bestCost = Float.MAX_VALUE
        for (offset in -searchRange..searchRange) {
            val cost = costAtOffset(current, previous, size, offset)
            if (cost < bestCost) {
                bestCost = cost
                bestOffset = offset
            }
        }
        return bestOffset to bestCost
    }

    private fun costAtOffset(
        current: FloatArray,
        previous: FloatArray,
        size: Int,
        offset: Int
    ): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until size) {
            val j = i + offset
            if (j in 0 until size) {
                sum += abs(current[j] - previous[i])
                count++
            }
        }
        return if (count > 0) sum / count else Float.MAX_VALUE
    }

    private fun improvementRatio(zeroCost: Float, bestCost: Float): Float {
        if (zeroCost <= 1f) return 0f
        return ((zeroCost - bestCost) / zeroCost).coerceIn(0f, 1f)
    }
}
