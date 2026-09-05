package com.vipjam.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vipjam.data.LiveProgEntry
import com.vipjam.data.LiveProgScripts
import com.vipjam.data.LiveProgStore
import kotlinx.coroutines.launch

@Composable
fun LiveProgTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LiveProgStore(context.prefs) }
    val entries by store.entries.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val errors = LiveProgScripts.validate(script)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("LiveProg", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Script name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                label = { Text("@init / @sample EEL script") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        item {
            if (errors.isEmpty() && script.isNotBlank()) {
                Text("Valid", color = MaterialTheme.colorScheme.primary)
            } else {
                errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            store.save(LiveProgEntry(name.trim(), script))
                                .onSuccess {
                                    message("Saved $name")
                                    name = ""
                                    script = ""
                                }
                                .onFailure { message("Invalid: ${it.message}") }
                        }
                    },
                    enabled = name.isNotBlank() && errors.isEmpty(),
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = {
                        script = "@init\n\n@sample\n"
                    },
                ) {
                    Text("Template")
                }
            }
        }
        items(entries, key = { it.name }) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                name = entry.name
                                script = entry.script
                            },
                        ) {
                            Text("Edit")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    store.delete(entry.name)
                                    message("Deleted ${entry.name}")
                                }
                            },
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
