package com.safe.vision

import android.content.Context
import android.content.SharedPreferences

import com.safe.vision.CustomPalette

/**
 * 应用通用设置管理器，目前用于控制图片选择入口等偏好。
 */
class AppSettingsManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_USE_FILE_SYSTEM_PICKER = "use_file_system_picker"
        private const val KEY_VIDEO_SKIP_STRIDE = "video_skip_stride"
        private const val KEY_VIDEO_HIGH_LOAD_ENABLED = "video_high_load_enabled"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_CUSTOM_THEME_BASE = "custom_theme_base"
        private const val KEY_CUSTOM_THEME_PRIMARY = "custom_theme_primary"
        private const val KEY_CUSTOM_THEME_ACCENT = "custom_theme_accent"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_RANDOM_PLAY_ENABLED = "random_play_enabled"
        private const val KEY_RANDOM_BROWSE_ENABLED = "random_browse_enabled"
        private const val KEY_RANDOM_QUEUE_TYPES = "random_queue_types"
        private const val KEY_RANDOM_QUEUE_LABELS = "random_queue_labels"
        private const val KEY_RANDOM_PLAY_INTERVAL_SECONDS = "random_play_interval_seconds"
        private const val KEY_METRONOME_ENABLED = "metronome_enabled"
        private const val KEY_METRONOME_INTERVAL_SECONDS = "metronome_interval_seconds"
        private const val KEY_DETECTION_MODEL_VARIANT = "detection_model_variant"
        private const val KEY_SCREEN_DETECTION_ANIME_MODEL = "screen_detection_anime_model"
        private const val KEY_SCREEN_DETECTION_INTERVAL_SECONDS = "screen_detection_interval_seconds"
        private const val KEY_SCREEN_DETECTION_USE_SYSTEM_ALERT_WINDOW = "screen_detection_use_system_alert_window"
        private const val DEFAULT_SCREEN_DETECTION_INTERVAL_SECONDS = 0.5f
        private const val MIN_SCREEN_DETECTION_INTERVAL_SECONDS = 0.01f
        private const val MAX_SCREEN_DETECTION_INTERVAL_SECONDS = 1.0f
        private const val KEY_SELECTED_OUTPUT_FOLDER = "selected_output_folder"
        private const val KEY_CUSTOM_IMAGE_FOLDERS = "custom_image_folders"
        private const val KEY_VIEWER_VERTICAL_SCROLL = "viewer_vertical_scroll"
        private const val DEFAULT_VIDEO_SKIP_STRIDE = 3
        private const val DEFAULT_RANDOM_PLAY_INTERVAL_SECONDS = 10
        private const val DEFAULT_METRONOME_INTERVAL_SECONDS = 1.0f
        private const val MIN_METRONOME_INTERVAL_SECONDS = 0.1f
        private const val MAX_METRONOME_INTERVAL_SECONDS = 5.0f

        @Volatile
        private var INSTANCE: AppSettingsManager? = null

        fun getInstance(context: Context): AppSettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    enum class RandomQueueType(val prefValue: String) {
        SAFENET("safenet"),
        NO_DETECTION("no_detection"),
        VIDEO_OUTPUT("video_output");

        companion object {
            fun fromPrefValues(values: Set<String>): Set<RandomQueueType> {
                return values.mapNotNull { value ->
                    entries.firstOrNull { it.prefValue == value }
                }.toSet()
            }
        }
    }

    fun getRandomQueueTypes(): Set<RandomQueueType> {
        val saved = sharedPreferences.getStringSet(KEY_RANDOM_QUEUE_TYPES, null)
        if (saved.isNullOrEmpty()) return RandomQueueType.entries.toSet()
        val parsed = RandomQueueType.fromPrefValues(saved)
        return if (parsed.isEmpty()) RandomQueueType.entries.toSet() else parsed
    }

    fun setRandomQueueTypes(types: Set<RandomQueueType>) {
        val safeTypes = if (types.isEmpty()) RandomQueueType.entries.toSet() else types
        val values = safeTypes.map { it.prefValue }.toSet()
        sharedPreferences.edit()
            .putStringSet(KEY_RANDOM_QUEUE_TYPES, values)
            .apply()
    }

    fun getRandomQueueLabels(): Set<String> {
        val saved = sharedPreferences.getStringSet(KEY_RANDOM_QUEUE_LABELS, null)
        if (saved.isNullOrEmpty()) return DetectionConfig.LABELS.toSet()
        val sanitized = saved.filterTo(linkedSetOf()) { DetectionConfig.LABELS.contains(it) }
        return if (sanitized.isEmpty()) DetectionConfig.LABELS.toSet() else sanitized
    }

    fun setRandomQueueLabels(labels: Set<String>) {
        val sanitized = labels.filterTo(linkedSetOf()) { DetectionConfig.LABELS.contains(it) }
        val safeLabels = if (sanitized.isEmpty()) DetectionConfig.LABELS.toSet() else sanitized
        sharedPreferences.edit()
            .putStringSet(KEY_RANDOM_QUEUE_LABELS, safeLabels)
            .apply()
    }

    fun getDetectionModelVariant(): DetectionModelVariant {
        val saved = sharedPreferences.getString(KEY_DETECTION_MODEL_VARIANT, DetectionModelVariant.STANDARD.prefValue)
        return DetectionModelVariant.fromPrefValue(saved)
    }

    fun setDetectionModelVariant(variant: DetectionModelVariant) {
        sharedPreferences.edit()
            .putString(KEY_DETECTION_MODEL_VARIANT, variant.prefValue)
            .apply()
    }

    fun isScreenDetectionAnimeModelEnabled(): Boolean {
        if (!sharedPreferences.contains(KEY_SCREEN_DETECTION_ANIME_MODEL)) {
            return getDetectionModelVariant() == DetectionModelVariant.ANIME
        }
        return sharedPreferences.getBoolean(KEY_SCREEN_DETECTION_ANIME_MODEL, false)
    }

    fun setScreenDetectionAnimeModelEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_SCREEN_DETECTION_ANIME_MODEL, enabled)
            .apply()
    }

    fun getScreenDetectionIntervalSeconds(): Float {
        val saved = sharedPreferences.getFloat(
            KEY_SCREEN_DETECTION_INTERVAL_SECONDS,
            DEFAULT_SCREEN_DETECTION_INTERVAL_SECONDS
        )
        return saved.coerceIn(
            MIN_SCREEN_DETECTION_INTERVAL_SECONDS,
            MAX_SCREEN_DETECTION_INTERVAL_SECONDS
        )
    }

    fun setScreenDetectionIntervalSeconds(seconds: Float) {
        sharedPreferences.edit()
            .putFloat(
                KEY_SCREEN_DETECTION_INTERVAL_SECONDS,
                seconds.coerceIn(
                    MIN_SCREEN_DETECTION_INTERVAL_SECONDS,
                    MAX_SCREEN_DETECTION_INTERVAL_SECONDS
                )
            )
            .apply()
    }

    fun isScreenDetectionSystemAlertWindowEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_SCREEN_DETECTION_USE_SYSTEM_ALERT_WINDOW, false)
    }

    fun setScreenDetectionSystemAlertWindowEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_SCREEN_DETECTION_USE_SYSTEM_ALERT_WINDOW, enabled)
            .apply()
    }

    fun getScreenDetectionOverlayMode(): ScreenOverlayMode {
        return if (isScreenDetectionSystemAlertWindowEnabled()) {
            ScreenOverlayMode.SYSTEM_ALERT_WINDOW
        } else {
            ScreenOverlayMode.ACCESSIBILITY
        }
    }

    fun isFileSystemPickerEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_USE_FILE_SYSTEM_PICKER, false)
    }

    fun setFileSystemPickerEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_USE_FILE_SYSTEM_PICKER, enabled).apply()
    }

    fun isRandomPlayEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_RANDOM_PLAY_ENABLED, false)
    }

    fun setRandomPlayEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_RANDOM_PLAY_ENABLED, enabled).apply()
    }

    fun isRandomBrowseEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_RANDOM_BROWSE_ENABLED, false)
    }

    fun setRandomBrowseEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_RANDOM_BROWSE_ENABLED, enabled).apply()
    }

    fun getRandomPlayIntervalSeconds(): Int {
        val saved = sharedPreferences.getInt(KEY_RANDOM_PLAY_INTERVAL_SECONDS, DEFAULT_RANDOM_PLAY_INTERVAL_SECONDS)
        return saved.coerceIn(3, 300)
    }

    fun setRandomPlayIntervalSeconds(seconds: Int) {
        sharedPreferences.edit()
            .putInt(KEY_RANDOM_PLAY_INTERVAL_SECONDS, seconds.coerceIn(3, 300))
            .apply()
    }

    fun isMetronomeEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_METRONOME_ENABLED, false)
    }

    fun setMetronomeEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_METRONOME_ENABLED, enabled).apply()
    }

    fun getMetronomeIntervalSeconds(): Float {
        val saved = sharedPreferences.getFloat(KEY_METRONOME_INTERVAL_SECONDS, DEFAULT_METRONOME_INTERVAL_SECONDS)
        return saved.coerceIn(MIN_METRONOME_INTERVAL_SECONDS, MAX_METRONOME_INTERVAL_SECONDS)
    }

    fun setMetronomeIntervalSeconds(seconds: Float) {
        sharedPreferences.edit()
            .putFloat(KEY_METRONOME_INTERVAL_SECONDS, seconds.coerceIn(MIN_METRONOME_INTERVAL_SECONDS, MAX_METRONOME_INTERVAL_SECONDS))
            .apply()
    }

    fun getVideoSkipStride(): Int {
        val saved = sharedPreferences.getInt(KEY_VIDEO_SKIP_STRIDE, DEFAULT_VIDEO_SKIP_STRIDE)
        return if (saved <= 0) DEFAULT_VIDEO_SKIP_STRIDE else saved
    }

    fun setVideoSkipStride(skipStride: Int) {
        sharedPreferences.edit()
            .putInt(KEY_VIDEO_SKIP_STRIDE, skipStride.coerceAtLeast(1))
            .apply()
    }

    fun isVideoHighLoadEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_VIDEO_HIGH_LOAD_ENABLED, false)
    }

    fun setVideoHighLoadEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_VIDEO_HIGH_LOAD_ENABLED, enabled)
            .apply()
    }

    fun getAppTheme(): AppTheme {
        val saved = sharedPreferences.getString(KEY_APP_THEME, AppTheme.DEFAULT.prefValue)
        return AppTheme.fromPrefValue(saved)
    }

    fun setAppTheme(theme: AppTheme) {
        sharedPreferences.edit()
            .putString(KEY_APP_THEME, theme.prefValue)
            .apply()
    }

    fun getAppLanguage(): String {
        val saved = sharedPreferences.getString(KEY_APP_LANGUAGE, AppLanguageManager.FOLLOW_SYSTEM)
        return AppLanguageManager.sanitizePreference(saved)
    }

    fun setAppLanguage(language: String) {
        sharedPreferences.edit()
            .putString(KEY_APP_LANGUAGE, AppLanguageManager.sanitizePreference(language))
            .apply()
    }

    fun getCustomPalette(): CustomPalette {
        val base = sharedPreferences.getString(KEY_CUSTOM_THEME_BASE, "#000000") ?: "#000000"
        val primary = sharedPreferences.getString(KEY_CUSTOM_THEME_PRIMARY, "#FF3B30") ?: "#FF3B30"
        val accent = sharedPreferences.getString(KEY_CUSTOM_THEME_ACCENT, "#B026FF") ?: "#B026FF"
        return CustomPalette(base, primary, accent)
    }

    fun setCustomPalette(palette: CustomPalette) {
        sharedPreferences.edit()
            .putString(KEY_CUSTOM_THEME_BASE, palette.baseHex)
            .putString(KEY_CUSTOM_THEME_PRIMARY, palette.primaryHex)
            .putString(KEY_CUSTOM_THEME_ACCENT, palette.accentHex)
            .apply()
    }

    fun isViewerVerticalScrollEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_VIEWER_VERTICAL_SCROLL, false)
    }

    fun setViewerVerticalScrollEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_VIEWER_VERTICAL_SCROLL, enabled).apply()
    }

    fun getSelectedOutputFolder(): String {
        return sharedPreferences.getString(KEY_SELECTED_OUTPUT_FOLDER, FolderModels.SAFE_NET_DIR)
            ?: FolderModels.SAFE_NET_DIR
    }

    fun setSelectedOutputFolder(name: String) {
        sharedPreferences.edit().putString(KEY_SELECTED_OUTPUT_FOLDER, name).apply()
    }

    fun getCustomImageFolders(): List<String> {
        val value = sharedPreferences.getString(KEY_CUSTOM_IMAGE_FOLDERS, "").orEmpty()
        if (value.isBlank()) return emptyList()
        return value.split('\n').mapNotNull { item ->
            item.trim().takeIf { it.isNotBlank() }
        }
    }

    fun addCustomImageFolder(name: String) {
        val current = getCustomImageFolders().toMutableList()
        current.removeAll { it.equals(name, ignoreCase = true) }
        current.add(0, name)
        setCustomImageFolders(current)
    }

    fun renameCustomImageFolder(old: String, new: String) {
        val current = getCustomImageFolders().toMutableList()
        val idx = current.indexOfFirst { it.equals(old, ignoreCase = true) }
        if (idx >= 0) {
            current[idx] = new
            setCustomImageFolders(current)
        }
    }

    fun removeCustomImageFolder(name: String) {
        val current = getCustomImageFolders()
            .filterNot { it.equals(name, ignoreCase = true) }
        setCustomImageFolders(current)
    }

    private fun setCustomImageFolders(folders: List<String>) {
        val normalized = folders.map { it.trim() }.filter { it.isNotBlank() }
        sharedPreferences.edit()
            .putString(KEY_CUSTOM_IMAGE_FOLDERS, normalized.joinToString("\n"))
            .apply()
    }
}
