package com.safe.vision

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageManager {
    const val FOLLOW_SYSTEM = "system"
    const val ENGLISH = "en"
    const val KOREAN = "ko"
    const val SIMPLIFIED_CHINESE = "zh-CN"
    const val TRADITIONAL_CHINESE = "zh-Hant"

    private val supportedPreferences = setOf(
        FOLLOW_SYSTEM,
        ENGLISH,
        KOREAN,
        SIMPLIFIED_CHINESE,
        TRADITIONAL_CHINESE
    )

    fun sanitizePreference(value: String?): String {
        return value?.takeIf { supportedPreferences.contains(it) } ?: FOLLOW_SYSTEM
    }

    fun applyToApp(context: Context) {
        val locales = resolveLocalesForPreference(AppSettingsManager.getInstance(context).getAppLanguage())
        if (AppCompatDelegate.getApplicationLocales() == locales) return
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun resolveLanguageTag(context: Context): String {
        val preference = AppSettingsManager.getInstance(context).getAppLanguage()
        return resolveLanguageTagForPreference(preference)
    }

    fun resolveLocalesForPreference(preference: String): LocaleListCompat {
        return when (sanitizePreference(preference)) {
            FOLLOW_SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            else -> LocaleListCompat.forLanguageTags(resolveLanguageTagForPreference(preference))
        }
    }

    fun resolveLanguageTagForPreference(preference: String): String {
        return when (sanitizePreference(preference)) {
            SIMPLIFIED_CHINESE -> SIMPLIFIED_CHINESE
            TRADITIONAL_CHINESE -> TRADITIONAL_CHINESE
            ENGLISH -> ENGLISH
            KOREAN -> KOREAN
            FOLLOW_SYSTEM -> mapSystemLocaleToSupportedLanguage(currentSystemLocale())
            else -> ENGLISH
        }
    }

    private fun currentSystemLocale(): Locale {
        val locales = Resources.getSystem().configuration.locales
        return if (!locales.isEmpty) locales[0] else Locale.ENGLISH
    }

    private fun mapSystemLocaleToSupportedLanguage(locale: Locale?): String {
        if (locale == null) return ENGLISH
        return when (locale.language.lowercase(Locale.ROOT)) {
            "zh" -> if (isTraditionalChinese(locale)) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
            "en" -> ENGLISH
            "ko" -> KOREAN
            else -> ENGLISH
        }
    }

    private fun isTraditionalChinese(locale: Locale): Boolean {
        val script = locale.script.lowercase(Locale.ROOT)
        val country = locale.country.uppercase(Locale.ROOT)
        return script == "hant" || country == "TW" || country == "HK" || country == "MO"
    }
}
