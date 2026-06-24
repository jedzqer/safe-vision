package com.safe.vision

import org.json.JSONArray
import org.json.JSONObject

object DetectionMetadataSanitizer {
    data class SanitizeResult(
        val changed: Boolean,
        val sanitizedText: String,
        val labelProfile: DetectionConfig.LabelProfile,
        val removedEntryCount: Int
    )

    fun sanitize(text: String): SanitizeResult? {
        val document = runCatching { DetectionMetadataFormat.parse(text) }.getOrNull() ?: return null
        val sourceArray = document.detections
        val allowedLabels = DetectionConfig.getPersistedLabels(document.labelProfile)
        val sanitizedArray = JSONArray()
        var removedEntryCount = 0

        for (index in 0 until sourceArray.length()) {
            val detection = sourceArray.optJSONObject(index)
            if (detection == null) {
                removedEntryCount++
                continue
            }
            val label = detection.optString("class").trim()
            val box = detection.optJSONArray("box")
            val width = box?.optDouble(2, 0.0) ?: 0.0
            val height = box?.optDouble(3, 0.0) ?: 0.0
            val isSupported = allowedLabels.contains(label)
            val hasValidBox = box != null && box.length() >= 4 && width > 0.0 && height > 0.0
            if (!isSupported || !hasValidBox) {
                removedEntryCount++
                continue
            }
            sanitizedArray.put(JSONObject(detection.toString()))
        }

        val sanitizedProfile = when (document.labelProfile) {
            DetectionConfig.LabelProfile.MIXED -> DetectionConfig.inferProfile(
                buildList {
                    for (i in 0 until sanitizedArray.length()) {
                        val label = sanitizedArray.optJSONObject(i)?.optString("class").orEmpty()
                        if (label.isNotBlank()) add(label)
                    }
                }
            )
            else -> document.labelProfile
        }

        return SanitizeResult(
            changed = removedEntryCount > 0,
            sanitizedText = DetectionMetadataFormat.build(sanitizedArray, sanitizedProfile),
            labelProfile = sanitizedProfile,
            removedEntryCount = removedEntryCount
        )
    }
}
