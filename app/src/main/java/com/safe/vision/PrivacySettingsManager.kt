package com.safe.vision

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashSet

/**
 * 隐私设置管理器，用于管理图片遮挡相关设置
 */
class PrivacySettingsManager private constructor(private val context: Context) {
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "privacy_settings"
        private const val KEY_BLUR_MODE = "blur_mode"
        private const val KEY_ANIME_BLUR_MODE = "anime_blur_mode"
        private const val KEY_BLOCKED_LABELS = "blocked_labels"
        private const val KEY_ANIME_BLOCKED_LABELS = "anime_blocked_labels"
        private const val KEY_STICKER_URI = "sticker_uri"
        private const val KEY_ANIME_STICKER_URI = "anime_sticker_uri"
        private const val KEY_LABEL_STICKER_URIS = "label_sticker_uris"
        private const val KEY_ANIME_LABEL_STICKER_URIS = "anime_label_sticker_uris"
        private const val KEY_LABEL_EFFECT_OVERRIDES = "label_effect_overrides"
        private const val KEY_ANIME_LABEL_EFFECT_OVERRIDES = "anime_label_effect_overrides"
        private const val KEY_REVERSE_LABELS = "reverse_labels"
        private const val KEY_ANIME_REVERSE_LABELS = "anime_reverse_labels"
        private const val KEY_EYE_MODE_LABELS = "eye_mode_labels"
        private const val KEY_ANIME_EYE_MODE_LABELS = "anime_eye_mode_labels"
        private const val KEY_MOSAIC_BLOCK_SIZE = "mosaic_block_size"
        private const val KEY_GAUSSIAN_RADIUS = "gaussian_radius"
        private const val KEY_MASK_SCALE = "mask_scale"
        private const val KEY_ANIME_MASK_SCALE = "anime_mask_scale"
        private const val KEY_LABEL_MASK_SCALES = "label_mask_scales"
        private const val KEY_ANIME_LABEL_MASK_SCALES = "anime_label_mask_scales"
        private const val KEY_CIRCULAR_MASK_ENABLED = "circular_mask_enabled"
        private const val KEY_MASK_OUTLINE_ENABLED = "mask_outline_enabled"
        private const val KEY_MASK_OUTLINE_LABELS = "mask_outline_labels"
        private const val KEY_ANIME_MASK_OUTLINE_LABELS = "anime_mask_outline_labels"
        private const val KEY_REVERSE_PRE_RENDER_ENABLED = "reverse_pre_render_enabled"
        private const val KEY_PRIVACY_PRESETS = "privacy_presets"
        private const val KEY_ACTIVE_PRESET_NAME = "active_preset_name"
        
        // 遮挡模式
        const val BLUR_MODE_MOSAIC = 0  // 马赛克
        const val BLUR_MODE_BLACK = 1   // 纯黑
        const val BLUR_MODE_GAUSSIAN = 2 // 高斯模糊
        const val BLUR_MODE_STICKER = 3 // 贴纸遮挡
        const val BLUR_MODE_SOBEL = 4 // 素描效果
        const val BLUR_MODE_EYES = 5 // 眼睛模式

        const val DEFAULT_STICKER_ASSET = "Default-stickers.png"

        const val MOSAIC_BLOCK_MIN = 12
        const val MOSAIC_BLOCK_MAX = 60
        const val MOSAIC_BLOCK_DEFAULT = 20
        const val GAUSSIAN_RADIUS_MIN = 6
        const val GAUSSIAN_RADIUS_MAX = 50
        const val GAUSSIAN_RADIUS_DEFAULT = 12
        const val MASK_SCALE_MIN = 1f
        const val MASK_SCALE_MAX = 3f
        const val MASK_SCALE_DEFAULT = 1f
        
        @Volatile
        private var INSTANCE: PrivacySettingsManager? = null
        
        fun getInstance(context: Context): PrivacySettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrivacySettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 获取当前遮挡模式
     */
    fun getBlurMode(): Int {
        return getBlurMode(getCurrentProfile())
    }

    fun getBlurMode(profile: DetectionConfig.LabelProfile): Int {
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_BLUR_MODE else KEY_BLUR_MODE
        return sharedPrefs.getInt(key, BLUR_MODE_MOSAIC)
    }
    
    /**
     * 设置遮挡模式
     */
    fun setBlurMode(mode: Int) {
        setBlurMode(getCurrentProfile(), mode)
    }

    fun setBlurMode(profile: DetectionConfig.LabelProfile, mode: Int) {
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_BLUR_MODE else KEY_BLUR_MODE
        sharedPrefs.edit().putInt(key, mode).apply()
    }

    fun setStickerUri(uri: String?) {
        sharedPrefs.edit().putString(KEY_STICKER_URI, uri).apply()
    }

    fun getStickerUri(): String? {
        return sharedPrefs.getString(KEY_STICKER_URI, null)
    }

    fun setAnimeStickerUri(uri: String?) {
        sharedPrefs.edit().putString(KEY_ANIME_STICKER_URI, uri).apply()
    }

    fun getAnimeStickerUri(): String? {
        return sharedPrefs.getString(KEY_ANIME_STICKER_URI, null)
    }

    fun setLabelStickerUri(label: String, uri: String?) {
        if (!DetectionConfig.LABELS.contains(label)) return
        val mapping = readLabelStickerUris(resolveProfileForLabel(label))
        if (uri.isNullOrBlank()) {
            mapping.remove(label)
        } else {
            mapping[label] = uri
        }
        writeLabelStickerUris(resolveProfileForLabel(label), mapping)
    }

    fun getLabelStickerUri(label: String): String? {
        return readLabelStickerUris(resolveProfileForLabel(label))[label]
    }

    fun getLabelStickerUris(): Map<String, String> {
        return readLabelStickerUris().toMap()
    }

