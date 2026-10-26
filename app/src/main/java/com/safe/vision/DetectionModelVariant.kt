package com.safe.vision

enum class DetectionModelVariant(
    val prefValue: String,
    val displayName: String,
    val modelFileName: String,
    val dataFileName: String?,
    val optimizedFileName: String,
    val runtimeLabel: String,
    val inputSize: Int,
    val outputLabels: List<String>
) {
    STANDARD(
        prefValue = "standard",
        displayName = "标准模型",
        modelFileName = "nude26_best_float16.tflite",
        dataFileName = null,
        optimizedFileName = "nude26_best_float16.tflite",
        runtimeLabel = "26n-v1",
        inputSize = 320,
        outputLabels = DetectionConfig.STANDARD_LABELS
    ),
    ANIME(
        prefValue = "anime",
        displayName = "动漫模型",
        modelFileName = "anime_320n_float16.tflite",
        dataFileName = null,
        optimizedFileName = "anime_320n_float16.tflite",
        runtimeLabel = "320n-anime",
        inputSize = 320,
        outputLabels = DetectionConfig.ANIME_LABELS
    );

    companion object {
        fun fromPrefValue(value: String?): DetectionModelVariant {
            return entries.firstOrNull { it.prefValue == value } ?: STANDARD
        }

        fun displayName(context: android.content.Context, variant: DetectionModelVariant): String {
            val resId = when (variant) {
                STANDARD -> R.string.model_name_standard
                ANIME -> R.string.model_name_anime
            }
            return context.getString(resId)
        }
    }
}
