package com.safe.vision

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import androidx.appcompat.view.ContextThemeWrapper
import androidx.annotation.StyleRes
import androidx.core.graphics.ColorUtils

enum class AppTheme(val prefValue: String, @StyleRes val styleRes: Int, val labelRes: Int) {
    DEFAULT("default", R.style.Theme_SafeVision, R.string.settings_theme_default),
    BLACK_RED("black_red", R.style.Theme_SafeVision_BlackRed, R.string.settings_theme_default),
    PASTEL("pastel", R.style.Theme_SafeVision_Pastel, R.string.settings_theme_pastel),
    DEEP_SEA("deep_sea", R.style.Theme_SafeVision_DeepSea, R.string.settings_theme_deep_sea),
    CUSTOM("custom", R.style.Theme_SafeVision_Custom, R.string.settings_theme_custom);

    companion object {
        fun fromPrefValue(value: String?): AppTheme {
            return values().firstOrNull { it.prefValue == value } ?: DEFAULT
        }
    }
}

object ThemeManager {
    fun applyTheme(activity: Activity, theme: AppTheme) {
        activity.setTheme(theme.styleRes)
    }

    fun wrapContextWithCustomColors(base: Context, theme: AppTheme, customPalette: CustomPalette?): Context {
        if (theme != AppTheme.CUSTOM || customPalette == null) return base
        val overrides = customPalette.toColorMap()
        val customResources = ColorOverrideResources(base.resources, overrides)
        return object : ContextThemeWrapper(base, theme.styleRes) {
            override fun getResources(): Resources = customResources
        }
    }
}

data class CustomPalette(
    val baseHex: String,
    val primaryHex: String,
    val accentHex: String
) {
    private fun parse(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
    }

    fun toColorMap(): Map<Int, Int> {
        val base = parse(baseHex, Color.BLACK)
        val primary = parse(primaryHex, Color.parseColor("#FF3B30"))
        val accent = parse(accentHex, Color.parseColor("#7D3CFF"))

        val surfaceVariant = ColorUtils.blendARGB(base, Color.WHITE, 0.08f)
        val card = ColorUtils.blendARGB(base, Color.WHITE, 0.06f)
        val border = ColorUtils.blendARGB(base, Color.WHITE, 0.12f)
        val chip = ColorUtils.blendARGB(base, Color.WHITE, 0.1f)
        val primaryVariant = ColorUtils.blendARGB(primary, Color.BLACK, 0.2f)
        val accentVariant = ColorUtils.blendARGB(accent, Color.BLACK, 0.2f)

        val surfaceLum = ColorUtils.calculateLuminance(base)
        val onSurface = if (surfaceLum < 0.5) Color.WHITE else Color.BLACK
        val textSecondary = ColorUtils.blendARGB(onSurface, base, 0.35f)
        val onPrimary = if (ColorUtils.calculateLuminance(primary) < 0.5) Color.WHITE else Color.BLACK
        val onSecondary = if (ColorUtils.calculateLuminance(accent) < 0.5) Color.WHITE else Color.BLACK

        return mapOf(
            R.color.palette_custom_primary to primary,
            R.color.palette_custom_primary_variant to primaryVariant,
            R.color.palette_custom_accent to accent,
            R.color.palette_custom_accent_variant to accentVariant,
            R.color.palette_custom_surface to base,
            R.color.palette_custom_surface_variant to surfaceVariant,
            R.color.palette_custom_card to card,
            R.color.palette_custom_text_primary to onSurface,
            R.color.palette_custom_text_secondary to textSecondary,
            R.color.palette_custom_border to border,
            R.color.palette_custom_chip to chip,
            R.color.palette_custom_on_primary to onPrimary,
            R.color.palette_custom_on_secondary to onSecondary
        )
    }
}

@Suppress("DEPRECATION") // Resources(assets, displayMetrics, configuration) is deprecated but acceptable for localized color overrides
private class ColorOverrideResources(
    private val base: Resources,
    private val overrides: Map<Int, Int>
) : Resources(base.assets, base.displayMetrics, base.configuration) {

    override fun getColor(id: Int, theme: Theme?): Int {
        overrides[id]?.let { return it }
        return base.getColor(id, theme)
    }

    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList {
        overrides[id]?.let { return ColorStateList.valueOf(it) }
        return base.getColorStateList(id, theme)
    }

    // Delegate other lookups to base to avoid missing resources
    override fun getText(id: Int): CharSequence = base.getText(id)
    override fun getString(id: Int): String = base.getString(id)
    override fun getString(id: Int, vararg formatArgs: Any?): String = base.getString(id, *formatArgs)
    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
        base.getQuantityString(id, quantity, *formatArgs)

    override fun openRawResource(id: Int): java.io.InputStream = base.openRawResource(id)
    override fun obtainTypedArray(id: Int) = base.obtainTypedArray(id)
    override fun getDisplayMetrics() = base.displayMetrics
    override fun getConfiguration() = base.configuration
}
