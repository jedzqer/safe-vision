package com.safe.vision

import org.json.JSONArray
import org.json.JSONObject

object DetectionMetadataFormat {
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_LABEL_FORMAT = "label_format"
    private const val KEY_DETECTIONS = "detections"
    private const val CURRENT_SCHEMA_VERSION = 2

    data class Document(
        val detections: JSONArray,
        val labelProfile: DetectionConfig.LabelProfile
    )

    fun parse(text: String): Document {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val detections = JSONArray(trimmed)
            return Document(
                detections = detections,
                labelProfile = inferProfile(detections)
            )
        }

        val root = JSONObject(trimmed)
        val detections = root.optJSONArray(KEY_DETECTIONS) ?: JSONArray()
        val explicitFormat = root.opt(KEY_LABEL_FORMAT)?.toString()
        val explicitProfile = DetectionConfig.LabelProfile.fromFormatKey(explicitFormat)
        return Document(
            detections = detections,
            labelProfile = explicitProfile ?: inferProfile(detections)
        )
    }

    fun build(detections: JSONArray, labelProfile: DetectionConfig.LabelProfile): String {
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .put(KEY_LABEL_FORMAT, labelProfile.formatKey)
            .put(KEY_DETECTIONS, detections)
            .toString(4)
    }

    private fun inferProfile(detections: JSONArray): DetectionConfig.LabelProfile {
        val labels = mutableListOf<String>()
        for (i in 0 until detections.length()) {
            val obj = detections.optJSONObject(i) ?: continue
            val label = obj.optString("class")
            if (label.isNotBlank()) {
                labels.add(label)
            }
        }
        return DetectionConfig.inferProfile(labels)
    }
}
