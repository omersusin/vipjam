package com.vipjam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class PresetEntry(val name: String, val settingsJson: String)

class PresetStore(private val dataStore: DataStore<Preferences>) {
    private val namesKey = stringSetPreferencesKey("preset_names")

    private fun bodyKey(name: String) = stringPreferencesKey("preset_$name")

    val entries: Flow<List<PresetEntry>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[namesKey].orEmpty().sorted().mapNotNull { name ->
                prefs[bodyKey(name)]?.let { PresetEntry(name, it) }
            }
        }

    suspend fun save(entry: PresetEntry): Result<Unit> = runCatching {
        require(NAME_RE.matches(entry.name)) { "bad preset name" }
        PresetImporter.parseV3(entry.settingsJson).getOrThrow()
        dataStore.edit { prefs ->
            prefs[namesKey] = prefs[namesKey].orEmpty() + entry.name
            prefs[bodyKey(entry.name)] = entry.settingsJson
        }
        Unit
    }

    suspend fun importText(text: String): Result<String> = runCatching {
        val preset = PresetImporter.parseV3(text).getOrThrow()
        save(PresetEntry(preset.name, preset.settingsJson)).getOrThrow()
        preset.name
    }

    suspend fun importLink(link: String): Result<String> = runCatching {
        val text = PresetImporter.unpackLink(link).getOrThrow()
        importText(text).getOrThrow()
    }

    suspend fun delete(name: String) {
        dataStore.edit { prefs ->
            prefs[namesKey] = prefs[namesKey].orEmpty() - name
            prefs.remove(bodyKey(name))
        }
    }

    companion object {
        private val NAME_RE = Regex("[A-Za-z0-9 _.-]{1,64}")
    }
}
