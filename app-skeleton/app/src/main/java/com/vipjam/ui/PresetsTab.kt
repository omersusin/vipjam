package com.vipjam.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetImporter
import com.vipjam.appprofile.AppProfileStore
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.PressableCard
import com.vipjam.ui.components.SectionHeader
import com.vipjam.ui.components.rememberReducedMotion
import com.vipjam.ui.components.staggeredDelayForIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PresetsTab(store: PresetStore, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val appStore = remember { AppProfileStore(context.prefs) }
    val entries by store.entries.collectAsState(initial = null)
    val prefsData by context.prefs.data.collectAsState(initial = null)
    val activeName = prefsData?.get(VipJamPrefs.ACTIVE_PRESET)
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<PresetEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<PresetEntry?>(null) }
    var overwriteName by remember { mutableStateOf<String?>(null) }
    var overwriteText by remember { mutableStateOf<String?>(null) }
    var overwriteIsPaste by remember { mutableStateOf(false) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun applyEntry(entry: PresetEntry, all: List<PresetEntry>) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val prefs = context.prefs.data.first()
            val master = prefs[VipJamPrefs.MASTER_ENABLE] ?: false
            val prevName = prefs[VipJamPrefs.ACTIVE_PRESET]
            val prevJson = all.find { it.name == prevName }?.settingsJson
            context.prefs.edit { it[VipJamPrefs.ACTIVE_PRESET] = entry.name }
            VipJamService.applyPreset(context, entry.settingsJson, master)
            val canUndo = prevName != null && prevJson != null && prevName != entry.name
            val result = snackbar.showSnackbar(
                message = "${entry.name} applied",
                actionLabel = if (canUndo) "Undo" else null,
            )
            if (result == SnackbarResult.ActionPerformed && canUndo && prevName != null && prevJson != null) {
                context.prefs.edit { it[VipJamPrefs.ACTIVE_PRESET] = prevName }
                VipJamService.applyPreset(context, prevJson, master)
            }
        }
    }

    fun shareEntry(entry: PresetEntry) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, entry.name)
            putExtra(Intent.EXTRA_TEXT, entry.settingsJson)
        }
        context.startActivity(Intent.createChooser(send, "Share ${entry.name}"))
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText()
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                message("Could not read file")
                return@launch
            }
            val existing = runCatching { PresetImporter.parseV3(text).getOrThrow().name }.getOrNull()
            if (existing != null && entries?.any { it.name == existing } == true) {
                overwriteName = existing
                overwriteText = text
                overwriteIsPaste = false
                return@launch
            }
            store.importText(text)
                .onSuccess { message("Imported $it") }
                .onFailure { message("Invalid preset: ${it.message}") }
        }
    }

    val list = entries
    val filtered = list?.filter { it.name.contains(query, ignoreCase = true) }.orEmpty()
    val reducedMotion = rememberReducedMotion()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader(title = "Presets")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search presets") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        when {
            list == null -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Text(
                "No presets yet. Paste JSON below or import a file to add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            filtered.isEmpty() -> Text(
                "No presets match \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(filtered, key = { _, it -> it.name }) { index, entry ->
                    val delay = staggeredDelayForIndex(index)
                    if (reducedMotion) {
                        PresetRow(
                            entry = entry,
                            isActive = entry.name == activeName,
                            onApply = { applyEntry(entry, list) },
                            onRename = {
                                renameTarget = entry
                                renameText = entry.name
                                renameError = null
                            },
                            onShare = { shareEntry(entry) },
                            onDelete = { deleteTarget = entry },
                        )
                    } else {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(240, delayMillis = delay.toInt(), easing = LinearOutSlowInEasing)) +
                                slideInVertically(tween(240, delayMillis = delay.toInt(), easing = LinearOutSlowInEasing)) { it / 4 },
                            exit = fadeOut(),
                        ) {
                            PresetRow(
                                entry = entry,
                                isActive = entry.name == activeName,
                                onApply = { applyEntry(entry, list) },
                                onRename = {
                                    renameTarget = entry
                                    renameText = entry.name
                                    renameError = null
                                },
                                onShare = { shareEntry(entry) },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = link,
            onValueChange = { link = it; linkError = null },
            label = { Text("vipjam://preset link") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = linkError != null,
            supportingText = linkError?.let { { Text(it) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        store.importLink(link.trim())
                            .onSuccess {
                                message("Imported $it")
                                link = ""
                                linkError = null
                            }
                            .onFailure { linkError = it.message }
                    }
                },
                enabled = link.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Import link")
            }
            OutlinedButton(
                onClick = { picker.launch("application/json") },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Import file")
            }
        }
        OutlinedTextField(
            value = paste,
            onValueChange = { paste = it; pasteError = null },
            label = { Text("Paste preset JSON") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            isError = pasteError != null,
            supportingText = pasteError?.let { { Text(it) } },
        )
        OutlinedButton(
            onClick = {
                scope.launch {
                    val text = paste.trim()
                    val existing = runCatching { PresetImporter.parseV3(text).getOrThrow().name }.getOrNull()
            if (existing != null && entries?.any { it.name == existing } == true) {
                        overwriteName = existing
                        overwriteText = text
                        overwriteIsPaste = true
                        return@launch
                    }
                    store.importText(text)
                        .onSuccess {
                            message("Imported $it")
                            paste = ""
                            pasteError = null
                        }
                        .onFailure { pasteError = it.message }
                }
            },
            enabled = paste.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Import pasted JSON")
        }
    }

    overwriteName?.let { name ->
        AlertDialog(
            onDismissRequest = { overwriteName = null; overwriteText = null },
            title = { Text("Overwrite $name?") },
            text = { Text("A preset named \"$name\" already exists. Overwrite it?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = overwriteText ?: return@TextButton
                        val isPaste = overwriteIsPaste
                        scope.launch {
                            store.importText(text)
                                .onSuccess {
                                    message("Imported $it")
                                    if (isPaste) {
                                        paste = ""
                                        pasteError = null
                                    }
                                    overwriteName = null
                                    overwriteText = null
                                }
                                .onFailure {
                                    if (isPaste) pasteError = it.message
                                    else message("Invalid preset: ${it.message}")
                                }
                        }
                    },
                ) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { overwriteName = null; overwriteText = null }) { Text("Cancel") }
            },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it; renameError = null },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = renameError != null,
                        supportingText = renameError?.let { { Text(it) } },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val next = renameText.trim()
                        scope.launch {
                            if (next.isEmpty()) {
                                renameError = "Name must not be empty"
                                return@launch
                            }
                            if (next != target.name && list?.any { it.name == next } == true) {
                                renameError = "A preset named \"$next\" already exists"
                                return@launch
                            }
                            val saved = store.rename(target.name, next)
                            if (saved.isFailure) {
                                renameError = saved.exceptionOrNull()?.message
                                return@launch
                            }
                            appStore.repointPreset(target.name, next)
                            if (activeName == target.name) {
                                context.prefs.edit { it[VipJamPrefs.ACTIVE_PRESET] = next }
                            }
                            renameTarget = null
                            message("Renamed to $next")
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete preset?") },
            text = { Text("Delete \"${target.name}\"? This cannot be undone except via Undo.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            store.delete(target.name)
                            appStore.purgePreset(target.name)
                            if (activeName == target.name) {
                                context.prefs.edit { it.remove(VipJamPrefs.ACTIVE_PRESET) }
                            }
                            val result = snackbar.showSnackbar(
                                message = "Deleted ${target.name}",
                                actionLabel = "Undo",
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                store.save(target)
                                    .onSuccess { message("Restored ${target.name}") }
                                    .onFailure { message("Could not restore: ${it.message}") }
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun PresetRow(
    entry: PresetEntry,
    isActive: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    PressableCard(
        onClick = onApply,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            if (isActive) {
                Text("Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onApply,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Apply") }
            OutlinedButton(
                onClick = onRename,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Rename") }
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Share") }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Delete") }
        }
    }
}
