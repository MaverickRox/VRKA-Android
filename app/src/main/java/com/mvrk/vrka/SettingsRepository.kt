package com.mvrk.vrka

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.vrkaDataStore by preferencesDataStore(name = "vrka_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val amoled: Boolean = true,
    val fontPreference: FontPreference = FontPreference.VRKA_FONT,
    val saveLocationMode: SaveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
    val outputTreeUri: String = "",
    val isDownloadLocationConfigured: Boolean = false,
    val defaultMode: MediaMode = MediaMode.VIDEO,
    val defaultQuality: VideoQuality = VideoQuality.BEST,
    val defaultAudioFormat: AudioFormat = AudioFormat.MP3,
    val defaultMp3Bitrate: Int = 320,
    val updatePreference: UpdatePreference = UpdatePreference.STABLE,
    val concurrency: Int = 1,
    val concurrentDownloadsEnabled: Boolean = false,
    val maxConcurrentDownloads: Int = 2,
    val h264CompatibilityMode: Boolean = false,
    val customCommandMode: Boolean = false,
    val customCommandTemplates: List<YtDlpCommandTemplate> = listOf(CustomCommandPolicy.starterTemplate()),
    val selectedCustomCommandTemplateId: String = "vrka-safe-starter",
    val showDownloadSizeConfirmation: Boolean = true,
    val shareSheetPreferences: ShareSheetPreferences? = null,
)

