package com.safe.vision

import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/** Drains capture even while inference is busy, retaining just the newest image. */
internal class ScreenCaptureSource(width: Int, height: Int, onFrameAvailable: () -> Unit) {
    // One retained image, plus acquireLatestImage's two-image discard margin.
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var latestImage: Image? = null
    private var closed = false

    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({
            val acquired = synchronized(lock) {
                if (closed) return@synchronized false
                val image = reader.acquireLatestImage() ?: return@synchronized false
                latestImage?.close()
                latestImage = image
                true
            }
            if (acquired) onFrameAvailable()
        }, handler)
    }

    // Only hold the lock for pixel copying (never inference or rendering).
    fun <T> withLatestImage(block: (Image) -> T): T? = synchronized(lock) {
        if (closed) return@synchronized null
        val image = latestImage ?: return@synchronized null
        latestImage = null
        image.use(block)
    }

    fun close() {
        // Closing on the capture thread avoids blocking the main thread on a pixel copy.
        handler.post {
            try {
                synchronized(lock) {
                    if (!closed) {
                        closed = true
                        reader.setOnImageAvailableListener(null, null)
                        latestImage?.close()
                        latestImage = null
                        reader.close()
                    }
                }
            } finally {
                thread.quitSafely()
            }
        }
    }
}
