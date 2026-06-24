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
        modelFileName = "yolo26n.onnx",
        dataFileName = null,
        optimizedFileName = "yolo26n_optimized.onnx",
        runtimeLabel = "26n-v1",
        inputSize = 320,
        outputLabels = DetectionConfig.STANDARD_LABELS
    ),
    ANIME(
        prefValue = "anime",
        displayName = "动漫模型",
        modelFileName = "320n-anime.onnx",
        dataFileName = "320n-anime.onnx.data",
        optimizedFileName = "320n_anime_optimized.onnx",
        runtimeLabel = "320n-anime",
        inputSize = 320,
        outputLabels = DetectionConfig.ANIME_LABELS
    );

    companion object {
        fun fromPrefValue(value: String?): DetectionModelVariant {
            return entries.firstOrNull { it.prefValue == value } ?: STANDARD
        }
    }
}
