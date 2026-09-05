package com.vipjam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceRulesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `exact device plus route beats wildcard route`() {
        val rules = listOf(
            DeviceRule("AA:BB:CC:DD:EE:FF", "", "Fallback"),
            DeviceRule("AA:BB:CC:DD:EE:FF", "bluetooth", "Exact"),
        )
        assertEquals("Exact", DeviceRules.match(rules, "AA:BB:CC:DD:EE:FF", "bluetooth"))
    }

    @Test
    fun `wildcard route matches when no exact route`() {
        val rules = listOf(DeviceRule("wired", "", "WiredPreset"))
        assertEquals("WiredPreset", DeviceRules.match(rules, "wired", "headset"))
        assertEquals("WiredPreset", DeviceRules.match(rules, "wired", "speaker"))
    }

    @Test
    fun `unknown device returns null`() {
        val rules = listOf(DeviceRule("wired", "", "WiredPreset"))
        assertNull(DeviceRules.match(rules, "speaker", "speaker"))
        assertNull(DeviceRules.match(emptyList(), "wired", "headset"))
    }

    @Test
    fun `parse and render round trip`() {
        val rules = listOf(
            DeviceRule("AA:BB:CC:DD:EE:FF", "bluetooth", "Bass"),
            DeviceRule("wired", "", "Flat"),
        )
        assertEquals(rules, DeviceRules.parseRules(DeviceRules.renderRules(rules)))
    }

    @Test
    fun `parse handles jdsp field names`() {
        val json = """{"rules":[{"deviceName":"My Buds","deviceId":"AA:BB:CC:DD:EE:FF","preset":"Bass","routeName":"Bluetooth","routeId":"bluetooth"}]}"""
        assertEquals(
            listOf(DeviceRule("AA:BB:CC:DD:EE:FF", "bluetooth", "Bass")),
            DeviceRules.parseRules(json),
        )
    }

    @Test
    fun `invalid json rejected`() {
        val bad = listOf(
            "",
            "{",
            """{"nope":[]}""",
            """{"rules":"nope"}""",
            """{"rules":[{"deviceId":"","preset":"Bass","routeId":"bluetooth"}]}""",
            """{"rules":[{"deviceId":"wired","routeId":"headset"}]}""",
            """{"rules":[42]}""",
        )
        for (input in bad) {
            try {
                DeviceRules.parseRules(input)
                fail("expected rejection for: $input")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `store device map set and clear`() = runTest {
        val file = tmp.newFile("device.prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        assertEquals(emptyMap<String, String>(), store.devicePresetMap.first())
        store.setDevicePreset("wired", "Flat")
        store.setDevicePreset("AA:BB:CC:DD:EE:FF", "Bass")
        assertEquals(
            mapOf("wired" to "Flat", "AA:BB:CC:DD:EE:FF" to "Bass"),
            store.devicePresetMap.first(),
        )
        store.clearDevicePreset("wired")
        assertEquals(
            mapOf("AA:BB:CC:DD:EE:FF" to "Bass"),
            store.devicePresetMap.first(),
        )
    }
}
