package com.safe.vision

import android.graphics.Path
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

    /**
     * 眼睛遮挡区域。
     *
     * [rect] 是**未旋转**的 w×h 矩形，配合 [rotationDegrees] 使用，对应元数据的
     * `eye_bar` / `eye_bar_rotation`；[path] 是可直接绘制的倾斜四边形；
     * [bounds] 是外接轴对齐矩形，供只能接受轴对齐框的逻辑使用。
     */
    data class DerivedEyeRegion(
        val rect: RectF,
        val rotationDegrees: Float,
        val path: Path,
        val bounds: RectF
    )

    fun deriveEyeRegion(source: EyeRegionSource, surfaceWidth: Int, surfaceHeight: Int): DerivedEyeRegion? {
        if (!DetectionConfig.canDeriveEyeRegion(source.sourceLabel)) return null

        // 有双眼点：按眼距和倾角重建贴合倾斜眼睛的旋转矩形（几何与 test/eye/2.py 一致）。
        source.eyes?.let { (left, right) ->
            BlurEffects.orientedEyeRectFromEyes(left, right, surfaceWidth, surfaceHeight)?.let { oriented ->
                return DerivedEyeRegion(
                    rect = RectF(oriented.rect),
                    rotationDegrees = oriented.rotationDegrees,
                    path = oriented.path,
                    bounds = RectF(oriented.bounds)
                )
            }
        }

        // 只有已保存的遮挡条：沿用未旋转矩形 + 倾角。
        source.eyeBar?.let { eyeBar ->
            if (eyeBar.width() > 0f && eyeBar.height() > 0f) {
                val rect = clampRectF(eyeBar, surfaceWidth, surfaceHeight)
                if (rect.width() > 0f && rect.height() > 0f) {
                    val rotation = source.eyeBarRotationDegrees ?: 0f
                    val path = BlurEffects.rotatedRectPath(rect, rotation)
                    return DerivedEyeRegion(
                        rect = rect,
                        rotationDegrees = rotation,
                        path = path,
                        bounds = pathBounds(path)
                    )
                }
            }
        }

        // 既无眼点也无遮挡条：退回人脸上部横向条带。
        val faceRect = Rect(
            source.faceRect.left.toInt(),
            source.faceRect.top.toInt(),
            source.faceRect.right.toInt(),
            source.faceRect.bottom.toInt()
        )
        val strip = BlurEffects.cropToEyeStrip(faceRect, surfaceWidth, surfaceHeight)
        if (strip.width() <= 0 || strip.height() <= 0) return null
        return DerivedEyeRegion(
            rect = RectF(strip),
            rotationDegrees = 0f,
            path = Path().apply { addRect(RectF(strip), Path.Direction.CW) },
            bounds = RectF(strip)
        )
    }

    private fun pathBounds(path: Path): RectF {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        return bounds
    }

    private fun clampRectF(rect: RectF, surfaceWidth: Int, surfaceHeight: Int): RectF {
        val left = rect.left.coerceIn(0f, surfaceWidth.toFloat())
        val top = rect.top.coerceIn(0f, surfaceHeight.toFloat())
        val right = rect.right.coerceIn(left, surfaceWidth.toFloat())
        val bottom = rect.bottom.coerceIn(top, surfaceHeight.toFloat())
        return RectF(left, top, right, bottom)
    }
}
