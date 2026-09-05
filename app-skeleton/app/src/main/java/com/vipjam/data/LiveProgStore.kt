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

data class LiveProgEntry(val name: String, val script: String)

class LiveProgStore(private val dataStore: DataStore<Preferences>) {
    private val namesKey = stringSetPreferencesKey("liveprog_names")

    private fun bodyKey(name: String) = stringPreferencesKey("liveprog_$name")

    val entries: Flow<List<LiveProgEntry>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[namesKey].orEmpty().sorted().mapNotNull { name ->
                prefs[bodyKey(name)]?.let { LiveProgEntry(name, it) }
            }
        }

    suspend fun save(entry: LiveProgEntry): Result<Unit> = runCatching {
        require(NAME_RE.matches(entry.name)) { "bad script name" }
        val errors = LiveProgScripts.validate(entry.script)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        dataStore.edit { prefs ->
            prefs[namesKey] = prefs[namesKey].orEmpty() + entry.name
            prefs[bodyKey(entry.name)] = entry.script
        }
        Unit
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
