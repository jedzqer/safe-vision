package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache

/**
 * 贴纸加载与缓存工具，支持默认贴纸和用户自定义贴纸。
 */
object StickerLoader {

    private val cachedBitmaps: LruCache<String, Bitmap> = LruCache(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(1)
    )

    fun loadSticker(context: Context, settings: PrivacySettingsManager, label: String? = null): Bitmap? {
        val customUri = settings.getStickerUriForLabel(label)
        val sourceKey = customUri ?: "asset:${PrivacySettingsManager.DEFAULT_STICKER_ASSET}"
        cachedBitmaps[sourceKey]?.let { bitmap ->
            if (!bitmap.isRecycled) return bitmap
            cachedBitmaps.remove(sourceKey)
        }

        val bitmap = if (customUri.isNullOrBlank()) {
            loadFromAssets(context)
        } else {
            loadFromUri(context, customUri) ?: loadFromAssets(context)
        }

        bitmap?.let { cachedBitmaps.put(sourceKey, it) }
        return bitmap
    }

    fun clearCache() {
        val snapshot = cachedBitmaps.snapshot()
        cachedBitmaps.evictAll()
        snapshot.values.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun loadFromAssets(context: Context): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            context.assets.open(PrivacySettingsManager.DEFAULT_STICKER_ASSET).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            DebugLogManager.addLog("贴纸", "加载默认贴纸失败: ${e.message}")
            null
        }
    }

    private fun loadFromUri(context: Context, uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            DebugLogManager.addLog("贴纸", "加载自定义贴纸失败: ${e.message}")
            null
        }
    }
}
