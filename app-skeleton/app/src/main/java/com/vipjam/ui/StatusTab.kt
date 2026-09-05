package com.vipjam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vipjam.BuildConfig
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
fun StatusTab(store: PresetStore) {
    val context = LocalContext.current
    val masterOn by context.prefs.data
        .map { it[VipJamPrefs.MASTER_ENABLE] ?: false }
        .collectAsState(initial = false)
    val profile by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET }
        .collectAsState(initial = VipJamPrefs.Profiles.HEADSET)
    val activePreset by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PRESET] }
        .collectAsState(initial = null)
    val count by remember(store) { store.entries.map { it.size } }
        .collectAsState(initial = 0)
    var driver by remember { mutableStateOf("probing…") }
    LaunchedEffect(Unit) {
        driver = withContext(Dispatchers.IO) {
            val d = VipJamDispatcher(0)
            try {
                if (!d.create()) {
                    "not installed"
                } else {
                    val v = d.getParam(VipJamDispatcher.GET_VERSION_CODE)
                    if (v == null) "unreachable" else "driver v$v"
                }
            } finally {
                d.release()
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Status", style = MaterialTheme.typography.headlineLarge)
        Text("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Preset schema: v${VipJamEffects.SCHEMA_VERSION}")
        Text("Master: ${if (masterOn) "on" else "off"}")
        Text("Profile: $profile")
        Text("Active preset: ${activePreset ?: "none"}")
        Text("Stored presets: $count")
        Text("Driver: $driver")
    }
}
