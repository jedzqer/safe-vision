package com.safe.vision

import java.io.File

object FolderModels {
    const val SAFE_NET_DIR = "SafeNet"
    const val NO_DETECTION_DIR = "no_detection"
    const val SAFE_VIDEO_DIR = "SafeVideo"
    val SYSTEM_DIRS = setOf(SAFE_NET_DIR, NO_DETECTION_DIR, SAFE_VIDEO_DIR)
}

enum class FolderType {
    SAFE_NET,
    NO_DETECTION,
    VIDEO
}

data class FolderSummary(
    val folderType: FolderType,
    val count: Int,
    val previewFiles: List<File>,
    val lastUpdated: Long
)

data class OutputFolderItem(
    val name: String,
    val isSystem: Boolean,
    val isSelected: Boolean,
    val count: Int,
    val previewFiles: List<File>
)
