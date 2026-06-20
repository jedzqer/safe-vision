package com.safe.vision

import org.json.JSONArray
import org.json.JSONObject

object DetectionMetadataFormat {
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_LABEL_FORMAT = "label_format"
    private const val KEY_DETECTIONS = "detections"
    private const val KEY_SEGMENTATIONS = "segmentations"
    private const val CURRENT_SCHEMA_VERSION = 4

    data class Document(
        val detections: JSONArray,
        val segmentations: JSONArray,
        val labelProfile: DetectionConfig.LabelProfile
    )

    fun parse(text: String): Document {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val detections = JSONArray(trimmed)
            return Document(
                detections = detections,
                segmentations = JSONArray(),
                labelProfile = inferProfile(detections)
            )
        }

        val root = JSONObject(trimmed)
        val detections = root.optJSONArray(KEY_DETECTIONS) ?: JSONArray()
        val segmentations = root.optJSONArray(KEY_SEGMENTATIONS) ?: JSONArray()
        val explicitFormat = root.opt(KEY_LABEL_FORMAT)?.toString()
        val explicitProfile = DetectionConfig.LabelProfile.fromFormatKey(explicitFormat)
        return Document(
            detections = detections,
            segmentations = segmentations,
            labelProfile = explicitProfile ?: DetectionConfig.inferProfile(
                collectLabels(detections, segmentations)
            )
        )
    }

    fun build(
        detections: JSONArray,
        segmentations: JSONArray = JSONArray(),
        labelProfile: DetectionConfig.LabelProfile
    ): String {
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .put(KEY_LABEL_FORMAT, labelProfile.formatKey)
            .put(KEY_DETECTIONS, detections)
            .put(KEY_SEGMENTATIONS, segmentations)
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

    private fun collectLabels(detections: JSONArray, segmentations: JSONArray): List<String> {
        val labels = mutableListOf<String>()
        for (i in 0 until detections.length()) {
            val obj = detections.optJSONObject(i) ?: continue
            val label = obj.optString("class")
            if (label.isNotBlank()) labels.add(label)
        }
        for (i in 0 until segmentations.length()) {
            val obj = segmentations.optJSONObject(i) ?: continue
            val label = obj.optString("class")
            if (DetectionConfig.BODY_LABELS.contains(label)) labels.add(label)
        }
        return labels
    }

    fun collectAllLabels(document: Document): Set<String> {
        val labels = linkedSetOf<String>()
        for (i in 0 until document.detections.length()) {
            val obj = document.detections.optJSONObject(i) ?: continue
            val label = obj.optString("class")
            if (DetectionConfig.LABELS.contains(label)) labels.add(label)
        }
        for (i in 0 until document.segmentations.length()) {
            val obj = document.segmentations.optJSONObject(i) ?: continue
            val label = obj.optString("class")
            if (DetectionConfig.BODY_LABELS.contains(label)) labels.add(label)
        }
        return labels
    }
}