    fun getLabelStickerUris(profile: DetectionConfig.LabelProfile): Map<String, String> {
        return readLabelStickerUris(profile).toMap()
    }

    fun getStickerUriForLabel(label: String?): String? {
        if (!label.isNullOrBlank()) {
            readLabelStickerUris(resolveProfileForLabel(label))[label]?.let { return it }
            return if (resolveProfileForLabel(label) == DetectionConfig.LabelProfile.ANIME) {
                getAnimeStickerUri()
            } else {
                getStickerUri()
            }
        }
        return getStickerUri()
    }

    fun getMosaicBlockSize(): Int {
        val stored = sharedPrefs.getInt(KEY_MOSAIC_BLOCK_SIZE, MOSAIC_BLOCK_DEFAULT)
        return stored.coerceIn(MOSAIC_BLOCK_MIN, MOSAIC_BLOCK_MAX)
    }

    fun setMosaicBlockSize(size: Int) {
        sharedPrefs.edit().putInt(KEY_MOSAIC_BLOCK_SIZE, size.coerceIn(MOSAIC_BLOCK_MIN, MOSAIC_BLOCK_MAX)).apply()
    }

    fun getGaussianRadius(): Int {
        val stored = sharedPrefs.getInt(KEY_GAUSSIAN_RADIUS, GAUSSIAN_RADIUS_DEFAULT)
        return stored.coerceIn(GAUSSIAN_RADIUS_MIN, GAUSSIAN_RADIUS_MAX)
    }

    fun setGaussianRadius(radius: Int) {
        sharedPrefs.edit().putInt(KEY_GAUSSIAN_RADIUS, radius.coerceIn(GAUSSIAN_RADIUS_MIN, GAUSSIAN_RADIUS_MAX)).apply()
    }

