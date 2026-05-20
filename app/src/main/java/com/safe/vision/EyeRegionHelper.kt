package com.safe.vision

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF

object EyeRegionHelper {
    data class EyeRegionSource(
        val sourceLabel: String,
        val faceRect: RectF,
        val boxRotationDegrees: Float? = null,
        val eyes: Pair<PointF, PointF>? = null,
        val eyeBar: RectF? = null,
        val eyeBarRotationDegrees: Float? = null
    )

    fun deriveEyeRegion(source: EyeRegionSource, surfaceWidth: Int, surfaceHeight: Int): RectF? {
        if (!DetectionConfig.canDeriveEyeRegion(source.sourceLabel)) return null
        source.eyeBar?.let { eyeBar ->
            if (eyeBar.width() > 0f && eyeBar.height() > 0f) {
                return clampRectF(eyeBar, surfaceWidth, surfaceHeight)
            }
        }
        val faceRect = Rect(
            source.faceRect.left.toInt(),
            source.faceRect.top.toInt(),
            source.faceRect.right.toInt(),
            source.faceRect.bottom.toInt()
        )
        source.eyes?.let { (left, right) ->
            return RectF(
                BlurEffects.eyeBarFromEyes(
                    faceRect,
                    left,
                    right,
                    surfaceWidth,
                    surfaceHeight
                )
            )
        }
        return RectF(BlurEffects.cropToEyeStrip(faceRect, surfaceWidth, surfaceHeight))
    }

    private fun clampRectF(rect: RectF, surfaceWidth: Int, surfaceHeight: Int): RectF {
        val left = rect.left.coerceIn(0f, surfaceWidth.toFloat())
        val top = rect.top.coerceIn(0f, surfaceHeight.toFloat())
        val right = rect.right.coerceIn(left, surfaceWidth.toFloat())
        val bottom = rect.bottom.coerceIn(top, surfaceHeight.toFloat())
        return RectF(left, top, right, bottom)
    }
}