class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    private object Keys {
        val darkTheme = booleanPreferencesKey("dark_theme")
        val outputTreeUri = stringPreferencesKey("output_tree_uri")
        val isDownloadLocationConfigured = booleanPreferencesKey("is_download_location_configured")
        val themeMode = stringPreferencesKey("theme_mode")
        val amoled = booleanPreferencesKey("amoled")
        val fontPreference = stringPreferencesKey("font_preference")
        val saveLocationMode = stringPreferencesKey("save_location_mode")
        val defaultMode = stringPreferencesKey("default_mode")
        val defaultQuality = stringPreferencesKey("default_quality")
        val defaultAudio = stringPreferencesKey("default_audio")
        val defaultBitrate = intPreferencesKey("default_bitrate")
        val updatePreference = stringPreferencesKey("update_preference")
        val concurrency = intPreferencesKey("concurrency")
        val concurrentDownloadsEnabled = booleanPreferencesKey("concurrent_downloads_enabled")
        val maxConcurrentDownloads = intPreferencesKey("max_concurrent_downloads")
        val adBlocking = booleanPreferencesKey("ad_blocking")
        val lastAppUpdateCheckTimestamp = longPreferencesKey("last_app_update_check_timestamp")
        val h264CompatibilityMode = booleanPreferencesKey("h264_compatibility_mode")
        val customCommandMode = booleanPreferencesKey("custom_command_mode")
        val customCommandTemplates = stringPreferencesKey("custom_command_templates")
        val selectedCustomCommandTemplateId = stringPreferencesKey("selected_custom_command_template_id")
        val showDownloadSizeConfirmation = booleanPreferencesKey("show_download_size_confirmation")
        val shareSheetPreferences = stringPreferencesKey("share_sheet_preferences")
    }

    val settings: StateFlow<AppSettings> = context.vrkaDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::mapSettings)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    init {
        // Remove templates saved by older builds if they contain blocked credential,
        // shell, or path options. Validation also runs again when a job is enqueued.
        scope.launch {
            context.vrkaDataStore.edit { preferences ->
                // This legacy preference no longer controls anything: uBlock is activated only
                // inside the Browser Fallback runtime. Remove the obsolete stored toggle.
                preferences.remove(Keys.adBlocking)
                val current = preferences[Keys.customCommandTemplates]
                val safe = encodeTemplates(decodeTemplates(current))
                if (current != safe) preferences[Keys.customCommandTemplates] = safe
            }
        }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.vrkaDataStore.edit { preferences ->
            if (preferences[Keys.amoled] == null) {
                val storedTheme = preferences[Keys.themeMode]
                preferences[Keys.amoled] = storedTheme == "AMOLED" ||
                    (storedTheme == null && preferences[Keys.darkTheme] != false)
            }
            preferences[Keys.themeMode] = value.name
        }
    }

    suspend fun setAmoled(value: Boolean) = update(Keys.amoled, value)

    suspend fun setFontPreference(value: FontPreference) =
        update(Keys.fontPreference, value.name)

    suspend fun setSaveLocationMode(value: SaveLocationMode) =
        update(Keys.saveLocationMode, value.name)

    suspend fun setOutputTree(uri: String) = update(Keys.outputTreeUri, uri)

    suspend fun setDownloadLocationConfigured(value: Boolean) =
        update(Keys.isDownloadLocationConfigured, value)

    suspend fun setDownloadLocation(uri: String, mode: SaveLocationMode, configured: Boolean = true) {
        context.vrkaDataStore.edit { preferences ->
            preferences[Keys.outputTreeUri] = uri
            preferences[Keys.saveLocationMode] = mode.name
            preferences[Keys.isDownloadLocationConfigured] = configured
        }
    }

    suspend fun setUpdatePreference(value: UpdatePreference) =
        update(Keys.updatePreference, value.name)

    suspend fun setConcurrency(value: Int) =
        update(Keys.concurrency, value.coerceIn(1, 4))

    suspend fun setMaxConcurrentDownloads(value: Int) {
        val clamped = value.coerceIn(2, 4)
        context.vrkaDataStore.edit { preferences ->
            preferences[Keys.maxConcurrentDownloads] = clamped
            if (preferences[Keys.concurrentDownloadsEnabled] == true) {
                preferences[Keys.concurrency] = clamped
            }
        }
    }

    suspend fun setConcurrentDownloadsEnabled(value: Boolean) {
        context.vrkaDataStore.edit { preferences ->
            preferences[Keys.concurrentDownloadsEnabled] = value
            val maxConcurrent = (preferences[Keys.maxConcurrentDownloads] ?: 2).coerceIn(2, 4)
            preferences[Keys.concurrency] = if (value) maxConcurrent else 1
        }
    }

    suspend fun setH264CompatibilityMode(value: Boolean) = update(Keys.h264CompatibilityMode, value)

    suspend fun setCustomCommandMode(value: Boolean) = update(Keys.customCommandMode, value)

    suspend fun setShowDownloadSizeConfirmation(value: Boolean) =
        update(Keys.showDownloadSizeConfirmation, value)

    suspend fun setShareSheetPreferences(value: ShareSheetPreferences) =
        update(Keys.shareSheetPreferences, SharePreferencesCodec.encode(value))

    suspend fun selectCustomCommandTemplate(id: String) =
        update(Keys.selectedCustomCommandTemplateId, id)

    suspend fun saveCustomCommandTemplate(template: YtDlpCommandTemplate) {
        require(CustomCommandPolicy.validateTemplate(template.name, template.arguments) == null) {
            CustomCommandPolicy.validateTemplate(template.name, template.arguments).orEmpty()
        }
        context.vrkaDataStore.edit { preferences ->
            val current = decodeTemplates(preferences[Keys.customCommandTemplates])
            val updated = (current.filterNot { it.id == template.id } + template)
                .takeLast(CustomCommandPolicy.MAX_TEMPLATES)
            preferences[Keys.customCommandTemplates] = encodeTemplates(updated)
            preferences[Keys.selectedCustomCommandTemplateId] = template.id
        }
    }

    suspend fun deleteCustomCommandTemplate(id: String) {
        context.vrkaDataStore.edit { preferences ->
            val result = CustomCommandPolicy.deleteTemplate(
                templates = decodeTemplates(preferences[Keys.customCommandTemplates]),
                selectedTemplateId = preferences[Keys.selectedCustomCommandTemplateId]
                    ?: CustomCommandPolicy.starterTemplate().id,
                deleteTemplateId = id,
            )
            preferences[Keys.customCommandTemplates] = encodeTemplates(result.templates)
            preferences[Keys.selectedCustomCommandTemplateId] = result.selectedTemplateId
        }
    }

    suspend fun setDefaults(
        mode: MediaMode,
        quality: VideoQuality,
        audioFormat: AudioFormat,
        bitrate: Int,
    ) {
        context.vrkaDataStore.edit { preferences ->
            preferences[Keys.defaultMode] = mode.name
            preferences[Keys.defaultQuality] = quality.name
            preferences[Keys.defaultAudio] = audioFormat.name
            preferences[Keys.defaultBitrate] = bitrate
        }
    }

    suspend fun getLastAppUpdateCheckTimestamp(): Long =
        context.vrkaDataStore.data.first()[Keys.lastAppUpdateCheckTimestamp] ?: 0L

    suspend fun setLastAppUpdateCheckTimestamp(value: Long) =
        update(Keys.lastAppUpdateCheckTimestamp, value)

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        context.vrkaDataStore.edit { it[key] = value }
    }

    private fun mapSettings(preferences: Preferences): AppSettings {
        val storedTheme = preferences[Keys.themeMode]
        val themeMode = when {
            storedTheme == ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            storedTheme == ThemeMode.DARK.name -> ThemeMode.DARK
            storedTheme == "AMOLED" -> ThemeMode.DARK
            preferences[Keys.darkTheme] == false -> ThemeMode.LIGHT
            else -> ThemeMode.DARK
        }
        val amoled = preferences[Keys.amoled]
            ?: (storedTheme == "AMOLED" ||
                (storedTheme == null && preferences[Keys.darkTheme] != false))

        val storedAudio = preferences[Keys.defaultAudio]
        val defaultAudioFormat = if (storedAudio == "FLAC") {
            AudioFormat.OPUS
        } else {
            enumValue(storedAudio, AudioFormat.MP3)
        }

        val isConfigured = preferences[Keys.isDownloadLocationConfigured]
            ?: (preferences[Keys.outputTreeUri]?.isNotBlank() == true || preferences[Keys.saveLocationMode] != null)

        val maxConcurrent = (preferences[Keys.maxConcurrentDownloads] ?: (preferences[Keys.concurrency] ?: 2)).coerceIn(2, 4)
        val concurrentEnabled = preferences[Keys.concurrentDownloadsEnabled]
            ?: ((preferences[Keys.concurrency] ?: 1) > 1)
        val effectiveConcurrency = if (concurrentEnabled) maxConcurrent else 1
        val templates = decodeTemplates(preferences[Keys.customCommandTemplates])
            .ifEmpty { listOf(CustomCommandPolicy.starterTemplate()) }
        val selectedTemplate = preferences[Keys.selectedCustomCommandTemplateId]
            ?.takeIf { id -> templates.any { it.id == id } }
            ?: templates.first().id

        return AppSettings(
            themeMode = themeMode,
            amoled = amoled,
            fontPreference = enumValue(preferences[Keys.fontPreference], FontPreference.VRKA_FONT),
            saveLocationMode = enumValue(preferences[Keys.saveLocationMode], SaveLocationMode.REMEMBER_LOCATION),
            outputTreeUri = preferences[Keys.outputTreeUri].orEmpty(),
            isDownloadLocationConfigured = isConfigured,
            defaultMode = enumValue(preferences[Keys.defaultMode], MediaMode.VIDEO),
            defaultQuality = enumValue(preferences[Keys.defaultQuality], VideoQuality.BEST),
            defaultAudioFormat = defaultAudioFormat,
            defaultMp3Bitrate = (preferences[Keys.defaultBitrate] ?: 320)
                .takeIf { it in setOf(128, 160, 192, 224, 256, 320) } ?: 320,
            updatePreference = enumValue(
                preferences[Keys.updatePreference],
                UpdatePreference.STABLE,
            ),
            concurrency = effectiveConcurrency,
            concurrentDownloadsEnabled = concurrentEnabled,
            maxConcurrentDownloads = maxConcurrent,
            h264CompatibilityMode = preferences[Keys.h264CompatibilityMode] ?: false,
            customCommandMode = preferences[Keys.customCommandMode] ?: false,
            customCommandTemplates = templates,
            selectedCustomCommandTemplateId = selectedTemplate,
            showDownloadSizeConfirmation =
                SizeConfirmationPreference.resolve(preferences[Keys.showDownloadSizeConfirmation]),
            shareSheetPreferences =
                SharePreferencesCodec.decode(preferences[Keys.shareSheetPreferences]),
        )
    }

    private fun decodeTemplates(json: String?): List<YtDlpCommandTemplate> = runCatching {
        val array = org.json.JSONArray(json ?: return@runCatching emptyList())
        buildList {
            for (i in 0 until minOf(array.length(), CustomCommandPolicy.MAX_TEMPLATES)) {
                val item = array.optJSONObject(i) ?: continue
                val args = item.optJSONArray("arguments") ?: continue
                val template = YtDlpCommandTemplate(
                    id = item.optString("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                    name = item.optString("name"),
                    arguments = buildList { for (argIndex in 0 until minOf(args.length(), CustomCommandPolicy.MAX_ARGUMENTS)) add(args.optString(argIndex)) },
                )
                if (CustomCommandPolicy.validateTemplate(template.name, template.arguments) == null) add(template)
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeTemplates(templates: List<YtDlpCommandTemplate>): String = org.json.JSONArray().apply {
        templates.takeLast(CustomCommandPolicy.MAX_TEMPLATES).forEach { template ->
            put(org.json.JSONObject()
                .put("id", template.id)
                .put("name", template.name)
                .put("arguments", org.json.JSONArray(template.arguments.take(CustomCommandPolicy.MAX_ARGUMENTS))))
        }
    }.toString()

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
