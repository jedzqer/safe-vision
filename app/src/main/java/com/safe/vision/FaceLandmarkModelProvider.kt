package com.safe.vision

import android.content.Context

/**
 * Shared provider that caches the face-landmark runner.
 */
object FaceLandmarkModelProvider {
    private var runner: FaceLandmarkOnnxRunner? = null

    fun getRunner(context: Context): FaceLandmarkOnnxRunner {
        synchronized(this) {
            return runner ?: FaceLandmarkOnnxRunner(context.applicationContext).also { runner = it }
        }
    }

    fun clear() {
        synchronized(this) {
            runner = null
        }
    }
}
