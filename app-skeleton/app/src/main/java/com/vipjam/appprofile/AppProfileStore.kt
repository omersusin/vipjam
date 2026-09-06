package com.vipjam.appprofile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.IOException

class AppProfileStore(private val dataStore: DataStore<Preferences>) {
    private val mapKey = stringPreferencesKey("app_preset_map")
    private val enabledKey = booleanPreferencesKey("app_monitor_enabled")
    private val headphoneOnlyKey = booleanPreferencesKey("app_monitor_headphone_only")

    val appPresetMap: Flow<Map<String, String>> = dataStore.data
        .catch { e -> if (e is IOException || e is ClassCastException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decodeAppMap(prefs[mapKey].orEmpty()) }

    val monitorEnabled: Flow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException || e is ClassCastException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[enabledKey] ?: false }

    val headphoneOnly: Flow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException || e is ClassCastException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[headphoneOnlyKey] ?: true }

    suspend fun setAppPreset(packageName: String, preset: String) {
        require(packageName.isNotBlank()) { "bad package name" }
        require(preset.isNotBlank()) { "bad preset name" }
        dataStore.edit { prefs ->
            prefs[mapKey] = encodeAppMap(
                decodeAppMap(prefs[mapKey].orEmpty()) + (packageName to preset),
            )
        }
    }

    suspend fun clearAppPreset(packageName: String) {
        dataStore.edit { prefs ->
            prefs[mapKey] = encodeAppMap(
                decodeAppMap(prefs[mapKey].orEmpty()) - packageName,
            )
        }
    }

    suspend fun repointPreset(oldName: String, newName: String) {
        if (oldName == newName) return
        dataStore.edit { prefs ->
            prefs[mapKey] = encodeAppMap(
                decodeAppMap(prefs[mapKey].orEmpty())
                    .mapValues { (_, v) -> if (v == oldName) newName else v },
            )
        }
    }

    suspend fun purgePreset(name: String) {
        dataStore.edit { prefs ->
            prefs[mapKey] = encodeAppMap(
                decodeAppMap(prefs[mapKey].orEmpty()).filterValues { it != name },
            )
        }
    }

    suspend fun setMonitorEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[enabledKey] = enabled }
    }

    suspend fun setHeadphoneOnly(only: Boolean) {
        dataStore.edit { prefs -> prefs[headphoneOnlyKey] = only }
    }

    companion object {
        internal fun encodeAppMap(map: Map<String, String>): String {
            val obj = JSONObject()
            for ((key, value) in map) obj.put(key, value)
            return obj.toString()
        }

        internal fun decodeAppMap(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            val obj = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return emptyMap()
            }
            val out = LinkedHashMap<String, String>()
            for (key in obj.keys()) {
                val value = obj.optString(key, "")
                if (key.isNotBlank() && value.isNotBlank()) out[key] = value
            }
            return out
        }
    }
}
