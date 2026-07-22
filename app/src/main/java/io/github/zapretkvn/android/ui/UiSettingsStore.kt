package io.github.zapretkvn.android.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zapretkvn.android.config.DnsMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    System,
    Light,
    Dark,
}

enum class UpdateChannel {
    Stable,
    Beta,
}

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val activeProfileId: String? = null,
    val rawEditorLineWrap: Boolean = false,
    val dnsMode: DnsMode = DnsMode.FromJson,
    val updateChannel: UpdateChannel = UpdateChannel.Stable,
)

private val Context.uiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ui_settings",
)

class UiSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.uiSettingsDataStore

    val settings: Flow<UiSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            UiSettings(
                themeMode = preferences[THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: ThemeMode.System,
                activeProfileId = preferences[ACTIVE_PROFILE_ID],
                rawEditorLineWrap = preferences[RAW_EDITOR_LINE_WRAP] ?: false,
                dnsMode = preferences[DNS_MODE]
                    ?.let { stored -> DnsMode.entries.firstOrNull { it.name == stored } }
                    ?: DnsMode.FromJson,
                updateChannel = preferences[UPDATE_CHANNEL]
                    ?.let { stored -> UpdateChannel.entries.firstOrNull { it.name == stored } }
                    ?: UpdateChannel.Stable,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setActiveProfile(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) preferences.remove(ACTIVE_PROFILE_ID)
            else preferences[ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun setRawEditorLineWrap(enabled: Boolean) {
        dataStore.edit { it[RAW_EDITOR_LINE_WRAP] = enabled }
    }

    suspend fun setDnsMode(mode: DnsMode) {
        dataStore.edit { it[DNS_MODE] = mode.name }
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        dataStore.edit { it[UPDATE_CHANNEL] = channel.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val RAW_EDITOR_LINE_WRAP = booleanPreferencesKey("raw_editor_line_wrap")
        val DNS_MODE = stringPreferencesKey("dns_mode")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
    }
}
