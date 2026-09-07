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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vipjam.ddc.DdcApi
import com.vipjam.ddc.DdcCache
import com.vipjam.ddc.DdcEntry
import com.vipjam.kernel.KernelStore
import com.vipjam.ui.components.EmptyState
import com.vipjam.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DdcTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kernels = remember { KernelStore(context.applicationContext) }
    val cache = remember { DdcCache(context.applicationContext) }

    var repo by rememberSaveable { mutableStateOf(DdcApi.DEFAULT_REPO) }
    var branch by rememberSaveable { mutableStateOf(DdcApi.DEFAULT_BRANCH) }
    var query by rememberSaveable { mutableStateOf("") }
    var hideOwned by rememberSaveable { mutableStateOf(true) }
    var remoteEntries by remember { mutableStateOf(emptyList<DdcEntry>()) }
    var indexEntries by remember { mutableStateOf(emptyList<DdcEntry>()) }
    var owned by remember { mutableStateOf(emptySet<String>()) }
    var loading by remember { mutableStateOf(false) }
    var stagingName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    suspend fun refreshOwned() {
        val names = withContext(Dispatchers.IO) {
            val staged = kernels.list()
                .filter { it.fileName.lowercase().endsWith(".vdc") }
                .flatMap { listOf(it.fileName, it.displayName, it.fileName.substringAfter('-')) }
            val cached = cache.cachedNames()
            (staged + cached).map { DdcApi.ownedKey(it) }.toSet()
        }
        owned = names
    }

    LaunchedEffect(Unit) {
        indexEntries = withContext(Dispatchers.IO) { cache.loadIndex() } ?: emptyList()
        refreshOwned()
    }

    val indexPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.let { String(it, Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                message("Cannot read index file")
                return@launch
            }
            val parsed = try {
                DdcApi.parseIndexJson(text)
            } catch (e: Exception) {
                message("Bad index file: ${e.message}")
                return@launch
            }
            withContext(Dispatchers.IO) { runCatching { cache.saveIndex(parsed) } }
            indexEntries = parsed
            message("Index loaded: ${parsed.size} entries")
        }
    }

    fun doBrowse() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { DdcApi.listFiles(repo.trim(), branch.trim()) }
            } catch (e: Exception) {
                loading = false
                error = "Browse failed: ${e.message ?: "network error"}"
                return@launch
            }
            loading = false
            remoteEntries = entries
            if (entries.isEmpty()) message("No .vdc files in repo")
        }
    }

    fun doStage(entry: DdcEntry) {
        if (stagingName != null) return
        stagingName = entry.name
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                cache.loadVdcText(entry.name) ?: runCatching {
                    DdcApi.fetchVdcText(entry.url)
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                stagingName = null
                message("Download failed: ${entry.name}")
                return@launch
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val res = withContext(Dispatchers.IO) { kernels.stageBytes(entry.name, bytes) }
            res.onSuccess {
                withContext(Dispatchers.IO) {
                    runCatching { cache.saveVdcText(entry.name, text) }
                }
                refreshOwned()
                message("Staged ${entry.name}")
            }.onFailure {
                message("Stage failed: ${it.message}")
            }
            stagingName = null
        }
    }

    val combined = remember(remoteEntries, indexEntries) {
        val seen = HashSet<String>()
        (indexEntries + remoteEntries).filter { seen.add(it.name.lowercase()) }
    }
    val searched = remember(combined, query) { DdcApi.search(combined, query) }
    val shown = remember(searched, owned, hideOwned) {
        if (hideOwned) DdcApi.filterUnowned(searched, owned) else searched
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionHeader(title = "DDC library") }
        item {
            Text(
                "No bundled DDC index ships in this build. Browse the default community repo " +
                    "(${DdcApi.DEFAULT_REPO}@${DdcApi.DEFAULT_BRANCH}, " +
                    "changeable in DdcApi.DEFAULT_REPO) or load a local JSON index " +
                    "[{\"name\",\"url\"}]. Owned (already staged) entries are skipped.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text("Repo (owner/name)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                label = { Text("Branch") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { doBrowse() },
                    enabled = !loading && repo.isNotBlank() && branch.isNotBlank(),
                ) { Text(if (loading) "Browsing…" else "Browse repo") }
                OutlinedButton(onClick = { indexPicker.launch("*/*") }) {
                    Text("Load index file")
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
        if (error != null) {
            item {
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search corrections (name substring)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { hideOwned = !hideOwned }) {
                    Text(if (hideOwned) "Showing: unowned" else "Showing: all")
                }
                Text(
                    "${shown.size} shown · ${owned.size} owned · staged files apply in Effects > DDC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    title = if (combined.isEmpty()) "No DDC entries yet" else "No matches",
                    body = if (combined.isEmpty()) "Browse the repo or load an index file above."
                    else "Adjust the search or toggle owned visibility.",
                )
            }
        }
        items(shown, key = { it.name.lowercase() }) { entry ->
            val isOwned = owned.contains(DdcApi.ownedKey(entry.name))
            val busy = stagingName == entry.name
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isOwned) "staged — skipped" else entry.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { doStage(entry) },
                        enabled = !isOwned && stagingName == null,
                    ) { Text(if (busy) "Staging…" else "Download & stage") }
                }
            }
        }
    }
}
