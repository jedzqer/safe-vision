package com.safe.vision

import android.content.Context

object BodyPartSegmentationProvider {
    @Volatile
    private var runner: BodyPartSegmentationRunner? = null

    fun getRunner(context: Context): BodyPartSegmentationRunner {
        synchronized(this) {
            return runner ?: BodyPartSegmentationRunner(context.applicationContext).also {
                runner = it
            }
        }
    }

    fun clear() {
        synchronized(this) {
            runCatching { runner?.close() }
            runner = null
        }
    }
}
