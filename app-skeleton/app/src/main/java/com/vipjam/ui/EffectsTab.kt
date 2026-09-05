package com.vipjam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetImporter
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.service.VipJamService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun EffectsTab(store: PresetStore, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterOn by context.prefs.data
        .map { it[VipJamPrefs.MASTER_ENABLE] ?: false }
        .collectAsState(initial = false)
    val profile by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET }
        .collectAsState(initial = VipJamPrefs.Profiles.HEADSET)
    val activeName by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PRESET] }
        .collectAsState(initial = null)
    val entries by store.entries.collectAsState(initial = emptyList())
    val active: PresetEntry? =
        entries.find { it.name == activeName } ?: entries.firstOrNull()
    val groups = active?.let { PresetImporter.groupEnables(it.settingsJson) }.orEmpty()

    fun persistMaster(on: Boolean) {
        scope.launch {
            context.prefs.edit { it[VipJamPrefs.MASTER_ENABLE] = on }
            VipJamService.start(context, on)
        }
    }

    fun persistProfile(next: String) {
        scope.launch {
            context.prefs.edit { it[VipJamPrefs.ACTIVE_PROFILE] = next }
            VipJamService.setProfile(context, next)
        }
    }

    fun flipGroup(group: String, on: Boolean) {
        val current = active ?: return
        scope.launch {
            val updated = PresetImporter.withGroupEnabled(current.settingsJson, group, on)
            store.save(PresetEntry(current.name, updated))
                .onSuccess { snackbar.showSnackbar("$group ${if (on) "on" else "off"}") }
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("VipJam", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Master", modifier = Modifier.weight(1f))
                Switch(checked = masterOn, onCheckedChange = ::persistMaster)
            }
        }
        item {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
        }
        items(VipJamPrefs.Profiles.ALL) { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.replaceFirstChar { it.uppercase() },
                    modifier = Modifier.weight(1f),
                )
                if (option == profile) {
                    Text("active", style = MaterialTheme.typography.labelMedium)
                } else {
                    OutlinedButton(onClick = { persistProfile(option) }) {
                        Text("Use")
                    }
                }
            }
        }
        item {
            Text(
                "Editing: ${active?.name ?: "no preset — import one first"}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(groups, key = { it.first }) { (group, on) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { flipGroup(group, it) })
            }
        }
    }
}
