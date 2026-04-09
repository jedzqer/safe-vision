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
    val score: Float? = null,
    val eyes: Pair<PointF, PointF>? = null
)

object DetectionMetadataIo {
    fun read(file: File?): MutableList<EditableDetection> {
        if (file == null || !file.exists()) return mutableListOf()
        val text = file.readText(Charsets.UTF_8)
        val arr = DetectionMetadataFormat.parse(text).detections
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
            val score = if (obj.has("score")) obj.optDouble("score", 1.0).toFloat() else null
            list.add(
                EditableDetection(
                    label = label,
                    rect = RectF(x, y, x + w, y + h),
                    score = score,
                    eyes = eyes
                )
            )
        }
        return list
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
}
