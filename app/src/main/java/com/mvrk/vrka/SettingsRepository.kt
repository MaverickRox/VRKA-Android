package com.mvrk.vrka

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.vrkaDataStore by preferencesDataStore(name = "vrka_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val amoled: Boolean = true,
    val outputTreeUri: String = "",
    val defaultMode: MediaMode = MediaMode.VIDEO,
    val defaultQuality: VideoQuality = VideoQuality.BEST,
    val defaultAudioFormat: AudioFormat = AudioFormat.MP3,
    val defaultMp3Bitrate: Int = 320,
    val updatePreference: UpdatePreference = UpdatePreference.STABLE,
    val concurrency: Int = 1,
    val adBlocking: Boolean = true,
)

class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    private object Keys {
        val darkTheme = booleanPreferencesKey("dark_theme")
        val outputTreeUri = stringPreferencesKey("output_tree_uri")
        val themeMode = stringPreferencesKey("theme_mode")
        val amoled = booleanPreferencesKey("amoled")
        val defaultMode = stringPreferencesKey("default_mode")
        val defaultQuality = stringPreferencesKey("default_quality")
        val defaultAudio = stringPreferencesKey("default_audio")
        val defaultBitrate = intPreferencesKey("default_bitrate")
        val updatePreference = stringPreferencesKey("update_preference")
        val concurrency = intPreferencesKey("concurrency")
        val adBlocking = booleanPreferencesKey("ad_blocking")
    }

    val settings: StateFlow<AppSettings> = context.vrkaDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::mapSettings)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

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

    suspend fun setOutputTree(uri: String) = update(Keys.outputTreeUri, uri)

    suspend fun setUpdatePreference(value: UpdatePreference) =
        update(Keys.updatePreference, value.name)

    suspend fun setConcurrency(value: Int) =
        update(Keys.concurrency, value.coerceIn(1, 2))

    suspend fun setAdBlocking(value: Boolean) = update(Keys.adBlocking, value)

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
        return AppSettings(
        themeMode = themeMode,
        amoled = amoled,
        outputTreeUri = preferences[Keys.outputTreeUri].orEmpty(),
        defaultMode = enumValue(preferences[Keys.defaultMode], MediaMode.VIDEO),
        defaultQuality = enumValue(preferences[Keys.defaultQuality], VideoQuality.BEST),
        defaultAudioFormat = enumValue(preferences[Keys.defaultAudio], AudioFormat.MP3),
        defaultMp3Bitrate = (preferences[Keys.defaultBitrate] ?: 320)
            .takeIf { it in setOf(128, 192, 256, 320) } ?: 320,
        updatePreference = enumValue(
            preferences[Keys.updatePreference],
            UpdatePreference.STABLE,
        ),
        concurrency = (preferences[Keys.concurrency] ?: 1).coerceIn(1, 2),
        adBlocking = preferences[Keys.adBlocking] ?: true,
    )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
