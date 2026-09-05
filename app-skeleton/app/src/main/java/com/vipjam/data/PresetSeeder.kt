package com.vipjam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object PresetSeeder {
    private val bundled = listOf("Movie.v3.json", "Game.v3.json")

    suspend fun seedOnce(
        context: Context,
        store: PresetStore,
        prefs: DataStore<Preferences>,
    ): Int {
        if (prefs.data.map { it[VipJamPrefs.V3_INITIALIZED] ?: false }.first()) {
            return 0
        }
        var seeded = 0
        bundled.forEach { asset ->
            runCatching {
                context.assets.open("presets/$asset").bufferedReader().use { reader ->
                    store.importText(reader.readText())
                }
            }.onSuccess { seeded++ }
        }
        if (seeded == bundled.size) {
            prefs.edit { it[VipJamPrefs.V3_INITIALIZED] = true }
        }
        return seeded
    }
}
