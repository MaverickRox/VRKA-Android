package com.mvrk.vrka

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

data class AppLanguage(
    val code: String, // "" for system default, or "ar", "bn", etc.
    val englishName: String,
    val nativeName: String,
    val displayLabel: String = when {
        code.isEmpty() -> englishName
        nativeName == englishName -> nativeName
        else -> "$nativeName ($englishName)"
    },
)

object LocaleHelper {
    private const val PREFS_NAME = "vrka_locale_preferences"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"

    val systemDefaultLanguage = AppLanguage(
        code = "",
        englishName = "System default",
        nativeName = "System default",
        displayLabel = "System default",
    )

    // 14 application languages strictly ordered by English name in ascending alphabetical order:
    // 1. Arabic, 2. Bengali, 3. Chinese (Simplified), 4. English, 5. French, 6. German,
    // 7. Gujarati, 8. Hindi, 9. Kannada, 10. Portuguese (Brazil), 11. Portuguese (Portugal),
    // 12. Spanish, 13. Tamil, 14. Telugu
    val sortedLanguages = listOf(
        AppLanguage("ar", "Arabic", "العربية"),
        AppLanguage("bn", "Bengali", "বাংলা"),
        AppLanguage("zh-CN", "Chinese (Simplified)", "简体中文"),
        AppLanguage("en", "English", "English"),
        AppLanguage("fr", "French", "Français"),
        AppLanguage("de", "German", "Deutsch"),
        AppLanguage("gu", "Gujarati", "ગુજરાતી"),
        AppLanguage("hi", "Hindi", "हिन्दी"),
        AppLanguage("kn", "Kannada", "ಕನ್ನಡ"),
        AppLanguage("pt-BR", "Portuguese (Brazil)", "Português (Brasil)"),
        AppLanguage("pt", "Portuguese (Portugal)", "Português (Portugal)"),
        AppLanguage("es", "Spanish", "Español"),
        AppLanguage("ta", "Tamil", "தமிழ்"),
        AppLanguage("te", "Telugu", "తెలుగు"),
    )

    val supportedLanguages = listOf(systemDefaultLanguage) + sortedLanguages

    fun getActiveLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                val locales = localeManager?.applicationLocales
                if (locales != null) {
                    if (!locales.isEmpty) {
                        return normalizeTag(locales[0].toLanguageTag())
                    }
                    val legacyTag = getStoredLegacyTag(context)
                    if (legacyTag.isNotBlank()) {
                        val normalized = normalizeTag(legacyTag)
                        localeManager.applicationLocales = LocaleList.forLanguageTags(normalized)
                        return normalized
                    }
                    return ""
                }
            } catch (_: Exception) {
            }
        }
        val tag = getStoredLegacyTag(context)
        return if (tag.isNotBlank()) normalizeTag(tag) else ""
    }

    fun getActiveLanguage(context: Context): AppLanguage {
        val tag = getActiveLanguageTag(context)
        return sortedLanguages.firstOrNull { it.code.equals(tag, ignoreCase = true) }
            ?: systemDefaultLanguage
    }

    fun normalizeTag(tag: String): String {
        return when {
            tag.equals("pt-BR", ignoreCase = true) || tag.startsWith("pt-rBR", ignoreCase = true) || tag.startsWith("pt_BR", ignoreCase = true) -> "pt-BR"
            tag.equals("pt", ignoreCase = true) || tag.startsWith("pt-PT", ignoreCase = true) || tag.startsWith("pt-rPT", ignoreCase = true) || tag.startsWith("pt_PT", ignoreCase = true) -> "pt"
            tag.equals("zh-CN", ignoreCase = true) || tag.startsWith("zh-Hans", ignoreCase = true) || tag.startsWith("zh-rCN", ignoreCase = true) || tag.startsWith("zh_CN", ignoreCase = true) -> "zh-CN"
            tag.startsWith("gu", ignoreCase = true) -> "gu"
            tag.startsWith("hi", ignoreCase = true) -> "hi"
            tag.startsWith("ta", ignoreCase = true) -> "ta"
            tag.startsWith("te", ignoreCase = true) -> "te"
            tag.startsWith("kn", ignoreCase = true) -> "kn"
            tag.startsWith("bn", ignoreCase = true) -> "bn"
            tag.startsWith("es", ignoreCase = true) -> "es"
            tag.startsWith("de", ignoreCase = true) -> "de"
            tag.startsWith("fr", ignoreCase = true) -> "fr"
            tag.startsWith("ar", ignoreCase = true) -> "ar"
            tag.startsWith("en", ignoreCase = true) -> "en"
            else -> tag
        }
    }

    fun setAppLanguage(context: Context, languageTag: String) {
        val normalized = if (languageTag.isBlank()) "" else normalizeTag(languageTag)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            if (normalized.isBlank()) {
                remove(KEY_LANGUAGE_TAG)
            } else {
                putString(KEY_LANGUAGE_TAG, normalized)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                if (normalized.isBlank()) {
                    localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
                } else {
                    localeManager?.applicationLocales = LocaleList.forLanguageTags(normalized)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun getStoredLegacyTag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE_TAG, "").orEmpty()
    }

    fun clearStoredLegacyTag(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LANGUAGE_TAG)) {
            prefs.edit { remove(KEY_LANGUAGE_TAG) }
        }
    }

    fun applyLocale(baseContext: Context): Context {
        val tag = getActiveLanguageTag(baseContext)
        if (tag.isNotBlank()) {
            val primaryLocale = Locale.forLanguageTag(tag)
            Locale.setDefault(primaryLocale)
            val config = Configuration(baseContext.resources.configuration)
            config.setLocales(LocaleList(primaryLocale))
            config.setLayoutDirection(primaryLocale)
            return baseContext.createConfigurationContext(config)
        } else {
            val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                baseContext.resources.configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                baseContext.resources.configuration.locale
            }
            Locale.setDefault(systemLocale)
            return baseContext
        }
    }
}
