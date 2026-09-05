package com.vipjam.appprofile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vipjam.appprofile.AppProfileMonitor.Action
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppProfileMonitorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `mapped app on headphone route applies`() {
        assertEquals(
            Action.Apply("Bass"),
            AppProfileMonitor.resolveAction("com.spotify.music", "Bass", null, true, true),
        )
    }

    @Test
    fun `already applied package does not reapply`() {
        assertEquals(
            Action.None,
            AppProfileMonitor.resolveAction("com.spotify.music", "Bass", "com.spotify.music", true, true),
        )
    }

    @Test
    fun `switching mapped app applies new preset`() {
        assertEquals(
            Action.Apply("Flat"),
            AppProfileMonitor.resolveAction("com.youtube", "Flat", "com.spotify.music", true, true),
        )
    }

    @Test
    fun `leaving to unmapped app restores previous preset`() {
        assertEquals(
            Action.Restore,
            AppProfileMonitor.resolveAction("com.android.settings", null, "com.spotify.music", true, true),
        )
    }

    @Test
    fun `unmapped app with no prior auto switch does nothing`() {
        assertEquals(
            Action.None,
            AppProfileMonitor.resolveAction("com.android.settings", null, null, true, true),
        )
    }

    @Test
    fun `headphone-only gates apply on speaker route`() {
        assertEquals(
            Action.None,
            AppProfileMonitor.resolveAction("com.spotify.music", "Bass", null, true, false),
        )
    }

    @Test
    fun `headphone-only gates restore on speaker route`() {
        assertEquals(
            Action.None,
            AppProfileMonitor.resolveAction("com.android.settings", null, "com.spotify.music", true, false),
        )
    }

    @Test
    fun `headphone-only off applies on speaker route`() {
        assertEquals(
            Action.Apply("Bass"),
            AppProfileMonitor.resolveAction("com.spotify.music", "Bass", null, false, false),
        )
    }

    @Test
    fun `blank package never acts`() {
        assertEquals(Action.None, AppProfileMonitor.resolveAction(null, "Bass", null, false, true))
        assertEquals(Action.None, AppProfileMonitor.resolveAction("", "Bass", null, false, true))
        assertEquals(Action.None, AppProfileMonitor.resolveAction("  ", null, "com.spotify.music", false, true))
    }

    @Test
    fun `bluetooth counts as headphone route`() {
        assertEquals(true, AppProfileMonitor.isHeadphoneRoute("headset"))
        assertEquals(true, AppProfileMonitor.isHeadphoneRoute("bluetooth"))
        assertEquals(false, AppProfileMonitor.isHeadphoneRoute("speaker"))
        assertEquals(false, AppProfileMonitor.isHeadphoneRoute(null))
    }

    @Test
    fun `store app map set and clear`() = runTest {
        val file = tmp.newFile("appprofile.prefs.preferences_pb")
        val store = AppProfileStore(PreferenceDataStoreFactory.create { file })
        assertEquals(emptyMap<String, String>(), store.appPresetMap.first())
        assertEquals(false, store.monitorEnabled.first())
        assertEquals(true, store.headphoneOnly.first())
        store.setAppPreset("com.spotify.music", "Bass")
        store.setAppPreset("com.youtube", "Flat")
        assertEquals(
            mapOf("com.spotify.music" to "Bass", "com.youtube" to "Flat"),
            store.appPresetMap.first(),
        )
        store.clearAppPreset("com.spotify.music")
        assertEquals(mapOf("com.youtube" to "Flat"), store.appPresetMap.first())
    }

    @Test
    fun `store toggles round trip`() = runTest {
        val file = tmp.newFile("appprofile-toggles.prefs.preferences_pb")
        val store = AppProfileStore(PreferenceDataStoreFactory.create { file })
        store.setMonitorEnabled(true)
        store.setHeadphoneOnly(false)
        assertEquals(true, store.monitorEnabled.first())
        assertEquals(false, store.headphoneOnly.first())
        store.setMonitorEnabled(false)
        store.setHeadphoneOnly(true)
        assertEquals(false, store.monitorEnabled.first())
        assertEquals(true, store.headphoneOnly.first())
    }
}
