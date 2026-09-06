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
import org.json.JSONObject
import java.io.IOException

data class PresetEntry(val name: String, val settingsJson: String)

class PresetStore(private val dataStore: DataStore<Preferences>) {
    private val namesKey = stringSetPreferencesKey("preset_names")

    private fun bodyKey(name: String) = stringPreferencesKey("preset_body_$name")
    private fun legacyBodyKey(name: String) = stringPreferencesKey("preset_$name")
    private val routeMapKey = stringPreferencesKey("route_preset_map")

    val entries: Flow<List<PresetEntry>> = dataStore.data
        .catch { e -> if (e is IOException || e is ClassCastException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val names = try { prefs[namesKey].orEmpty() } catch (_: ClassCastException) { emptySet() }
            names.sorted().mapNotNull { name ->
                (prefs[bodyKey(name)] ?: prefs[legacyBodyKey(name)])?.let { PresetEntry(name, it) }
            }
        }

    suspend fun save(entry: PresetEntry): Result<Unit> = runCatching {
        require(NAME_RE.matches(entry.name)) { "bad preset name" }
        PresetImporter.parseV3(entry.settingsJson).getOrThrow()
        dataStore.edit { prefs ->
            val names = try { prefs[namesKey].orEmpty() } catch (_: ClassCastException) { emptySet() }
            prefs[namesKey] = names + entry.name
            prefs[bodyKey(entry.name)] = entry.settingsJson
            prefs.remove(legacyBodyKey(entry.name))
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
            val names = try { prefs[namesKey].orEmpty() } catch (_: ClassCastException) { emptySet() }
            prefs[namesKey] = names - name
            prefs.remove(bodyKey(name))
            prefs.remove(legacyBodyKey(name))
            prefs[routeMapKey] = encodeRouteMap(
                decodeRouteMap(prefs[routeMapKey].orEmpty()).filterValues { it != name },
            )
            prefs[deviceMapKey] = encodeDeviceMap(
                decodeDeviceMap(prefs[deviceMapKey].orEmpty()).filterValues { it != name },
            )
        }
    }

    suspend fun rename(oldName: String, newName: String): Result<Unit> = runCatching {
        require(NAME_RE.matches(newName)) { "bad preset name" }
        require(oldName != newName) { "same name" }
        dataStore.edit { prefs ->
            val names = try { prefs[namesKey].orEmpty() } catch (_: ClassCastException) { emptySet() }
            require(oldName in names) { "preset not found" }
            require(newName !in names) { "preset already exists" }
            val body = prefs[bodyKey(oldName)] ?: prefs[legacyBodyKey(oldName)]
                ?: throw IllegalStateException("preset body missing")
            PresetImporter.parseV3(body).getOrThrow()
            prefs[namesKey] = names - oldName + newName
            prefs[bodyKey(newName)] = body
            prefs.remove(bodyKey(oldName))
            prefs.remove(legacyBodyKey(oldName))
            prefs[routeMapKey] = encodeRouteMap(
                decodeRouteMap(prefs[routeMapKey].orEmpty())
                    .mapValues { (_, v) -> if (v == oldName) newName else v },
            )
            prefs[deviceMapKey] = encodeDeviceMap(
                decodeDeviceMap(prefs[deviceMapKey].orEmpty())
                    .mapValues { (_, v) -> if (v == oldName) newName else v },
            )
        }
        Unit
    }

    val routePresetMap: Flow<Map<String, String>> = dataStore.data
        .catch { e -> if (e is IOException || e is ClassCastException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decodeRouteMap(prefs[routeMapKey].orEmpty()) }

    suspend fun setRoutePreset(route: String, presetName: String) {
        require(route.isNotBlank()) { "bad route" }
        require(presetName.isNotBlank()) { "bad preset name" }
        dataStore.edit { prefs ->
            prefs[routeMapKey] = encodeRouteMap(
                decodeRouteMap(prefs[routeMapKey].orEmpty()) + (route to presetName),
            )
        }
    }

    suspend fun clearRoutePreset(route: String) {
        dataStore.edit { prefs ->
            prefs[routeMapKey] = encodeRouteMap(
                decodeRouteMap(prefs[routeMapKey].orEmpty()) - route,
            )
        }
    }

    private val deviceMapKey = stringPreferencesKey("device_preset_map")

    val devicePresetMap: Flow<Map<String, String>> = dataStore.data
        .catch { e -> if (e is IOException || e is ClassCastException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decodeDeviceMap(prefs[deviceMapKey].orEmpty()) }

    suspend fun setDevicePreset(deviceId: String, preset: String) {
        require(deviceId.isNotBlank()) { "bad device id" }
        require(preset.isNotBlank()) { "bad preset name" }
        dataStore.edit { prefs ->
            prefs[deviceMapKey] = encodeDeviceMap(
                decodeDeviceMap(prefs[deviceMapKey].orEmpty()) + (deviceId to preset),
            )
        }
    }

    suspend fun clearDevicePreset(deviceId: String) {
        dataStore.edit { prefs ->
            prefs[deviceMapKey] = encodeDeviceMap(
                decodeDeviceMap(prefs[deviceMapKey].orEmpty()) - deviceId,
            )
        }
    }

    companion object {
        private val NAME_RE = Regex("[A-Za-z0-9 _.-]{1,64}")

        private fun encodeRouteMap(map: Map<String, String>): String {
            val obj = JSONObject()
            for ((key, value) in map) obj.put(key, value)
            return obj.toString()
        }

        private fun decodeRouteMap(raw: String): Map<String, String> {
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

        private fun encodeDeviceMap(map: Map<String, String>): String {
            val obj = JSONObject()
            for ((key, value) in map) obj.put(key, value)
            return obj.toString()
        }

        private fun decodeDeviceMap(raw: String): Map<String, String> {
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
