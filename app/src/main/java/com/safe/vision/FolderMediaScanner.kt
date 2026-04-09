package com.safe.vision

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

class FolderMediaScanner(
    private val resolver: ContentResolver
) {
    data class ScanResult(
        val imageUris: List<Uri>,
        val videoUris: List<Uri>
    )

    fun scan(folderUri: Uri, onLog: (String) -> Unit): ScanResult {
        val imageUris = mutableListOf<Uri>()
        val videoUris = mutableListOf<Uri>()
        try {
            resolver.takePersistableUriPermission(
                folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            onLog("持久化权限失败: ${e.message}")
        }
        scanDirectory(folderUri, imageUris, videoUris, onLog)
        return ScanResult(imageUris, videoUris)
    }

    private fun scanDirectory(
        dirUri: Uri,
        imageUris: MutableList<Uri>,
        videoUris: MutableList<Uri>,
        onLog: (String) -> Unit
    ) {
        try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(dirUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, treeDocumentId)
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val documentId = cursor.getString(0)
                        val mimeType = cursor.getString(1)
                        val displayName = cursor.getString(2)
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, documentId)
                        when {
                            mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> {
                                scanDirectory(documentUri, imageUris, videoUris, onLog)
                            }
                            mimeType?.startsWith("image/") == true -> {
                                imageUris.add(documentUri)
                                onLog("找到图片: $displayName")
                            }
                            mimeType?.startsWith("video/") == true -> {
                                videoUris.add(documentUri)
                                onLog("找到视频: $displayName")
                            }
                            displayName?.lowercase()?.let { name ->
                                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                    name.endsWith(".png") || name.endsWith(".webp")
                            } == true -> {
                                imageUris.add(documentUri)
                                onLog("找到图片: $displayName")
                            }
                            displayName?.lowercase()?.let { name ->
                                name.endsWith(".mp4") || name.endsWith(".mov") ||
                                    name.endsWith(".avi") || name.endsWith(".mkv") ||
                                    name.endsWith(".3gp") || name.endsWith(".webm")
                            } == true -> {
                                videoUris.add(documentUri)
                                onLog("找到视频: $displayName")
                            }
                        }
                    } catch (e: Exception) {
                        onLog("跳过无效文件: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            onLog("扫描目录失败: ${e.message}")
        }
    }
}