    fun getMaskScale(): Float {
        val stored = sharedPrefs.getFloat(KEY_MASK_SCALE, MASK_SCALE_DEFAULT)
        return stored.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX)
    }

    fun setMaskScale(scale: Float) {
        sharedPrefs.edit().putFloat(KEY_MASK_SCALE, scale.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX)).apply()
    }

    fun getAnimeMaskScale(): Float {
        val stored = sharedPrefs.getFloat(KEY_ANIME_MASK_SCALE, MASK_SCALE_DEFAULT)
        return stored.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX)
    }

    fun setAnimeMaskScale(scale: Float) {
        sharedPrefs.edit().putFloat(KEY_ANIME_MASK_SCALE, scale.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX)).apply()
    }

    fun getLabelMaskScaleOverride(label: String): Float? {
        if (!DetectionConfig.LABELS.contains(label)) return null
        return readLabelMaskScales(resolveProfileForLabel(label))[label]
    }

    fun setLabelMaskScaleOverride(label: String, scale: Float?) {
        if (!DetectionConfig.LABELS.contains(label)) return
        val profile = resolveProfileForLabel(label)
        val overrides = readLabelMaskScales(profile)
        if (scale == null) {
            overrides.remove(label)
        } else {
            overrides[label] = scale.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX)
        }
        writeLabelMaskScales(profile, overrides)
    }

    fun getLabelMaskScaleOverrides(): Map<String, Float> {
        return readLabelMaskScales().toMap()
    }

    fun getLabelMaskScaleOverrides(profile: DetectionConfig.LabelProfile): Map<String, Float> {
        return readLabelMaskScales(profile).toMap()
    }

    fun getEffectiveMaskScale(label: String): Float {
        return getLabelMaskScaleOverride(label) ?: if (resolveProfileForLabel(label) == DetectionConfig.LabelProfile.ANIME) {
            getAnimeMaskScale()
        } else {
            getMaskScale()
        }
    }

    fun isCircularMaskEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_CIRCULAR_MASK_ENABLED, false)
    }

    fun setCircularMaskEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_CIRCULAR_MASK_ENABLED, enabled).apply()
    }

    fun isMaskOutlineEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_MASK_OUTLINE_ENABLED, false)
    }

    fun setMaskOutlineEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_MASK_OUTLINE_ENABLED, enabled).apply()
    }

    fun getMaskOutlineLabels(): List<String> {
        return getMaskOutlineLabels(getCurrentProfile())
    }

    fun getMaskOutlineLabels(profile: DetectionConfig.LabelProfile): List<String> {
        val labelsForProfile = DetectionConfig.getLabels(profile).filterNot { isReverseLockedInProfile(it, profile) }
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_MASK_OUTLINE_LABELS else KEY_MASK_OUTLINE_LABELS
        val fallback = if (labelsForProfile.isEmpty()) DetectionConfig.getLabels(profile) else labelsForProfile
        val stored = sharedPrefs.getString(key, null) ?: return fallback
        return try {
            val jsonArray = JSONArray(stored)
            val labels = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val label = jsonArray.getString(i)
                if (DetectionConfig.getLabels(profile).contains(label)) {
                    labels.add(label)
                }
            }
            if (labels.isEmpty()) fallback else labels
        } catch (e: Exception) {
            fallback
        }
    }

    fun setMaskOutlineLabels(labels: List<String>) {
        setMaskOutlineLabels(getCurrentProfile(), labels)
    }

    fun setMaskOutlineLabels(profile: DetectionConfig.LabelProfile, labels: List<String>) {
        val allowed = DetectionConfig.getLabels(profile)
        val sanitized = labels.filter { allowed.contains(it) }
        val finalLabels = if (sanitized.isEmpty()) allowed else sanitized
        val jsonArray = JSONArray()
        finalLabels.forEach { jsonArray.put(it) }
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_MASK_OUTLINE_LABELS else KEY_MASK_OUTLINE_LABELS
        sharedPrefs.edit().putString(key, jsonArray.toString()).apply()
    }

    fun isMaskOutlineEnabledForLabel(label: String): Boolean {
        if (!isMaskOutlineEnabled()) return false
        val allowed = getMaskOutlineLabels()
        return allowed.isEmpty() || allowed.contains(label)
    }

    fun isReversePreRenderEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_REVERSE_PRE_RENDER_ENABLED, true)
    }

    fun setReversePreRenderEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_REVERSE_PRE_RENDER_ENABLED, enabled).apply()
    }

    data class PrivacyPreset(
        val name: String,
        val blurMode: Int,
        val animeBlurMode: Int,
        val circularMaskEnabled: Boolean,
        val maskOutlineEnabled: Boolean,
        val mosaicBlockSize: Int,
        val gaussianRadius: Int,
        val maskScale: Float,
        val animeMaskScale: Float,
        val stickerUri: String?,
        val animeStickerUri: String?,
        val labelStickerUris: Map<String, String>,
        val labelMaskScales: Map<String, Float>,
        val blockedLabels: List<String>,
        val labelEffectOverrides: Map<String, Int>,
        val reverseLabels: List<String>,
        val eyeModeLabels: List<String>,
        val maskOutlineLabels: List<String>,
        val reversePreRenderEnabled: Boolean
    )

    data class ImportResult(
        val importedNames: List<String>,
        val skippedNames: List<String>
    ) {
        val importedCount: Int
            get() = importedNames.size
    }
    
    /**
     * 获取需要遮挡的标签列表
     */
    fun getBlockedLabels(): List<String> {
        return getBlockedLabels(getCurrentProfile())
    }

    fun getBlockedLabels(profile: DetectionConfig.LabelProfile): List<String> {
        val storedLabels = readStoredBlockedLabels(profile) ?: getLockedLabels(profile)
        return mergeWithLockedLabels(profile, storedLabels)
    }
    
    /**
     * 设置需要遮挡的标签列表
     */
    fun setBlockedLabels(labels: List<String>) {
        setBlockedLabels(getCurrentProfile(), labels)
    }

    fun setBlockedLabels(profile: DetectionConfig.LabelProfile, labels: List<String>) {
        val jsonArray = JSONArray()
        mergeWithLockedLabels(profile, labels).forEach { jsonArray.put(it) }
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_BLOCKED_LABELS else KEY_BLOCKED_LABELS
        sharedPrefs.edit().putString(key, jsonArray.toString()).apply()
    }
    
    /**
     * 检查指定标签是否需要遮挡
     */
    fun isLabelBlocked(label: String): Boolean {
        return getBlockedLabels(resolveProfileForLabel(label)).contains(label)
    }

    fun isLabelBlocked(label: String, profile: DetectionConfig.LabelProfile): Boolean {
        return getBlockedLabels(profile).contains(label)
    }

    fun isLabelLocked(label: String): Boolean {
        return isLabelDisableLocked(label)
    }

    fun isLabelDisableLocked(label: String): Boolean {
        val profile = resolveProfileForLabel(label)
        return isDisableLockedInProfile(label, profile)
    }

    fun isLabelReverseLocked(label: String): Boolean {
        val profile = resolveProfileForLabel(label)
        return isReverseLockedInProfile(label, profile)
    }
    
    /**
     * 添加需要遮挡的标签
     */
    fun addBlockedLabel(label: String) {
        val currentLabels = getBlockedLabels(resolveProfileForLabel(label)).toMutableList()
        if (!currentLabels.contains(label)) {
            currentLabels.add(label)
            setBlockedLabels(resolveProfileForLabel(label), currentLabels)
        }
    }
    
    /**
     * 移除需要遮挡的标签
     */
    fun removeBlockedLabel(label: String) {
        val currentLabels = getBlockedLabels(resolveProfileForLabel(label)).toMutableList()
        if (currentLabels.contains(label)) {
            currentLabels.remove(label)
            setBlockedLabels(resolveProfileForLabel(label), currentLabels)
        }
    }

    fun setLabelEnabled(label: String, enabled: Boolean) {
        if (!enabled && isLabelDisableLocked(label)) {
            return
        }
        val profile = resolveProfileForLabel(label)
        val currentLabels = LinkedHashSet(getBlockedLabels(profile))
        if (enabled) {
            currentLabels.add(label)
        } else {
            currentLabels.remove(label)
        }
        setBlockedLabels(profile, currentLabels.toList())
        if (!enabled) {
            setLabelReverse(label, false)
        }
    }

    fun getLabelEffectOverride(label: String): Int? {
        return readLabelEffectOverrides(resolveProfileForLabel(label))[label]
    }

    fun getLabelEffectOverrides(): Map<String, Int> {
        return readLabelEffectOverrides().toMap()
    }

    fun getLabelEffectOverrides(profile: DetectionConfig.LabelProfile): Map<String, Int> {
        return readLabelEffectOverrides(profile).toMap()
    }

    fun setLabelEffectOverride(label: String, mode: Int?) {
        val profile = resolveProfileForLabel(label)
        val overrides = readLabelEffectOverrides(profile)
        if (mode == null || !isValidBlurMode(mode)) {
            overrides.remove(label)
        } else {
            overrides[label] = mode
        }
        writeLabelEffectOverrides(profile, overrides)
    }

    fun getEffectiveBlurMode(label: String): Int {
        val profile = resolveProfileForLabel(label)
        return getLabelEffectOverride(label) ?: getBlurMode(profile)
    }

    fun getReverseLabels(): List<String> {
        return getReverseLabels(getCurrentProfile())
    }

    fun getReverseLabels(profile: DetectionConfig.LabelProfile): List<String> {
        val rawBlocked = (readStoredBlockedLabels(profile) ?: emptyList()).toSet()
        val labelsJson = readStoredReverseLabels(profile) ?: return emptyList()
        return try {
            val labels = mutableListOf<String>()
            labelsJson.forEach { label ->
                if (!isReverseLockedInProfile(label, profile) && rawBlocked.contains(label)) {
                    labels.add(label)
                }
            }
            labels.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isLabelReverse(label: String): Boolean {
        if (isLabelReverseLocked(label)) return false
        return getReverseLabels(resolveProfileForLabel(label)).contains(label)
    }

    fun setLabelReverse(label: String, enabled: Boolean) {
        if (isLabelReverseLocked(label)) {
            return
        }
        val profile = resolveProfileForLabel(label)
        val currentLabels = LinkedHashSet(getReverseLabels(profile))
        if (enabled) {
            currentLabels.add(label)
        } else {
            currentLabels.remove(label)
        }
        setReverseLabels(profile, currentLabels.toList())
    }

    fun getEyeModeLabels(): List<String> {
        return getEyeModeLabels(getCurrentProfile())
    }

    fun getEyeModeLabels(profile: DetectionConfig.LabelProfile): List<String> {
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_EYE_MODE_LABELS else KEY_EYE_MODE_LABELS
        val labelsJson = sharedPrefs.getString(key, null)
            ?: return if (profile == DetectionConfig.LabelProfile.ANIME) emptyList() else DetectionConfig.STANDARD_FACE_LABELS.toList()
        return try {
            val jsonArray = JSONArray(labelsJson)
            val labels = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val label = jsonArray.getString(i)
                if (DetectionConfig.getLabels(profile).contains(label) && DetectionConfig.FACE_LABELS.contains(label)) {
                    labels.add(label)
                }
            }
            labels.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isLabelEyeMode(label: String): Boolean {
        return getEyeModeLabels(resolveProfileForLabel(label)).contains(label)
    }

    fun setLabelEyeMode(label: String, enabled: Boolean) {
        if (!DetectionConfig.FACE_LABELS.contains(label)) {
            return
        }
        val profile = resolveProfileForLabel(label)
        val currentLabels = LinkedHashSet(getEyeModeLabels(profile))
        if (enabled) {
            currentLabels.add(label)
        } else {
            currentLabels.remove(label)
        }
        setEyeModeLabels(profile, currentLabels.toList())
    }
    
    /**
     * 获取所有可用的标签选项
     */
    fun getAllAvailableLabels(): List<String> {
        return DetectionConfig.LABELS
    }

    fun getLabelDisplayName(label: String): String {
        return DetectionConfig.getDisplayName(label)
    }

    fun getDisplayNames(labels: List<String>): List<String> {
        return labels.map { getLabelDisplayName(it) }
    }

    fun getActivePresetName(): String? {
        val value = sharedPrefs.getString(KEY_ACTIVE_PRESET_NAME, null)
        return if (value.isNullOrBlank()) null else value
    }

    fun setActivePresetName(name: String?) {
        sharedPrefs.edit().putString(KEY_ACTIVE_PRESET_NAME, name?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    fun listPresetNames(): List<String> {
        return readPresets().keys.toList()
    }

    fun getPreset(name: String): PrivacyPreset? {
        return readPresets()[name]
    }

    fun savePreset(name: String): PrivacyPreset {
        val finalName = name.trim()
        require(finalName.isNotEmpty()) { "Preset name cannot be empty" }
        val all = readPresets().toMutableMap()
        val snapshot = captureCurrentSnapshot(finalName)
        all[finalName] = snapshot
        writePresets(all)
        setActivePresetName(finalName)
        return snapshot
    }

    fun applyPreset(name: String): Boolean {
        val preset = readPresets()[name] ?: return false
        applySnapshot(preset)
        setActivePresetName(name)
        return true
    }

    fun deletePreset(name: String): Boolean {
        val finalName = name.trim()
        if (finalName.isEmpty()) return false
        val all = readPresets().toMutableMap()
        val removed = all.remove(finalName) != null
        if (!removed) return false
        writePresets(all)
        if (getActivePresetName() == finalName) {
            setActivePresetName(null)
        }
        return true
    }

    fun buildPresetPackageJson(names: List<String>): String? {
        val sanitized = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (sanitized.isEmpty()) return null
        val all = readPresets()
        val selected = linkedMapOf<String, PrivacyPreset>()
        sanitized.forEach { name ->
            all[name]?.let { selected[name] = it }
        }
        if (selected.isEmpty()) return null
        val order = JSONArray()
        val data = JSONObject()
        selected.forEach { (name, preset) ->
            order.put(name)
            data.put(name, serializePreset(preset))
        }
        val root = JSONObject()
            .put("packageType", "safe_vision_privacy_preset")
            .put("packageVersion", 1)
            .put("order", order)
            .put("data", data)
        return root.toString()
    }

    fun importPresetPackageJson(json: String): ImportResult {
        val all = readPresets().toMutableMap()
        val importedNames = mutableListOf<String>()
        val skippedNames = mutableListOf<String>()
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return ImportResult(emptyList(), emptyList())
        }
        val order = root.optJSONArray("order") ?: JSONArray()
        val data = root.optJSONObject("data") ?: JSONObject()
        for (i in 0 until order.length()) {
            val name = order.optString(i).trim()
            if (name.isEmpty()) continue
            val presetObj = data.optJSONObject(name)
            val preset = if (presetObj == null) null else parsePreset(name, presetObj)
            if (preset == null) {
                skippedNames.add(name)
                continue
            }
            all[name] = preset
            importedNames.add(name)
        }
        if (importedNames.isNotEmpty()) {
            writePresets(all)
        }
        return ImportResult(importedNames, skippedNames)
    }
    
    /**
     * 获取遮挡模式的显示名称
     */
    fun getBlurModeName(mode: Int): String {
        return when (mode) {
            BLUR_MODE_MOSAIC -> "马赛克"
            BLUR_MODE_BLACK -> "纯黑遮挡"
            BLUR_MODE_GAUSSIAN -> "高斯模糊"
            BLUR_MODE_STICKER -> "遮挡贴纸"
            BLUR_MODE_SOBEL -> "素描效果"
            BLUR_MODE_EYES -> "眼睛模式"
            else -> "未知"
        }
    }

    private fun isValidBlurMode(mode: Int): Boolean {
        return mode in BLUR_MODE_MOSAIC..BLUR_MODE_EYES
    }

    private fun getCurrentProfile(): DetectionConfig.LabelProfile {
        return if (AppSettingsManager.getInstance(context).getDetectionModelVariant() == DetectionModelVariant.ANIME) {
            DetectionConfig.LabelProfile.ANIME
        } else {
            DetectionConfig.LabelProfile.STANDARD
        }
    }

    private fun resolveProfileForLabel(label: String): DetectionConfig.LabelProfile {
        return when {
            DetectionConfig.ANIME_LABELS.contains(label) -> DetectionConfig.LabelProfile.ANIME
            else -> DetectionConfig.LabelProfile.STANDARD
        }
    }

    private fun getLockedLabels(profile: DetectionConfig.LabelProfile): List<String> {
        return DetectionConfig.getLockedLabels(profile).filter { isDisableLockedInProfile(it, profile) }
    }

    private fun isDisableLockedInProfile(label: String, profile: DetectionConfig.LabelProfile): Boolean {
        return DetectionConfig.getLockedLabels(profile).contains(label) && !hasReverseOverride(profile)
    }

    private fun isReverseLockedInProfile(label: String, profile: DetectionConfig.LabelProfile): Boolean {
        return DetectionConfig.getLockedLabels(profile).contains(label)
    }

    private fun hasReverseOverride(profile: DetectionConfig.LabelProfile): Boolean {
        return getReverseLabels(profile).isNotEmpty()
    }

    private fun captureCurrentSnapshot(name: String): PrivacyPreset {
        return PrivacyPreset(
            name = name,
            blurMode = getBlurMode(DetectionConfig.LabelProfile.STANDARD),
            animeBlurMode = getBlurMode(DetectionConfig.LabelProfile.ANIME),
            circularMaskEnabled = isCircularMaskEnabled(),
            maskOutlineEnabled = isMaskOutlineEnabled(),
            mosaicBlockSize = getMosaicBlockSize(),
            gaussianRadius = getGaussianRadius(),
            maskScale = getMaskScale(),
            animeMaskScale = getAnimeMaskScale(),
            stickerUri = getStickerUri(),
            animeStickerUri = getAnimeStickerUri(),
            labelStickerUris = mergeLabelMaps(
                getLabelStickerUris(DetectionConfig.LabelProfile.STANDARD),
                getLabelStickerUris(DetectionConfig.LabelProfile.ANIME)
            ),
            labelMaskScales = mergeLabelMaps(
                getLabelMaskScaleOverrides(DetectionConfig.LabelProfile.STANDARD),
                getLabelMaskScaleOverrides(DetectionConfig.LabelProfile.ANIME)
            ),
            blockedLabels = mergeLabelLists(
                getBlockedLabels(DetectionConfig.LabelProfile.STANDARD),
                getBlockedLabels(DetectionConfig.LabelProfile.ANIME)
            ),
            labelEffectOverrides = mergeLabelMaps(
                getLabelEffectOverrides(DetectionConfig.LabelProfile.STANDARD),
                getLabelEffectOverrides(DetectionConfig.LabelProfile.ANIME)
            ),
            reverseLabels = mergeLabelLists(
                getReverseLabels(DetectionConfig.LabelProfile.STANDARD),
                getReverseLabels(DetectionConfig.LabelProfile.ANIME)
            ),
            eyeModeLabels = mergeLabelLists(
                getEyeModeLabels(DetectionConfig.LabelProfile.STANDARD),
                getEyeModeLabels(DetectionConfig.LabelProfile.ANIME)
            ),
            maskOutlineLabels = mergeLabelLists(
                getMaskOutlineLabels(DetectionConfig.LabelProfile.STANDARD),
                getMaskOutlineLabels(DetectionConfig.LabelProfile.ANIME)
            ),
            reversePreRenderEnabled = isReversePreRenderEnabled()
        )
    }

    private fun applySnapshot(preset: PrivacyPreset) {
        val standardBlocked = filterLabelsForProfile(preset.blockedLabels, DetectionConfig.LabelProfile.STANDARD)
        val animeBlocked = filterLabelsForProfile(preset.blockedLabels, DetectionConfig.LabelProfile.ANIME)
        val standardReverse = filterLabelsForProfile(preset.reverseLabels, DetectionConfig.LabelProfile.STANDARD)
        val animeReverse = filterLabelsForProfile(preset.reverseLabels, DetectionConfig.LabelProfile.ANIME)

        setBlurMode(DetectionConfig.LabelProfile.STANDARD, preset.blurMode)
        setBlurMode(DetectionConfig.LabelProfile.ANIME, preset.animeBlurMode)
        setCircularMaskEnabled(preset.circularMaskEnabled)
        setMaskOutlineEnabled(preset.maskOutlineEnabled)
        setMosaicBlockSize(preset.mosaicBlockSize)
        setGaussianRadius(preset.gaussianRadius)
        setMaskScale(preset.maskScale)
        setAnimeMaskScale(preset.animeMaskScale)
        setStickerUri(preset.stickerUri)
        setAnimeStickerUri(preset.animeStickerUri)
        writeLabelStickerUris(
            DetectionConfig.LabelProfile.STANDARD,
            filterMapForProfile(preset.labelStickerUris, DetectionConfig.LabelProfile.STANDARD)
        )
        writeLabelStickerUris(
            DetectionConfig.LabelProfile.ANIME,
            filterMapForProfile(preset.labelStickerUris, DetectionConfig.LabelProfile.ANIME)
        )
        writeLabelMaskScales(
            DetectionConfig.LabelProfile.STANDARD,
            filterMapForProfile(preset.labelMaskScales, DetectionConfig.LabelProfile.STANDARD)
        )
        writeLabelMaskScales(
            DetectionConfig.LabelProfile.ANIME,
            filterMapForProfile(preset.labelMaskScales, DetectionConfig.LabelProfile.ANIME)
        )
        writeStoredBlockedLabels(DetectionConfig.LabelProfile.STANDARD, standardBlocked)
        writeStoredBlockedLabels(DetectionConfig.LabelProfile.ANIME, animeBlocked)
        writeLabelEffectOverrides(
            DetectionConfig.LabelProfile.STANDARD,
            filterMapForProfile(preset.labelEffectOverrides, DetectionConfig.LabelProfile.STANDARD)
        )
        writeLabelEffectOverrides(
            DetectionConfig.LabelProfile.ANIME,
            filterMapForProfile(preset.labelEffectOverrides, DetectionConfig.LabelProfile.ANIME)
        )
        setReverseLabels(DetectionConfig.LabelProfile.STANDARD, standardReverse)
        setReverseLabels(DetectionConfig.LabelProfile.ANIME, animeReverse)
        setEyeModeLabels(
            DetectionConfig.LabelProfile.STANDARD,
            filterLabelsForProfile(preset.eyeModeLabels, DetectionConfig.LabelProfile.STANDARD)
        )
        setEyeModeLabels(
            DetectionConfig.LabelProfile.ANIME,
            filterLabelsForProfile(preset.eyeModeLabels, DetectionConfig.LabelProfile.ANIME)
        )
        setMaskOutlineLabels(
            DetectionConfig.LabelProfile.STANDARD,
            filterLabelsForProfile(preset.maskOutlineLabels, DetectionConfig.LabelProfile.STANDARD)
        )
        setMaskOutlineLabels(
            DetectionConfig.LabelProfile.ANIME,
            filterLabelsForProfile(preset.maskOutlineLabels, DetectionConfig.LabelProfile.ANIME)
        )
        setReversePreRenderEnabled(preset.reversePreRenderEnabled)
    }

    private fun readPresets(): LinkedHashMap<String, PrivacyPreset> {
        val json = sharedPrefs.getString(KEY_PRIVACY_PRESETS, null) ?: return linkedMapOf()
        return try {
            val root = JSONObject(json)
            val names = root.optJSONArray("order") ?: JSONArray()
            val data = root.optJSONObject("data") ?: JSONObject()
            val parsed = linkedMapOf<String, PrivacyPreset>()
            for (i in 0 until names.length()) {
                val name = names.optString(i).trim()
                if (name.isEmpty()) continue
                val obj = data.optJSONObject(name) ?: continue
                parsePreset(name, obj)?.let { parsed[name] = it }
            }
            parsed
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    private fun parsePreset(name: String, obj: JSONObject): PrivacyPreset? {
        val blurMode = obj.optInt("blurMode", BLUR_MODE_MOSAIC)
        if (!isValidBlurMode(blurMode)) return null
        val animeBlurMode = obj.optInt("animeBlurMode", blurMode)
        if (!isValidBlurMode(animeBlurMode)) return null
        val stickerUri = obj.opt("stickerUri")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        val animeStickerUri = obj.opt("animeStickerUri")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: stickerUri
        return PrivacyPreset(
            name = name,
            blurMode = blurMode,
            animeBlurMode = animeBlurMode,
            circularMaskEnabled = obj.optBoolean("circularMaskEnabled", false),
            maskOutlineEnabled = obj.optBoolean("maskOutlineEnabled", false),
            mosaicBlockSize = obj.optInt("mosaicBlockSize", MOSAIC_BLOCK_DEFAULT).coerceIn(MOSAIC_BLOCK_MIN, MOSAIC_BLOCK_MAX),
            gaussianRadius = obj.optInt("gaussianRadius", GAUSSIAN_RADIUS_DEFAULT).coerceIn(GAUSSIAN_RADIUS_MIN, GAUSSIAN_RADIUS_MAX),
            maskScale = obj.optDouble("maskScale", MASK_SCALE_DEFAULT.toDouble()).toFloat()
                .coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX),
            animeMaskScale = obj.optDouble("animeMaskScale", obj.optDouble("maskScale", MASK_SCALE_DEFAULT.toDouble())).toFloat()
                .coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX),
            stickerUri = stickerUri,
            animeStickerUri = animeStickerUri,
            labelStickerUris = parseLabelStickerUris(obj.optJSONObject("labelStickerUris")),
            labelMaskScales = parseScaleOverrides(obj.optJSONObject("labelMaskScales")),
            blockedLabels = parseLabelList(
                obj.optJSONArray("blockedLabels"),
                includeLocked = true,
                default = emptyList()
            ),
            labelEffectOverrides = parseOverrides(obj.optJSONObject("labelEffectOverrides")),
            reverseLabels = parseLabelList(obj.optJSONArray("reverseLabels"), includeLocked = false, default = emptyList()),
            eyeModeLabels = parseFaceLabelList(
                obj.optJSONArray("eyeModeLabels"),
                DetectionConfig.STANDARD_FACE_LABELS.toList()
            ),
            maskOutlineLabels = parseLabelList(obj.optJSONArray("maskOutlineLabels"), includeLocked = true, default = DetectionConfig.LABELS),
            reversePreRenderEnabled = obj.optBoolean("reversePreRenderEnabled", true)
        )
    }

    private fun parseLabelList(array: JSONArray?, includeLocked: Boolean, default: List<String>): List<String> {
        if (array == null) return default
        val out = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            val label = array.optString(i)
            if (!DetectionConfig.LABELS.contains(label)) continue
            if (!includeLocked && DetectionConfig.LOCKED_LABELS.contains(label)) continue
            out.add(label)
        }
        if (out.isEmpty()) return default
        return out.toList()
    }

    private fun parseFaceLabelList(array: JSONArray?, default: List<String> = emptyList()): List<String> {
        if (array == null) return default
        val out = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            val label = array.optString(i)
            if (DetectionConfig.FACE_LABELS.contains(label)) out.add(label)
        }
        return if (out.isEmpty()) default else out.toList()
    }

    private fun parseOverrides(obj: JSONObject?): Map<String, Int> {
        if (obj == null) return emptyMap()
        val overrides = mutableMapOf<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val mode = obj.optInt(key, Int.MIN_VALUE)
            if (DetectionConfig.LABELS.contains(key) && isValidBlurMode(mode)) {
                overrides[key] = mode
            }
        }
        return overrides
    }

    private fun parseLabelStickerUris(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optString(key, "").trim()
            if (DetectionConfig.LABELS.contains(key) && value.isNotEmpty() && value != "null") {
                out[key] = value
            }
        }
        return out
    }

    private fun parseScaleOverrides(obj: JSONObject?): Map<String, Float> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<String, Float>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optDouble(key, Double.NaN).toFloat()
            if (DetectionConfig.LABELS.contains(key) && value.isFinite()) {
                out[key] = value.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX)
            }
        }
        return out
    }

    private fun writePresets(presets: Map<String, PrivacyPreset>) {
        val order = JSONArray()
        val data = JSONObject()
        presets.forEach { (name, preset) ->
            order.put(name)
            data.put(name, serializePreset(preset))
        }
        val root = JSONObject()
            .put("order", order)
            .put("data", data)
        sharedPrefs.edit().putString(KEY_PRIVACY_PRESETS, root.toString()).apply()
    }

    private fun serializePreset(preset: PrivacyPreset): JSONObject {
        val blocked = JSONArray().apply { preset.blockedLabels.forEach { put(it) } }
        val reverse = JSONArray().apply { preset.reverseLabels.forEach { put(it) } }
        val eye = JSONArray().apply { preset.eyeModeLabels.forEach { put(it) } }
        val outline = JSONArray().apply { preset.maskOutlineLabels.forEach { put(it) } }
        val overrides = JSONObject().apply {
            preset.labelEffectOverrides.forEach { (label, mode) -> put(label, mode) }
        }
        return JSONObject()
            .put("blurMode", preset.blurMode)
            .put("animeBlurMode", preset.animeBlurMode)
            .put("circularMaskEnabled", preset.circularMaskEnabled)
            .put("maskOutlineEnabled", preset.maskOutlineEnabled)
            .put("mosaicBlockSize", preset.mosaicBlockSize)
            .put("gaussianRadius", preset.gaussianRadius)
            .put("maskScale", preset.maskScale)
            .put("animeMaskScale", preset.animeMaskScale)
            .put("stickerUri", preset.stickerUri)
            .put("animeStickerUri", preset.animeStickerUri)
            .put("labelStickerUris", JSONObject().apply {
                preset.labelStickerUris.forEach { (label, uri) -> put(label, uri) }
            })
            .put("labelMaskScales", JSONObject().apply {
                preset.labelMaskScales.forEach { (label, scale) -> put(label, scale.toDouble()) }
            })
            .put("blockedLabels", blocked)
            .put("labelEffectOverrides", overrides)
            .put("reverseLabels", reverse)
            .put("eyeModeLabels", eye)
            .put("maskOutlineLabels", outline)
            .put("reversePreRenderEnabled", preset.reversePreRenderEnabled)
    }

    private fun <T> mergeLabelMaps(vararg maps: Map<String, T>): Map<String, T> {
        val merged = linkedMapOf<String, T>()
        maps.forEach { map -> map.forEach { (label, value) -> merged[label] = value } }
        return merged
    }

    private fun mergeLabelLists(vararg lists: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        lists.forEach { list -> list.forEach { merged.add(it) } }
        return merged.toList()
    }

    private fun filterLabelsForProfile(labels: List<String>, profile: DetectionConfig.LabelProfile): List<String> {
        val allowed = DetectionConfig.getLabels(profile).toSet()
        return labels.filter { allowed.contains(it) }.distinct()
    }

    private fun <T> filterMapForProfile(
        mapping: Map<String, T>,
        profile: DetectionConfig.LabelProfile
    ): Map<String, T> {
        val allowed = DetectionConfig.getLabels(profile).toSet()
        return mapping.filterKeys { allowed.contains(it) }
    }

    private fun mergeWithLockedLabels(profile: DetectionConfig.LabelProfile, labels: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        getLockedLabels(profile).forEach { merged.add(it) }
        labels.forEach { merged.add(it) }
        return merged.toList()
    }

    private fun readStoredBlockedLabels(profile: DetectionConfig.LabelProfile): List<String>? {
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_BLOCKED_LABELS else KEY_BLOCKED_LABELS
        val labelsJson = sharedPrefs.getString(key, null) ?: return null
        return try {
            val jsonArray = JSONArray(labelsJson)
            val labels = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                labels.add(jsonArray.getString(i))
            }
            labels.toList()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeStoredBlockedLabels(profile: DetectionConfig.LabelProfile, labels: List<String>) {
        val allowed = DetectionConfig.getLabels(profile).toSet()
        val jsonArray = JSONArray()
        labels.filter { allowed.contains(it) }.distinct().forEach { jsonArray.put(it) }
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_BLOCKED_LABELS else KEY_BLOCKED_LABELS
        sharedPrefs.edit().putString(key, jsonArray.toString()).apply()
    }

    private fun readStoredReverseLabels(profile: DetectionConfig.LabelProfile): List<String>? {
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_REVERSE_LABELS else KEY_REVERSE_LABELS
        val labelsJson = sharedPrefs.getString(key, null) ?: return null
        return try {
            val jsonArray = JSONArray(labelsJson)
            val labels = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                labels.add(jsonArray.getString(i))
            }
            labels.toList()
        } catch (_: Exception) {
            null
        }
    }

    private fun readLabelEffectOverrides(): MutableMap<String, Int> {
        return readLabelEffectOverrides(getCurrentProfile())
    }

    private fun readLabelEffectOverrides(profile: DetectionConfig.LabelProfile): MutableMap<String, Int> {
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_LABEL_EFFECT_OVERRIDES else KEY_LABEL_EFFECT_OVERRIDES
        val json = sharedPrefs.getString(key, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(json)
            val overrides = mutableMapOf<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optInt(key, Int.MIN_VALUE)
                if (DetectionConfig.getLabels(profile).contains(key) && isValidBlurMode(value)) {
                    overrides[key] = value
                }
            }
            overrides
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun writeLabelEffectOverrides(overrides: Map<String, Int>) {
        writeLabelEffectOverrides(getCurrentProfile(), overrides)
    }

    private fun writeLabelEffectOverrides(profile: DetectionConfig.LabelProfile, overrides: Map<String, Int>) {
        val obj = JSONObject()
        overrides.forEach { (label, mode) ->
            if (DetectionConfig.getLabels(profile).contains(label)) {
                obj.put(label, mode)
            }
        }
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_LABEL_EFFECT_OVERRIDES else KEY_LABEL_EFFECT_OVERRIDES
        sharedPrefs.edit().putString(key, obj.toString()).apply()
    }

    private fun setReverseLabels(profile: DetectionConfig.LabelProfile, labels: List<String>) {
        val jsonArray = JSONArray()
        labels.filterNot { isReverseLockedInProfile(it, profile) }.forEach { jsonArray.put(it) }
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_REVERSE_LABELS else KEY_REVERSE_LABELS
        sharedPrefs.edit().putString(key, jsonArray.toString()).apply()
        normalizeProtectedLabelState(profile)
    }

    private fun normalizeProtectedLabelState(profile: DetectionConfig.LabelProfile) {
        if (hasReverseOverride(profile)) return
        val protected = DetectionConfig.getLockedLabels(profile)
        if (protected.isEmpty()) return
        val currentLabels = LinkedHashSet(readStoredBlockedLabels(profile) ?: emptyList())
        val updated = LinkedHashSet(currentLabels)
        protected.forEach { updated.add(it) }
        if (updated != currentLabels) {
            setBlockedLabels(profile, updated.toList())
        }
    }

    private fun setEyeModeLabels(profile: DetectionConfig.LabelProfile, labels: List<String>) {
        val jsonArray = JSONArray()
        labels
            .filter { DetectionConfig.getLabels(profile).contains(it) && DetectionConfig.FACE_LABELS.contains(it) }
            .forEach { jsonArray.put(it) }
        val key = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_EYE_MODE_LABELS else KEY_EYE_MODE_LABELS
        sharedPrefs.edit().putString(key, jsonArray.toString()).apply()
    }

    private fun readLabelStickerUris(): MutableMap<String, String> {
        return readLabelStickerUris(getCurrentProfile())
    }

    private fun readLabelStickerUris(profile: DetectionConfig.LabelProfile): MutableMap<String, String> {
        val keyName = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_LABEL_STICKER_URIS else KEY_LABEL_STICKER_URIS
        val json = sharedPrefs.getString(keyName, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(json)
            val out = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "").trim()
                if (DetectionConfig.getLabels(profile).contains(key) && value.isNotEmpty() && value != "null") {
                    out[key] = value
                }
            }
            out
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeLabelStickerUris(mapping: Map<String, String>) {
        writeLabelStickerUris(getCurrentProfile(), mapping)
    }

    private fun writeLabelStickerUris(profile: DetectionConfig.LabelProfile, mapping: Map<String, String>) {
        val obj = JSONObject()
        mapping.forEach { (label, uri) ->
            if (DetectionConfig.getLabels(profile).contains(label) && uri.isNotBlank()) {
                obj.put(label, uri)
            }
        }
        val keyName = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_LABEL_STICKER_URIS else KEY_LABEL_STICKER_URIS
        sharedPrefs.edit().putString(keyName, obj.toString()).apply()
    }

    private fun readLabelMaskScales(): MutableMap<String, Float> {
        return readLabelMaskScales(getCurrentProfile())
    }

    private fun readLabelMaskScales(profile: DetectionConfig.LabelProfile): MutableMap<String, Float> {
        val keyName = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_LABEL_MASK_SCALES else KEY_LABEL_MASK_SCALES
        val json = sharedPrefs.getString(keyName, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(json)
            val out = mutableMapOf<String, Float>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optDouble(key, Double.NaN).toFloat()
                if (DetectionConfig.getLabels(profile).contains(key) && value.isFinite()) {
                    out[key] = value.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX)
                }
            }
            out
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeLabelMaskScales(mapping: Map<String, Float>) {
        writeLabelMaskScales(getCurrentProfile(), mapping)
    }

    private fun writeLabelMaskScales(profile: DetectionConfig.LabelProfile, mapping: Map<String, Float>) {
        val obj = JSONObject()
        mapping.forEach { (label, scale) ->
            if (DetectionConfig.getLabels(profile).contains(label)) {
                obj.put(label, scale.coerceIn(MASK_SCALE_MIN, MASK_SCALE_MAX).toDouble())
            }
        }
        val keyName = if (profile == DetectionConfig.LabelProfile.ANIME) KEY_ANIME_LABEL_MASK_SCALES else KEY_LABEL_MASK_SCALES
        sharedPrefs.edit().putString(keyName, obj.toString()).apply()
    }
}
