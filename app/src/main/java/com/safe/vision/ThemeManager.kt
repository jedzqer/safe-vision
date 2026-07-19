package com.safe.vision

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import androidx.annotation.StyleRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

enum class AppTheme(val prefValue: String, @StyleRes val styleRes: Int, val labelRes: Int) {
    DEFAULT("default", R.style.Theme_SafeVision, R.string.settings_theme_default),
    BLACK_RED("black_red", R.style.Theme_SafeVision_BlackRed, R.string.settings_theme_black_red),
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

    fun applySystemBarAppearance(activity: Activity, theme: AppTheme, customPalette: CustomPalette?) {
        if (theme != AppTheme.CUSTOM || customPalette == null) return
        val colors = customPalette.toColorMap()
        val statusBarColor = colors.getValue(R.color.palette_custom_primary_variant)
        val navigationBarColor = colors.getValue(R.color.palette_custom_surface)

        @Suppress("DEPRECATION")
        activity.window.statusBarColor = statusBarColor
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = navigationBarColor

        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(statusBarColor) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(navigationBarColor) > 0.5
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
        val base = opaque(parse(baseHex, Color.BLACK))
        val primary = opaque(parse(primaryHex, Color.parseColor("#FF3B30")))
        val accent = opaque(parse(accentHex, Color.parseColor("#7D3CFF")))
        val surfaceLuminance = ColorUtils.calculateLuminance(base)

        val surfaceVariant: Int
        val card: Int
        val border: Int
        val chip: Int
        when {
            surfaceLuminance < 0.18 -> {
                surfaceVariant = ColorUtils.blendARGB(base, Color.WHITE, 0.08f)
                card = ColorUtils.blendARGB(base, Color.WHITE, 0.06f)
                border = ColorUtils.blendARGB(base, Color.WHITE, 0.15f)
                chip = ColorUtils.blendARGB(base, Color.WHITE, 0.11f)
            }
            surfaceLuminance < 0.58 -> {
                surfaceVariant = ColorUtils.blendARGB(base, Color.BLACK, 0.1f)
                card = ColorUtils.blendARGB(base, Color.BLACK, 0.2f)
                border = ColorUtils.blendARGB(base, Color.WHITE, 0.16f)
                chip = ColorUtils.blendARGB(base, Color.WHITE, 0.1f)
            }
            else -> {
                surfaceVariant = ColorUtils.blendARGB(base, Color.WHITE, 0.34f)
                card = ColorUtils.blendARGB(base, Color.WHITE, 0.82f)
                border = ColorUtils.blendARGB(base, Color.BLACK, 0.14f)
                chip = ColorUtils.blendARGB(card, accent, 0.1f)
            }
        }
        val primaryVariant = ColorUtils.blendARGB(primary, Color.BLACK, 0.2f)
        val accentVariant = ColorUtils.blendARGB(accent, Color.BLACK, 0.2f)

        val onSurface = bestOnColor(card)
        val textSecondary = ColorUtils.blendARGB(onSurface, card, 0.34f)
        val onPrimary = bestOnColor(primary)
        val onSecondary = bestOnColor(accent)

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

    private fun opaque(color: Int): Int = Color.rgb(
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun bestOnColor(background: Int): Int {
        val whiteContrast = ColorUtils.calculateContrast(Color.WHITE, background)
        val blackContrast = ColorUtils.calculateContrast(Color.BLACK, background)
        return if (whiteContrast >= blackContrast) Color.WHITE else Color.BLACK
    }
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@SuppressLint("UseCompatLoadingForColorStateLists", "UseCompatLoadingForDrawables")
private class ColorOverrideResources(
    private val base: Resources,
    private val overrides: Map<Int, Int>
) : Resources(base.assets, base.displayMetrics, base.configuration) {

    override fun getColor(id: Int): Int {
        overrides[id]?.let { return it }
        return base.getColor(id)
    }

    override fun getColor(id: Int, theme: Theme?): Int {
        overrides[id]?.let { return it }
        return base.getColor(id, theme)
    }

    override fun getColorStateList(id: Int): ColorStateList {
        overrides[id]?.let { return ColorStateList.valueOf(it) }
        return base.getColorStateList(id)
    }

    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList {
        overrides[id]?.let { return ColorStateList.valueOf(it) }
        return base.getColorStateList(id, theme)
    }

    override fun getDrawable(id: Int): Drawable {
        overrides[id]?.let { return ColorDrawable(it) }
        return base.getDrawable(id)
    }

    override fun getDrawable(id: Int, theme: Theme?): Drawable {
        overrides[id]?.let { return ColorDrawable(it) }
        return base.getDrawable(id, theme)
    }

    override fun getValue(id: Int, outValue: TypedValue, resolveRefs: Boolean) {
        overrides[id]?.let {
            setColorValue(outValue, id, it)
            return
        }
        base.getValue(id, outValue, resolveRefs)
    }

    override fun getValueForDensity(
        id: Int,
        density: Int,
        outValue: TypedValue,
        resolveRefs: Boolean
    ) {
        overrides[id]?.let {
            setColorValue(outValue, id, it)
            return
        }
        base.getValueForDensity(id, density, outValue, resolveRefs)
    }

    private fun setColorValue(outValue: TypedValue, id: Int, color: Int) {
        outValue.type = TypedValue.TYPE_INT_COLOR_ARGB8
        outValue.data = color
        outValue.assetCookie = 0
        outValue.resourceId = id
        outValue.changingConfigurations = 0
        outValue.density = TypedValue.DENSITY_NONE
        outValue.string = null
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
