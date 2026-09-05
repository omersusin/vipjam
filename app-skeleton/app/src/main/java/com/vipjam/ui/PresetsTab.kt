package com.vipjam.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PresetsTab(store: PresetStore, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
