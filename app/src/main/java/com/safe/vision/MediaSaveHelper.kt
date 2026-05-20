package com.safe.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Shared helper for persisting processed media and metadata.
 */
object MediaSaveHelper {

    data class SaveResult(
        val imageFile: File,
        val metadataFile: File?,
        val hasDetections: Boolean
    )

    fun saveImage(
        context: Context,
        bytes: ByteArray,
        originalName: String?,
        detections: List<YoloOnnxRunner.Detection>,
        preferredDetectedFolder: String = FolderModels.SAFE_NET_DIR
    ): SaveResult {
        val rootDir = context.getExternalFilesDir(null) ?: context.filesDir
        val safeDir = File(rootDir, preferredDetectedFolder).apply { if (!exists()) mkdirs() }
        val noDetectionDir = File(rootDir, FolderModels.NO_DETECTION_DIR).apply { if (!exists()) mkdirs() }
        val hasDetections = detections.isNotEmpty()
        val targetDir = if (hasDetections) safeDir else noDetectionDir

        val baseName = buildBaseFileName(originalName)
        val extension = extractExtension(originalName)
        val resolvedBaseName = resolveAvailableBaseName(targetDir, baseName, extension, hasDetections)
        val imageFile = File(targetDir, "$resolvedBaseName$extension").apply {
            writeBytes(bytes)
        }

        val jsonFile = if (hasDetections) {
            val file = File(targetDir, "$resolvedBaseName.json")
            file.writeText(buildMetadataJson(detections), Charsets.UTF_8)
            file
        } else {
            null
        }

        return SaveResult(imageFile, jsonFile, hasDetections)
    }

    private fun buildMetadataJson(detections: List<YoloOnnxRunner.Detection>): String {
        val array = JSONArray()
        YoloOnnxRunner.withDerivedEyeRegionDetections(detections).forEach { detection ->
            val box = detection.box
            val obj = JSONObject().apply {
                put("class", detection.className)
                put(
                    "box",
                    JSONArray().apply {
                        put(box.left.toInt())
                        put(box.top.toInt())
                        put(box.width().toInt())
                        put(box.height().toInt())
                    }
                )
                if (detection.leftEye != null && detection.rightEye != null) {
                    put(
                        "eyes",
                        JSONArray().apply {
                            put(
                                JSONArray().apply {
                                    put(detection.leftEye.x.toInt())
                                    put(detection.leftEye.y.toInt())
                                }
                            )
                            put(
                                JSONArray().apply {
                                    put(detection.rightEye.x.toInt())
                                    put(detection.rightEye.y.toInt())
                                }
                            )
                        }
                    )
                }
                detection.eyeBar?.let { eyeBar ->
                    put(
                        "eye_bar",
                        JSONArray().apply {
                            put(eyeBar.left.toInt())
                            put(eyeBar.top.toInt())
                            put(eyeBar.width().toInt())
                            put(eyeBar.height().toInt())
                        }
                    )
                }
            }
            array.put(obj)
        }
        val profile = DetectionConfig.inferProfile(detections.map { it.className })
        return DetectionMetadataFormat.build(array, profile)
    }

    private fun buildBaseFileName(originalName: String?): String {
        return originalName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.replace('/', '_')
            ?.replace('\\', '_')
            ?.takeIf { it.isNotBlank() }
            ?: "image"
    }

    private fun extractExtension(originalName: String?): String {
        val suffix = originalName?.substringAfterLast('.', missingDelimiterValue = "")
        return if (suffix.isNullOrBlank()) ".jpg" else ".${suffix.lowercase(Locale.ROOT)}"
    }

    private fun resolveAvailableBaseName(
        targetDir: File,
        baseName: String,
        extension: String,
        hasDetections: Boolean
    ): String {
        var candidate = baseName
        var index = 1
        while (true) {
            val imageExists = File(targetDir, "$candidate$extension").exists()
            val jsonExists = hasDetections && File(targetDir, "$candidate.json").exists()
            if (!imageExists && !jsonExists) return candidate
            candidate = "${baseName}_$index"
            index++
        }
    }
}
