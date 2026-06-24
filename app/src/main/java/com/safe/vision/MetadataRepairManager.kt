package com.safe.vision

import android.content.Context
import java.io.File

class MetadataRepairManager private constructor(private val context: Context) {
    companion object {
        private const val METADATA_REPAIR_VERSION = 1

        @Volatile
        private var INSTANCE: MetadataRepairManager? = null

        fun getInstance(context: Context): MetadataRepairManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MetadataRepairManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class RepairResult(
        val scannedFileCount: Int,
        val repairedFileCount: Int,
        val removedEntryCount: Int
    )

    fun repairIfNeeded(): RepairResult {
        val appSettings = AppSettingsManager.getInstance(context)
        if (appSettings.getMetadataRepairVersion() >= METADATA_REPAIR_VERSION) {
            return RepairResult(0, 0, 0)
        }

        val rootDir = context.getExternalFilesDir(null) ?: context.filesDir
        val metadataFiles = collectMetadataFiles(rootDir)
        var repairedFileCount = 0
        var removedEntryCount = 0

        metadataFiles.forEach { file ->
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            val sanitizeResult = DetectionMetadataSanitizer.sanitize(text) ?: return@forEach
            if (!sanitizeResult.changed) return@forEach

            runCatching {
                file.writeText(sanitizeResult.sanitizedText, Charsets.UTF_8)
            }.onSuccess {
                repairedFileCount++
                removedEntryCount += sanitizeResult.removedEntryCount
            }.onFailure { error ->
                DebugLogManager.addLog(
                    "元数据修复",
                    "修复失败: ${file.absolutePath}, ${error.message}"
                )
            }
        }

        appSettings.setMetadataRepairVersion(METADATA_REPAIR_VERSION)
        if (repairedFileCount > 0) {
            DebugLogManager.addLog(
                "元数据修复",
                "自动修复完成: 文件 $repairedFileCount/${metadataFiles.size}, 移除错误条目 $removedEntryCount"
            )
        }
        return RepairResult(
            scannedFileCount = metadataFiles.size,
            repairedFileCount = repairedFileCount,
            removedEntryCount = removedEntryCount
        )
    }

    private fun collectMetadataFiles(rootDir: File): List<File> {
        if (!rootDir.exists()) return emptyList()
        return rootDir.walkTopDown()
            .onEnter { dir -> !dir.name.equals(FolderModels.LOGS_DIR, ignoreCase = true) }
            .filter { file ->
                file.isFile && file.extension.equals("json", ignoreCase = true)
            }
            .toList()
    }
}
