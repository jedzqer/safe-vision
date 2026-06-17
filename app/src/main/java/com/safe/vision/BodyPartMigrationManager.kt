package com.safe.vision

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

class BodyPartMigrationManager private constructor(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_DONE = "body_part_migration_v1_done"

        @Volatile
        private var INSTANCE: BodyPartMigrationManager? = null

        fun getInstance(context: Context): BodyPartMigrationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BodyPartMigrationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isMigrationDone(): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)
    }

    fun markMigrationDone() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }

    fun hasLegacyJsonImages(): Boolean {
        val root = context.getExternalFilesDir(null) ?: return false
        return root.walkTopDown().any { file ->
            file.isFile &&
                file.extension.equals("json", ignoreCase = true) &&
                findSiblingImage(file) != null &&
                needsMigration(file)
        }
    }

    suspend fun hasLegacyJsonImagesAsync(): Boolean = withContext(Dispatchers.IO) {
        hasLegacyJsonImages()
    }

    fun startMigration() {
        scope.launch {
            val root = context.getExternalFilesDir(null) ?: return@launch
            val runner = BodyPartSegmentationProvider.getRunner(context)
            var allSucceeded = true
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                .forEach { jsonFile ->
                    runCatching {
                        if (!needsMigration(jsonFile)) return@runCatching
                        val imageFile = findSiblingImage(jsonFile) ?: return@runCatching
                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@runCatching
                        val metadata = DetectionMetadataFormat.parse(jsonFile.readText(Charsets.UTF_8))
                        try {
                            val segmentations = runner.run(bitmap)
                            val segArray = org.json.JSONArray().apply {
                                segmentations.forEach { put(BodyPartSegmentationMetadata.toJsonObject(it)) }
                            }
                            val mergedText = DetectionMetadataFormat.build(
                                detections = metadata.detections,
                                segmentations = segArray,
                                labelProfile = DetectionConfig.inferProfile(
                                    DetectionMetadataFormat.collectAllLabels(
                                        metadata.copy(segmentations = segArray)
                                    )
                                )
                            )
                            jsonFile.writeText(mergedText, Charsets.UTF_8)
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }.onFailure { error ->
                        allSucceeded = false
                        DebugLogManager.addLog("迁移", "迁移失败: ${jsonFile.name}, ${error.message}")
                    }
                }
            if (allSucceeded && !hasLegacyJsonImages()) {
                markMigrationDone()
            }
        }
    }

    private fun findSiblingImage(jsonFile: File): File? {
        return listOf("jpg", "jpeg", "png", "webp")
            .map { ext -> File(jsonFile.parentFile, "${jsonFile.nameWithoutExtension}.$ext") }
            .firstOrNull { it.exists() }
    }

    private fun needsMigration(jsonFile: File): Boolean {
        val metadata = runCatching {
            DetectionMetadataFormat.parse(jsonFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return false
        if (metadata.segmentations.length() > 0) return false
        return metadata.labelProfile != DetectionConfig.LabelProfile.ANIME
    }
}
