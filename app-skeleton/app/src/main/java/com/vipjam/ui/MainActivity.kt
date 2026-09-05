package com.vipjam.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.vipjam.BuildConfig
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.effect.VipJamEffects
import com.vipjam.service.VipJamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.prefs by preferencesDataStore("vipjam_prefs")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VipJamApp()
                }
            }
        }
    }
}

private enum class TabPage { Effects, Presets, Status }

@Composable
fun VipJamApp() {
    var page by remember { mutableStateOf(TabPage.Effects) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = page.ordinal) {
                TabPage.entries.forEach { tab ->
                    Tab(
                        selected = page == tab,
                        onClick = { page = tab },
                        text = { Text(tab.name) },
                    )
                }
            }
            when (page) {
                TabPage.Effects -> EffectsTab()
                TabPage.Presets -> PresetsTab(snackbar)
                TabPage.Status -> StatusTab()
            }
        }
    }
}

@Composable
fun EffectsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterOn by context.prefs.data
        .map { it[VipJamPrefs.MASTER_ENABLE] ?: false }
        .collectAsState(initial = false)
    val profile by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET }
        .collectAsState(initial = VipJamPrefs.Profiles.HEADSET)
    val activePreset by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PRESET] }
        .collectAsState(initial = null)

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("VipJam", style = MaterialTheme.typography.headlineLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Master", modifier = Modifier.weight(1f))
            Switch(checked = masterOn, onCheckedChange = ::persistMaster)
        }
        Text("Profile", style = MaterialTheme.typography.titleMedium)
        VipJamPrefs.Profiles.ALL.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(option.replaceFirstChar { it.uppercase() }, modifier = Modifier.weight(1f))
                if (option == profile) {
                    Text("active", style = MaterialTheme.typography.labelMedium)
                } else {
                    OutlinedButton(onClick = { persistProfile(option) }) {
                        Text("Use")
                    }
                }
            }
        }
        Text(
            "Active preset: ${activePreset ?: "none"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Per-effect sliders bind to the audio driver next.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun PresetsTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PresetStore(context.prefs) }
    val entries by store.entries.collectAsState(initial = emptyList())
    var link by remember { mutableStateOf("") }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()
            }
            if (text == null) {
                message("Could not read file")
                return@launch
            }
            store.importText(text)
                .onSuccess { message("Imported $it") }
                .onFailure { message("Invalid preset: ${it.message}") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Presets", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("vipjam://preset link") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        store.importLink(link.trim())
                            .onSuccess {
                                message("Imported $it")
                                link = ""
                            }
                            .onFailure { message("Invalid link: ${it.message}") }
                    }
                },
                enabled = link.isNotBlank(),
            ) {
                Text("Import link")
            }
            OutlinedButton(onClick = { picker.launch("application/json") }) {
                Text("Import file")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.name }) { entry ->
                PresetRow(
                    entry = entry,
                    onApply = {
                        scope.launch {
                            context.prefs.edit {
                                it[VipJamPrefs.ACTIVE_PRESET] = entry.name
                            }
                            message("${entry.name} is now active")
                        }
                    },
                    onDelete = {
                        scope.launch {
                            store.delete(entry.name)
                            message("Deleted ${entry.name}")
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun PresetRow(entry: PresetEntry, onApply: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onApply) { Text("Apply") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
fun StatusTab() {
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
    val store = remember { PresetStore(context.prefs) }
    val count by store.entries.map { it.size }.collectAsState(initial = 0)
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
    }
}
