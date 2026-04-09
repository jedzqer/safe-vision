package com.safe.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

class ThumbnailCacheManager private constructor(context: Context) {

    enum class MediaKind {
        IMAGE,
        VIDEO
    }

    companion object {
        private const val DISK_CACHE_DIR = "thumbs_v1"
        private const val DISK_CACHE_MAX_BYTES = 100L * 1024L * 1024L

        @Volatile
        private var instance: ThumbnailCacheManager? = null

        fun getInstance(context: Context): ThumbnailCacheManager {
            return instance ?: synchronized(this) {
                instance ?: ThumbnailCacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryCache =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8L / 1024L).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    private val diskDir = File(context.cacheDir, DISK_CACHE_DIR).apply { if (!exists()) mkdirs() }
    private val executor = Executors.newFixedThreadPool(3)
    private val inFlight = Collections.synchronizedMap(mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>())

    fun load(file: File, kind: MediaKind, targetPx: Int, callback: (Bitmap?) -> Unit) {
        val key = buildKey(file, kind, targetPx)
        memoryCache.get(key)?.let {
            callback(it)
            return
        }
        synchronized(inFlight) {
            val waiters = inFlight[key]
            if (waiters != null) {
                waiters.add(callback)
                return
            }
            inFlight[key] = mutableListOf(callback)
        }
        executor.execute {
            val bitmap = loadInternal(file, kind, targetPx, key)
            val callbacks = synchronized(inFlight) { inFlight.remove(key) ?: mutableListOf() }
            callbacks.forEach { cb ->
                mainHandler.post { cb(bitmap) }
            }
        }
    }

    fun preload(files: List<File>, kind: MediaKind, targetPx: Int) {
        files.forEach { file -> load(file, kind, targetPx) {} }
    }

    fun invalidate(file: File) {
        val path = file.absolutePath
        val keys = memoryCache.snapshot().keys.filter { it.startsWith(path) }
        keys.forEach { memoryCache.remove(it) }
        diskDir.listFiles()?.forEach { cacheFile ->
            if (cacheFile.name.startsWith(hash(path))) {
                cacheFile.delete()
            }
        }
    }

    fun clearAll() {
        memoryCache.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
    }

    private fun loadInternal(file: File, kind: MediaKind, targetPx: Int, key: String): Bitmap? {
        val diskFile = File(diskDir, "${hash(key)}.jpg")
        if (diskFile.exists()) {
            val cached = BitmapFactory.decodeFile(diskFile.absolutePath)
            if (cached != null) {
                memoryCache.put(key, cached)
                diskFile.setLastModified(System.currentTimeMillis())
                return cached
            }
        }

        val decoded = when (kind) {
            MediaKind.IMAGE -> decodeImage(file, targetPx)
            MediaKind.VIDEO -> decodeVideo(file, targetPx)
        } ?: return null

        memoryCache.put(key, decoded)
        saveToDisk(decoded, diskFile)
        trimDiskCache()
        return decoded
    }

    private fun decodeImage(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeVideo(file: File, targetPx: Int): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.frameAtTime
            retriever.release()
            frame?.let { source ->
                val scaled = Bitmap.createScaledBitmap(source, targetPx, targetPx, true)
                if (scaled != source) source.recycle()
                scaled
            }
        } catch (e: Exception) {
            DebugLogManager.addLog("缩略图", "视频缩略图解码失败: ${file.name}, ${e.message}")
            null
        }
    }

    private fun saveToDisk(bitmap: Bitmap, diskFile: File) {
        try {
            FileOutputStream(diskFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            }
        } catch (_: Exception) {
            // Ignore disk cache write errors.
        }
    }

    private fun trimDiskCache() {
        val files = diskDir.listFiles()?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_MAX_BYTES) return

        val sorted = files.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= DISK_CACHE_MAX_BYTES) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }

    private fun buildKey(file: File, kind: MediaKind, targetPx: Int): String {
        return "${file.absolutePath}|${file.lastModified()}|${file.length()}|${kind.name}|$targetPx"
    }

    private fun calculateSampleSize(width: Int, height: Int, targetPx: Int): Int {
        var inSampleSize = 1
        if (height > targetPx || width > targetPx) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= targetPx && (halfWidth / inSampleSize) >= targetPx) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }
}
