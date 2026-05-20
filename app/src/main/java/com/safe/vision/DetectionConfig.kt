package com.safe.vision

import kotlin.math.max

/**
 * Centralized detection configuration: labels, display names, locked labels, and thresholds.
 * Keeping them in one place avoids drift between model, UI, and privacy settings.
 */
object DetectionConfig {
    const val EYE_REGION_LABEL = "EYE_REGION"

    enum class LabelProfile(val formatKey: String) {
        STANDARD("standard"),
        ANIME("anime"),
        MIXED("mixed");

        companion object {
            fun fromFormatKey(value: String?): LabelProfile? {
                return entries.firstOrNull { it.formatKey == value }
            }
        }
    }

    // Standard YOLO model output labels in fixed order.
    val STANDARD_LABELS: List<String> = listOf(
        "FEMALE_GENITALIA_COVERED",
        "FACE_FEMALE",
        "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_BREAST_EXPOSED",
        "ANUS_EXPOSED",
        "FEET_EXPOSED",
        "BELLY_COVERED",
        "FEET_COVERED",
        "ARMPITS_COVERED",
        "ARMPITS_EXPOSED",
        "FACE_MALE",
        "BELLY_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED",
        "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED"
    )

    val ANIME_LABELS: List<String> = listOf(
        "Animal",
        "Anus",
        "Ass",
        "Balls",
        "Boob",
        "Dick",
        "Face",
        "Feet",
        "Nipple",
        "Pussy"
    )

    // All supported labels across saved metadata formats and settings.
    val LABELS: List<String> = (STANDARD_LABELS + ANIME_LABELS + EYE_REGION_LABEL).distinct()

    // Locked labels cannot be disabled in UI.
    val STANDARD_LOCKED_LABELS: List<String> = listOf(
        "FEMALE_GENITALIA_EXPOSED",
        "FEMALE_BREAST_EXPOSED"
    )

    val ANIME_LOCKED_LABELS: List<String> = listOf(
        "Boob",
        "Pussy"
    )

    val LOCKED_LABELS: List<String> = STANDARD_LOCKED_LABELS + ANIME_LOCKED_LABELS

    val LABEL_DISPLAY_NAMES: Map<String, String> = mapOf(
        "FEMALE_GENITALIA_COVERED" to "女性私处（遮挡）",
        "FACE_FEMALE" to "女性面部",
        "BUTTOCKS_EXPOSED" to "臀部（暴露）",
        "FEMALE_BREAST_EXPOSED" to "女性胸部（暴露）",
        "FEMALE_GENITALIA_EXPOSED" to "女性私处（暴露）",
        "MALE_BREAST_EXPOSED" to "男性胸部（暴露）",
        "ANUS_EXPOSED" to "肛门（暴露）",
        "FEET_EXPOSED" to "脚部（暴露）",
        "BELLY_COVERED" to "腹部（遮挡）",
        "FEET_COVERED" to "脚部（遮挡）",
        "ARMPITS_COVERED" to "腋下（遮挡）",
        "ARMPITS_EXPOSED" to "腋下（暴露）",
        "FACE_MALE" to "男性面部",
        "BELLY_EXPOSED" to "腹部（暴露）",
        "MALE_GENITALIA_EXPOSED" to "男性私处（暴露）",
        "ANUS_COVERED" to "肛门（遮挡）",
        "FEMALE_BREAST_COVERED" to "女性胸部（遮挡）",
        "BUTTOCKS_COVERED" to "臀部（遮挡）",
        "Animal" to "动物",
        "Anus" to "肛门",
        "Ass" to "臀部",
        "Balls" to "睾丸",
        "Boob" to "胸部",
        "Dick" to "阴茎",
        "Face" to "面部",
        "Feet" to "脚部",
        "Nipple" to "乳头",
        "Pussy" to "阴部",
        EYE_REGION_LABEL to "眼睛"
    )

    val STANDARD_FACE_LABELS: Set<String> = setOf(
        "FACE_FEMALE",
        "FACE_MALE"
    )

    val ANIME_FACE_LABELS: Set<String> = setOf(
        "Face"
    )

    val FACE_LABELS: Set<String> = STANDARD_FACE_LABELS + ANIME_FACE_LABELS

    const val SCORE_THRESHOLD = 0.2f
    const val NMS_THRESHOLD = 0.45f
    private const val CPU_THREAD_DIVISOR = 2

    object FaceLandmark {
        const val CONFIDENCE_THRESHOLD = 0.55f
        const val NMS_THRESHOLD = 0.4f
        const val VARIANCE_0 = 0.1f
        const val VARIANCE_1 = 0.2f
    }

    object VideoProcessing {
        const val MIN_SKIP_STRIDE = 3
        const val TARGET_BITRATE_FACTOR = 0.12f
        const val MAX_PRIMARY_FRAME_RATE = 30
        const val FALLBACK_FRAME_RATE_HIGH = 24
        const val FALLBACK_FRAME_RATE_LOW = 20
        const val FALLBACK_BITRATE_RATIO_HIGH = 0.7f
        const val FALLBACK_BITRATE_RATIO_LOW = 0.5f
        const val FALLBACK_MIN_BITRATE_HIGH = 1_500_000
        const val FALLBACK_MIN_BITRATE_LOW = 1_000_000
    }

    fun defaultCpuThreadCount(): Int {
        return max(1, Runtime.getRuntime().availableProcessors() / CPU_THREAD_DIVISOR)
    }

    fun getLabels(profile: LabelProfile): List<String> {
        return when (profile) {
            LabelProfile.STANDARD -> STANDARD_LABELS + EYE_REGION_LABEL
            LabelProfile.ANIME -> ANIME_LABELS + EYE_REGION_LABEL
            LabelProfile.MIXED -> LABELS
        }
    }

    fun getLockedLabels(profile: LabelProfile): List<String> {
        return when (profile) {
            LabelProfile.STANDARD -> STANDARD_LOCKED_LABELS
            LabelProfile.ANIME -> ANIME_LOCKED_LABELS
            LabelProfile.MIXED -> LOCKED_LABELS
        }
    }

    fun getDisplayName(label: String): String {
        return LABEL_DISPLAY_NAMES[label] ?: label
    }

    fun supportsFaceLandmarks(label: String): Boolean {
        return STANDARD_FACE_LABELS.contains(label)
    }

    fun isEyeRegionLabel(label: String): Boolean {
        return label == EYE_REGION_LABEL
    }

    fun canDeriveEyeRegion(label: String): Boolean {
        return FACE_LABELS.contains(label)
    }

    fun inferProfile(labels: Collection<String>): LabelProfile {
        val known = labels.filter { LABELS.contains(it) }.toSet()
        if (known.isEmpty()) return LabelProfile.STANDARD
        val hasStandard = known.any { STANDARD_LABELS.contains(it) }
        val hasAnime = known.any { ANIME_LABELS.contains(it) }
        return when {
            hasStandard && hasAnime -> LabelProfile.MIXED
            hasAnime -> LabelProfile.ANIME
            else -> LabelProfile.STANDARD
        }
    }
}
