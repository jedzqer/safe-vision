package com.safe.vision

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

class MediaSelectionHelper(
    private val resolver: ContentResolver
) {
    fun filterSupportedImageUris(uris: List<Uri>): List<Uri> {
        return uris.filter { uri ->
            val type = resolver.getType(uri)
            if (type != null) {
                type.startsWith("image/")
            } else {
                val name = queryDisplayName(uri) ?: return@filter false
                val lower = name.lowercase()
                lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") ||
                    lower.endsWith(".webp")
            }
        }
    }

    fun persistReadPermissions(uris: List<Uri>) {
        uris.forEach { uri ->
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // best effort
            }
        }
    }

    fun readBytes(uri: Uri): ByteArray? {
        return resolver.openInputStream(uri)?.use { it.readBytes() }
    }

    fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }
}
