package com.vipjam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vipjam.appprofile.AppProfileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetRenameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun json(name: String) =
        """{"schemaVersion":3,"origin":"viper","name":"$name"}"""

    @Test
    fun `rename moves body and remaps links`() = runTest {
        val ds = PreferenceDataStoreFactory.create { tmp.newFile("rename.prefs.preferences_pb") }
        val store = PresetStore(ds)
        val apps = AppProfileStore(ds)
        assertTrue(store.save(PresetEntry("Old", json("Old"))).isSuccess)
        store.setRoutePreset("headset", "Old")
        store.setDevicePreset("wired", "Old")
        apps.setAppPreset("com.example.app", "Old")
        assertTrue(store.rename("Old", "New").isSuccess)
        apps.repointPreset("Old", "New")
        assertEquals(listOf("New"), store.entries.first().map { it.name })
        assertEquals(mapOf("headset" to "New"), store.routePresetMap.first())
        assertEquals(mapOf("wired" to "New"), store.devicePresetMap.first())
        assertEquals(mapOf("com.example.app" to "New"), apps.appPresetMap.first())
    }

    @Test
    fun `delete purges links`() = runTest {
        val ds = PreferenceDataStoreFactory.create { tmp.newFile("purge.prefs.preferences_pb") }
        val store = PresetStore(ds)
        val apps = AppProfileStore(ds)
        assertTrue(store.save(PresetEntry("Gone", json("Gone"))).isSuccess)
        assertTrue(store.save(PresetEntry("Keep", json("Keep"))).isSuccess)
        store.setRoutePreset("headset", "Gone")
        store.setDevicePreset("wired", "Gone")
        apps.setAppPreset("com.example.app", "Gone")
        store.delete("Gone")
        apps.purgePreset("Gone")
        assertEquals(listOf("Keep"), store.entries.first().map { it.name })
        assertEquals(emptyMap<String, String>(), store.routePresetMap.first())
        assertEquals(emptyMap<String, String>(), store.devicePresetMap.first())
        assertEquals(emptyMap<String, String>(), apps.appPresetMap.first())
    }

    @Test
    fun `rename rejects bad input`() = runTest {
        val file = tmp.newFile("renamebad.prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        assertTrue(store.save(PresetEntry("A", json("A"))).isSuccess)
        assertTrue(store.rename("A", "A").isFailure)
        assertTrue(store.rename("A", "Bad/Name!").isFailure)
        assertTrue(store.rename("Missing", "B").isFailure)
        assertTrue(store.save(PresetEntry("B", json("B"))).isSuccess)
        assertTrue(store.rename("A", "B").isFailure)
    }
}
