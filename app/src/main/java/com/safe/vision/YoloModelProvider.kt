package com.safe.vision

import android.content.Context

/**
 * Shared provider that caches YOLO runners per variant to avoid repeated asset reads
 * and to centralize creation/fallback logic.
 */
object YoloModelProvider {
    private val runners = mutableMapOf<DetectionModelVariant, YoloOnnxRunner>()

    fun getRunner(
        context: Context,
        variant: DetectionModelVariant = AppSettingsManager.getInstance(context).getDetectionModelVariant()
    ): YoloOnnxRunner {
        synchronized(this) {
            return runners[variant] ?: YoloOnnxRunner(context.applicationContext, variant).also {
                runners[variant] = it
            }
        }
    }

    fun clear() {
        synchronized(this) {
            runners.clear()
        }
    }
}
