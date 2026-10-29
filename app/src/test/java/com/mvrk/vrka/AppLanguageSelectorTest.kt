package com.mvrk.vrka

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppLanguageSelectorTest {

    private val expectedEnglishNamesInOrder = listOf(
        "Arabic",
        "Bengali",
        "Chinese (Simplified)",
        "English",
        "French",
        "German",
        "Gujarati",
        "Hindi",
        "Kannada",
        "Portuguese (Brazil)",
        "Portuguese (Portugal)",
        "Spanish",
        "Tamil",
        "Telugu",
    )

    private val expectedLanguageCodes = listOf(
        "ar",
        "bn",
        "zh-CN",
        "en",
        "fr",
        "de",
        "gu",
        "hi",
        "kn",
        "pt-BR",
        "pt",
        "es",
        "ta",
        "te",
    )

    @Test
    fun testAll14LanguagesAvailableAndOrderedByEnglishName() {
        val sorted = LocaleHelper.sortedLanguages
        assertEquals("Must support exactly 14 application languages", 14, sorted.size)

        val actualEnglishNames = sorted.map { it.englishName }
        assertEquals(
            "14 languages must be listed in exact ascending alphabetical order of English names",
            expectedEnglishNamesInOrder,
            actualEnglishNames,
        )

        val actualCodes = sorted.map { it.code }
        assertEquals("Language codes must match expected set in order", expectedLanguageCodes, actualCodes)

        // Portuguese (Brazil) and Portuguese (Portugal) must be distinct
        val ptBr = sorted.firstOrNull { it.code == "pt-BR" }
        val ptPt = sorted.firstOrNull { it.code == "pt" }
        assertNotNull("Portuguese (Brazil) must be present", ptBr)
        assertNotNull("Portuguese (Portugal) must be present", ptPt)
        assertEquals("Portuguese (Brazil)", ptBr?.englishName)
        assertEquals("Portuguese (Portugal)", ptPt?.englishName)
        assertEquals("pt-BR", ptBr?.code)
        assertEquals("pt", ptPt?.code)

        // Simplified Chinese must be present
        val zhCn = sorted.firstOrNull { it.code == "zh-CN" }
        assertNotNull("Chinese (Simplified) must be present", zhCn)
        assertEquals("Chinese (Simplified)", zhCn?.englishName)
        assertEquals("zh-CN", zhCn?.code)
    }

    @Test
    fun testSystemDefaultIsAvailableAndAtTopOfSupportedLanguages() {
        val supported = LocaleHelper.supportedLanguages
        assertEquals("supportedLanguages must contain System default + 14 languages", 15, supported.size)

        val firstItem = supported.first()
        assertEquals("First item must be System default with empty code", "", firstItem.code)
        assertEquals("System default", firstItem.englishName)

        val remaining14 = supported.drop(1)
        assertEquals(expectedEnglishNamesInOrder, remaining14.map { it.englishName })
    }

    @Test
    fun testDisplayLabelFormatting() {
        // English should display "English", not "English (English)"
        val en = LocaleHelper.sortedLanguages.first { it.code == "en" }
        assertEquals("English", en.displayLabel)

        // Non-English languages should display Native (English)
        val ar = LocaleHelper.sortedLanguages.first { it.code == "ar" }
        assertEquals("العربية (Arabic)", ar.displayLabel)

        val gu = LocaleHelper.sortedLanguages.first { it.code == "gu" }
        assertEquals("ગુજરાતી (Gujarati)", gu.displayLabel)

        val zh = LocaleHelper.sortedLanguages.first { it.code == "zh-CN" }
        assertEquals("简体中文 (Chinese (Simplified))", zh.displayLabel)

        val ptBr = LocaleHelper.sortedLanguages.first { it.code == "pt-BR" }
        assertEquals("Português (Brasil) (Portuguese (Brazil))", ptBr.displayLabel)

        val ptPt = LocaleHelper.sortedLanguages.first { it.code == "pt" }
        assertEquals("Português (Portugal) (Portuguese (Portugal))", ptPt.displayLabel)
    }

    @Test
    fun testTagNormalization() {
        assertEquals("pt-BR", LocaleHelper.normalizeTag("pt-BR"))
        assertEquals("pt-BR", LocaleHelper.normalizeTag("pt-rBR"))
        assertEquals("pt-BR", LocaleHelper.normalizeTag("pt_BR"))

        assertEquals("pt", LocaleHelper.normalizeTag("pt"))
        assertEquals("pt", LocaleHelper.normalizeTag("pt-PT"))
        assertEquals("pt", LocaleHelper.normalizeTag("pt_PT"))

        assertEquals("zh-CN", LocaleHelper.normalizeTag("zh-CN"))
        assertEquals("zh-CN", LocaleHelper.normalizeTag("zh-Hans"))
        assertEquals("zh-CN", LocaleHelper.normalizeTag("zh-rCN"))
        assertEquals("zh-CN", LocaleHelper.normalizeTag("zh_CN"))

        assertEquals("gu", LocaleHelper.normalizeTag("gu"))
        assertEquals("gu", LocaleHelper.normalizeTag("gu-IN"))

        assertEquals("hi", LocaleHelper.normalizeTag("hi"))
        assertEquals("ta", LocaleHelper.normalizeTag("ta"))
        assertEquals("te", LocaleHelper.normalizeTag("te"))
        assertEquals("kn", LocaleHelper.normalizeTag("kn"))
        assertEquals("bn", LocaleHelper.normalizeTag("bn"))
        assertEquals("es", LocaleHelper.normalizeTag("es"))
        assertEquals("de", LocaleHelper.normalizeTag("de"))
        assertEquals("fr", LocaleHelper.normalizeTag("fr"))
        assertEquals("ar", LocaleHelper.normalizeTag("ar"))
        assertEquals("en", LocaleHelper.normalizeTag("en"))
        assertEquals("", LocaleHelper.normalizeTag(""))
    }

    @Test
    fun testLocalePersistenceAndSystemDefaultClearing() {
        val fakePrefs = FakeSharedPreferences()
        val fakeContext = FakeContext(fakePrefs)

        // Initial state: System default (empty tag)
        assertEquals("", LocaleHelper.getActiveLanguageTag(fakeContext))
        assertEquals(LocaleHelper.systemDefaultLanguage, LocaleHelper.getActiveLanguage(fakeContext))

        // Set Gujarati
        LocaleHelper.setAppLanguage(fakeContext, "gu")
        assertEquals("gu", LocaleHelper.getActiveLanguageTag(fakeContext))
        val activeLang = LocaleHelper.getActiveLanguage(fakeContext)
        assertEquals("gu", activeLang.code)
        assertEquals("Gujarati", activeLang.englishName)

        // Set Arabic
        LocaleHelper.setAppLanguage(fakeContext, "ar")
        assertEquals("ar", LocaleHelper.getActiveLanguageTag(fakeContext))
        assertEquals("Arabic", LocaleHelper.getActiveLanguage(fakeContext).englishName)

        // Set System default (clear override)
        LocaleHelper.setAppLanguage(fakeContext, "")
        assertEquals("", LocaleHelper.getActiveLanguageTag(fakeContext))
        assertEquals(LocaleHelper.systemDefaultLanguage, LocaleHelper.getActiveLanguage(fakeContext))
    }

    @Test
    fun testResourceDirectoriesAndLocalesConfigParity() {
        val resDir = File("src/main/res").takeIf { it.exists() }
            ?: File("app/src/main/res").takeIf { it.exists() }
            ?: File(System.getProperty("user.dir"), "app/src/main/res")

        assertTrue("res directory must exist at $resDir", resDir.exists())

        val expectedQualifiers = listOf(
            "values", // default (en)
            "values-ar",
            "values-bn",
            "values-de",
            "values-es",
            "values-fr",
            "values-gu",
            "values-hi",
            "values-kn",
            "values-pt",
            "values-pt-rBR",
            "values-ta",
            "values-te",
            "values-zh-rCN",
        )

        for (q in expectedQualifiers) {
            val qDir = File(resDir, q)
            assertTrue("Resource directory $q must exist", qDir.exists())
            val stringsFile = File(qDir, "strings.xml")
            assertTrue("strings.xml must exist in $q", stringsFile.exists())
        }

        // Verify locales_config.xml
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml must exist", localesConfigFile.exists())

        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(localesConfigFile)
        val localeNodes = doc.getElementsByTagName("locale")
        val declaredLocales = mutableListOf<String>()
        for (i in 0 until localeNodes.length) {
            val element = localeNodes.item(i) as Element
            declaredLocales.add(element.getAttribute("android:name"))
        }

        val requiredLocaleNames = listOf("en", "gu", "hi", "ta", "te", "kn", "bn", "es", "pt", "pt-BR", "zh-CN", "de", "fr", "ar")
        for (req in requiredLocaleNames) {
            assertTrue("locales_config.xml must declare $req", declaredLocales.contains(req))
        }
    }

    @Test
    fun testSettingsScreenContainsLanguageSelectorInAppearanceSection() {
        val settingsFile = File("src/main/java/com/mvrk/vrka/SettingsScreen.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/mvrk/vrka/SettingsScreen.kt").takeIf { it.exists() }
            ?: File(System.getProperty("user.dir"), "app/src/main/java/com/mvrk/vrka/SettingsScreen.kt")

        assertTrue("SettingsScreen.kt must exist at $settingsFile", settingsFile.exists())
        val content = settingsFile.readText()

        // 1. Selector is in the Appearance section after Font and before Components & Updates
        val appearanceIdx = content.indexOf("settings_heading_appearance")
        val fontIdx = content.indexOf("pref_font", appearanceIdx)
        val langIdx = content.indexOf("pref_app_language", fontIdx)
        val componentsIdx = content.indexOf("settings_heading_components", langIdx)

        assertTrue("settings_heading_appearance must appear before pref_font", appearanceIdx in 0..<fontIdx)
        assertTrue("pref_font must appear before pref_app_language", fontIdx in 0..<langIdx)
        assertTrue("pref_app_language must appear before settings_heading_components", langIdx in 0..<componentsIdx)

        // 2. Change button is wired up
        assertTrue("SettingsScreen must contain btn_change", content.contains("btn_change"))
        assertTrue("SettingsScreen must contain showLanguageDialog", content.contains("showLanguageDialog"))

        // 3. System default in separate row and sorted languages list are used
        assertTrue("SettingsScreen must reference pref_system_default", content.contains("pref_system_default"))
        assertTrue("SettingsScreen must reference LocaleHelper.sortedLanguages", content.contains("LocaleHelper.sortedLanguages"))
        assertTrue("SettingsScreen must call LocaleHelper.setAppLanguage", content.contains("LocaleHelper.setAppLanguage"))
    }

    @Test
    fun testLongLanguageLabelsLayoutConstraintsAndAlignment() {
        // Verify long labels exist and exceed typical short label lengths
        val zh = LocaleHelper.sortedLanguages.first { it.code == "zh-CN" }
        val ptBr = LocaleHelper.sortedLanguages.first { it.code == "pt-BR" }
        val ptPt = LocaleHelper.sortedLanguages.first { it.code == "pt" }

        assertTrue("Simplified Chinese label must be formatted with English name", zh.displayLabel.contains("Chinese (Simplified)"))
        assertTrue("Portuguese (Brazil) label must be formatted with English name", ptBr.displayLabel.contains("Portuguese (Brazil)"))
        assertTrue("Portuguese (Portugal) label must be formatted with English name", ptPt.displayLabel.contains("Portuguese (Portugal)"))

        // Verify layout implementation in SettingsScreen.kt prevents long labels from pushing radio buttons
        val settingsFile = File("src/main/java/com/mvrk/vrka/SettingsScreen.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/mvrk/vrka/SettingsScreen.kt").takeIf { it.exists() }
            ?: File(System.getProperty("user.dir"), "app/src/main/java/com/mvrk/vrka/SettingsScreen.kt")

        val content = settingsFile.readText()
        val dialogStart = content.indexOf("if (showLanguageDialog) {")
        val dialogEnd = content.indexOf("confirmButton = {},", dialogStart)
        val dialogCode = content.substring(dialogStart, dialogEnd)

        // 1. Must use weight(1f) and widthIn(min = 0.dp) on text so it wraps naturally
        assertTrue("Language dialog text must use weight(1f) to prevent pushing radio buttons", dialogCode.contains(".weight(1f)"))
        assertTrue("Language dialog text must use widthIn(min = 0.dp) to allow natural wrapping", dialogCode.contains(".widthIn(min = 0.dp)"))

        // 2. Must use fixed-size trailing container for RadioButton
        assertTrue("Language dialog must provide fixed-size 24.dp container for RadioButton", dialogCode.contains("Box(\n                                        modifier = Modifier.size(24.dp)") || dialogCode.contains("Modifier.size(24.dp)"))

        // 3. Must use selectable with Role.RadioButton for accessibility
        assertTrue("Language dialog row must use selectable modifier", dialogCode.contains(".selectable("))
        assertTrue("Language dialog row must declare Role.RadioButton", dialogCode.contains("role = Role.RadioButton"))

        // 4. Must use fillMaxWidth() on option rows
        assertTrue("Language dialog row must occupy fillMaxWidth()", dialogCode.contains(".fillMaxWidth()"))
    }

    // --- Fake Test Fixtures ---

    private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val storage: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value == null) pending[key] = null else pending[key] = value
                }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values == null) pending[key] = null else pending[key] = values
                }
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                storage.clear()
                pending.clear()
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                for ((k, v) in pending) {
                    if (v == null) {
                        storage.remove(k)
                    } else {
                        storage[k] = v
                    }
                }
                pending.clear()
            }
        }
    }
}
