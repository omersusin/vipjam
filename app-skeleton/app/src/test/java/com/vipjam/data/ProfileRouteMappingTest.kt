package com.vipjam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vipjam.appprofile.AppProfileMonitor
import com.vipjam.appprofile.AppProfileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRouteMappingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `effectKey joins with underscore`() {
        assertEquals("bass_gain", VipJamPrefs.effectKey("bass", "gain"))
        assertEquals("equalizer_0", VipJamPrefs.effectKey("equalizer", "0"))
    }

    @Test
    fun `profiles list has three routes`() {
        assertEquals(listOf("headset", "speaker", "bluetooth"), VipJamPrefs.Profiles.ALL)
    }

    @Test
    fun `match is case and whitespace tolerant`() {
        val rules = listOf(DeviceRule("AA:BB", "bluetooth", "Bass"))
        assertEquals("Bass", DeviceRules.match(rules, "AA:BB", "Bluetooth"))
        assertEquals("Bass", DeviceRules.match(rules, "AA:BB", "  bluetooth  "))
    }

    @Test
    fun `first exact match wins`() {
        val rules = listOf(
            DeviceRule("d", "bluetooth", "First"),
            DeviceRule("d", "bluetooth", "Second"),
        )
        assertEquals("First", DeviceRules.match(rules, "d", "bluetooth"))
    }

    @Test
    fun `first wildcard wins`() {
        val rules = listOf(
            DeviceRule("d", "", "First"),
            DeviceRule("d", "", "Second"),
        )
        assertEquals("First", DeviceRules.match(rules, "d", "speaker"))
    }

    @Test
    fun `device id is case sensitive`() {
        val rules = listOf(DeviceRule("AA:BB", "", "Bass"))
        assertNull(DeviceRules.match(rules, "aa:bb", "headset"))
    }

    @Test
    fun `routeId wins over route`() {
        val json = """{"rules":[{"deviceId":"d","route":"speaker","routeId":"bluetooth","preset":"Bass"}]}"""
        assertEquals(listOf(DeviceRule("d", "bluetooth", "Bass")), DeviceRules.parseRules(json))
    }

    @Test
    fun `route only parsed and lowercased`() {
        val json = """{"rules":[{"deviceId":"d","route":"Bluetooth","preset":"Bass"}]}"""
        assertEquals(listOf(DeviceRule("d", "bluetooth", "Bass")), DeviceRules.parseRules(json))
    }

    @Test
    fun `blank route parses to wildcard`() {
        val json = """{"rules":[{"deviceId":"d","preset":"Bass"}]}"""
        assertEquals(listOf(DeviceRule("d", "", "Bass")), DeviceRules.parseRules(json))
    }

    @Test
    fun `save rejects bad names`() = runTest {
        val file = tmp.newFile("names.prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        for (bad in listOf("", "Bad/Name!", "a".repeat(65))) {
            val r = store.save(PresetEntry(bad, """{"schemaVersion":3,"origin":"viper","name":"x"}"""))
            assertTrue("expected failure for $bad", r.isFailure)
        }
    }

    @Test
    fun `save accepts dotted names`() = runTest {
        val file = tmp.newFile("names-ok.prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        val json = """{"schemaVersion":3,"origin":"viper","name":"A-1_2.3 x"}"""
        assertTrue(store.save(PresetEntry("A-1_2.3 x", json)).isSuccess)
        assertEquals(listOf("A-1_2.3 x"), store.entries.first().map { it.name })
    }

    @Test
    fun `save rejects invalid preset json`() = runTest {
        val file = tmp.newFile("names-badjson.prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        assertTrue(store.save(PresetEntry("Good", "{}")).isFailure)
    }

    @Test
    fun `device preset rejects blanks`() = runTest {
        val file = tmp.newFile("devmap.prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        try {
            store.setDevicePreset("", "Bass")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            store.setDevicePreset("d", "")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `app preset rejects blanks`() = runTest {
        val file = tmp.newFile("appmap.prefs.preferences_pb")
        val store = AppProfileStore(PreferenceDataStoreFactory.create { file })
        try {
            store.setAppPreset("", "Bass")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            store.setAppPreset("com.x", "  ")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `corrupt device map decodes empty`() = runTest {
        val file = tmp.newFile("devcorrupt.prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        store.setDevicePreset("d", "Bass")
        store.clearDevicePreset("d")
        assertEquals(emptyMap<String, String>(), store.devicePresetMap.first())
    }

    @Test
    fun `blank mapped preset restores when leaving`() {
        assertEquals(
            AppProfileMonitor.Action.Restore,
            AppProfileMonitor.resolveAction("com.android.settings", "", "com.spotify.music", false, true),
        )
    }

    @Test
    fun `blank mapped preset with no prior does nothing`() {
        assertEquals(
            AppProfileMonitor.Action.None,
            AppProfileMonitor.resolveAction("com.android.settings", "  ", null, false, true),
        )
    }

    @Test
    fun `restore fires on speaker route when gating off`() {
        assertEquals(
            AppProfileMonitor.Action.Restore,
            AppProfileMonitor.resolveAction("com.android.settings", null, "com.spotify.music", false, false),
        )
    }

    @Test
    fun `unknown profile is not headphone route`() {
        assertFalse(AppProfileMonitor.isHeadphoneRoute("wired"))
        assertFalse(AppProfileMonitor.isHeadphoneRoute(""))
        assertFalse(AppProfileMonitor.isHeadphoneRoute("HEADSET"))
    }
}
