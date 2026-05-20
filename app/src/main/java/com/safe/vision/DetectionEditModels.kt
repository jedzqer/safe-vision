package com.safe.vision

import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class EditableDetection(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val rect: RectF,
    val boxRotationDegrees: Float? = null,
    val score: Float? = null,
    val eyes: Pair<PointF, PointF>? = null,
    val eyeBar: RectF? = null,
    val eyeBarRotationDegrees: Float? = null
)

object DetectionMetadataIo {
    fun read(file: File?): MutableList<EditableDetection> {
        if (file == null || !file.exists()) return mutableListOf()
        val text = file.readText(Charsets.UTF_8)
        val metadata = DetectionMetadataFormat.parse(text)
        val arr = metadata.detections
        val list = mutableListOf<EditableDetection>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val label = obj.optString("class")
            if (label.isBlank()) continue
            val box = obj.optJSONArray("box") ?: continue
            if (box.length() < 4) continue
            val x = box.optDouble(0, 0.0).toFloat()
            val y = box.optDouble(1, 0.0).toFloat()
            val w = box.optDouble(2, 0.0).toFloat()
            val h = box.optDouble(3, 0.0).toFloat()
            if (w <= 0f || h <= 0f) continue
            val eyes = parseEyes(obj.optJSONArray("eyes"))
            val eyeBar = parseRect(obj.optJSONArray("eye_bar"))
            val boxRotationDegrees = if (obj.has("box_rotation")) {
                obj.optDouble("box_rotation", 0.0).toFloat()
            } else if (obj.has("eye_bar_rotation") && eyeBar != null) {
                obj.optDouble("eye_bar_rotation", 0.0).toFloat()
            } else {
                null
            }
            val eyeBarRotationDegrees = if (obj.has("eye_bar_rotation")) {
                obj.optDouble("eye_bar_rotation", 0.0).toFloat()
            } else {
                null
            }
            val score = if (obj.has("score")) obj.optDouble("score", 1.0).toFloat() else null
            list.add(
                EditableDetection(
                    label = label,
                    rect = RectF(x, y, x + w, y + h),
                    boxRotationDegrees = boxRotationDegrees,
                    score = score,
                    eyes = eyes,
                    eyeBar = eyeBar,
                    eyeBarRotationDegrees = eyeBarRotationDegrees
                )
            )
        }
        val withDerivedEyeRegions = mutableListOf<EditableDetection>()
        val hasExplicitEyeRegion = list.any { it.label == DetectionConfig.EYE_REGION_LABEL }
        list.forEach { item ->
            withDerivedEyeRegions.add(item)
            if (hasExplicitEyeRegion || !DetectionConfig.canDeriveEyeRegion(item.label)) return@forEach
            val eyeRect = EyeRegionHelper.deriveEyeRegion(
                EyeRegionHelper.EyeRegionSource(
                    sourceLabel = item.label,
                    faceRect = item.rect,
                    boxRotationDegrees = item.boxRotationDegrees,
                    eyes = item.eyes,
                    eyeBar = item.eyeBar,
                    eyeBarRotationDegrees = item.eyeBarRotationDegrees
                ),
                surfaceWidth = item.rect.right.toInt().coerceAtLeast(1),
                surfaceHeight = item.rect.bottom.toInt().coerceAtLeast(1)
            ) ?: return@forEach
            withDerivedEyeRegions.add(
                EditableDetection(
                    label = DetectionConfig.EYE_REGION_LABEL,
                    rect = eyeRect,
                    boxRotationDegrees = item.eyeBarRotationDegrees ?: item.boxRotationDegrees,
                    score = item.score,
                    eyes = item.eyes,
                    eyeBar = eyeRect,
                    eyeBarRotationDegrees = item.eyeBarRotationDegrees
                )
            )
        }
        return withDerivedEyeRegions
    }

    fun write(file: File, items: List<EditableDetection>) {
        val arr = JSONArray()
        items.forEach { item ->
            val clampedW = item.rect.width().coerceAtLeast(1f)
            val clampedH = item.rect.height().coerceAtLeast(1f)
            val obj = JSONObject().apply {
                put("class", item.label)
                put(
                    "box",
                    JSONArray().apply {
                        put(item.rect.left.toInt())
                        put(item.rect.top.toInt())
                        put(clampedW.toInt())
                        put(clampedH.toInt())
                    }
                )
                item.boxRotationDegrees?.let { rotation ->
                    put("box_rotation", rotation.toDouble())
                }
                item.eyes?.let { (left, right) ->
                    put(
                        "eyes",
                        JSONArray().apply {
                            put(JSONArray().apply {
                                put(left.x.toInt())
                                put(left.y.toInt())
                            })
                            put(JSONArray().apply {
                                put(right.x.toInt())
                                put(right.y.toInt())
                            })
                        }
                    )
                }
                item.eyeBar?.let { eyeBar ->
                    val clampedEyeBarW = eyeBar.width().coerceAtLeast(1f)
                    val clampedEyeBarH = eyeBar.height().coerceAtLeast(1f)
                    put(
                        "eye_bar",
                        JSONArray().apply {
                            put(eyeBar.left.toInt())
                            put(eyeBar.top.toInt())
                            put(clampedEyeBarW.toInt())
                            put(clampedEyeBarH.toInt())
                        }
                    )
                    item.eyeBarRotationDegrees?.let { rotation ->
                        put("eye_bar_rotation", rotation.toDouble())
                    }
                }
            }
            arr.put(obj)
        }
        val profile = DetectionConfig.inferProfile(items.map { it.label })
        file.writeText(DetectionMetadataFormat.build(arr, profile), Charsets.UTF_8)
    }

    private fun parseEyes(eyesArray: JSONArray?): Pair<PointF, PointF>? {
        if (eyesArray == null || eyesArray.length() < 2) return null
        val left = eyesArray.optJSONArray(0) ?: return null
        val right = eyesArray.optJSONArray(1) ?: return null
        if (left.length() < 2 || right.length() < 2) return null
        return PointF(
            left.optDouble(0, 0.0).toFloat(),
            left.optDouble(1, 0.0).toFloat()
        ) to PointF(
            right.optDouble(0, 0.0).toFloat(),
            right.optDouble(1, 0.0).toFloat()
        )
    }

    private fun parseRect(array: JSONArray?): RectF? {
        if (array == null || array.length() < 4) return null
        val x = array.optDouble(0, 0.0).toFloat()
        val y = array.optDouble(1, 0.0).toFloat()
        val w = array.optDouble(2, 0.0).toFloat()
        val h = array.optDouble(3, 0.0).toFloat()
        if (w <= 0f || h <= 0f) return null
        return RectF(x, y, x + w, y + h)
    }
}
