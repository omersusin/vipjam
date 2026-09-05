package com.vipjam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vipjam.autoeq.AutoEq
import com.vipjam.autoeq.AutoEqCache
import com.vipjam.autoeq.AutoEqDownloader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KNOWN_SOURCES = listOf("oratory1990", "crinacle", "rtings")

private data class CachedProfile(
    val key: String,
    val fileName: String,
    val summary: String,
)

@Composable
fun AutoEqTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheGen by remember { mutableStateOf(0) }
    val cache = remember(cacheGen) { AutoEqCache(context) }
    val downloader = remember { AutoEqDownloader() }

    var fullUrl by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(KNOWN_SOURCES[0]) }
    var modelPath by remember { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(emptyList<CachedProfile>()) }
    var downloading by remember { mutableStateOf(false) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "autoeq/profiles")
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            files.map { f ->
                val text = try {
                    cache.loadProfileText(f.nameWithoutExtension)
                        ?: f.readText()
                } catch (e: Exception) {
                    null
                }
                val summary = if (text == null) {
                    "unreadable file"
                } else {
                    try {
                        val eq = AutoEq.parseParametric(text)
                        "Preamp ${eq.preampDb} dB, ${eq.filters.size} filters"
                    } catch (e: Exception) {
                        "unparseable: ${e.message}"
                    }
                }
                CachedProfile(f.nameWithoutExtension, f.name, summary)
            }
        }
        profiles = list
    }

    LaunchedEffect(cacheGen) { refresh() }

    fun builtRelativePath(): String {
        val p = modelPath.trim().trim('/')
        if (p.isEmpty()) return ""
        return if (p.endsWith(".txt", ignoreCase = true)) "results/$source/$p"
        else "results/$source/$p/ParametricEQ.txt"
    }

    fun resolvedUrl(): String {
        val u = fullUrl.trim()
        if (u.isNotEmpty()) return u
        val rel = builtRelativePath()
        if (rel.isEmpty()) return ""
        return downloader.profileUrl(rel)
    }

    fun cacheKeyFor(url: String): String {
        val rel = builtRelativePath()
        if (fullUrl.trim().isEmpty() && rel.isNotEmpty()) return "$source/$modelPath".trim()
        val base = AutoEq.BASE_URL
        if (url.startsWith(base)) return url.removePrefix(base).trim('/')
        return url
    }

    fun doDownload(url: String) {
        if (downloading) return
        downloading = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { downloader.fetchText(url) }
            downloading = false
            if (text == null) {
                message("Download failed (bad URL, network, or >1MB)")
                return@launch
            }
            try {
                AutoEq.parseParametric(text)
            } catch (e: Exception) {
                message("Downloaded but invalid ParametricEQ: ${e.message}")
                return@launch
            }
            withContext(Dispatchers.IO) {
                try {
                    cache.saveProfileText(cacheKeyFor(url), text)
                } catch (e: Exception) {
                    null
                }
            }
            message("Saved AutoEq profile")
            refresh()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("AutoEq", style = MaterialTheme.typography.headlineLarge) }
        item {
            Text(
                "No index browsing: find a profile on autoeq.app, then paste its raw ParametricEQ.txt URL or pick a source + model path.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedTextField(
                value = fullUrl,
                onValueChange = { fullUrl = it },
                label = { Text("Full raw URL (…/ParametricEQ.txt)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { sourceMenu = true }) {
                        Text(source)
                    }
                    DropdownMenu(
                        expanded = sourceMenu,
                        onDismissRequest = { sourceMenu = false },
                    ) {
                        KNOWN_SOURCES.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s) },
                                onClick = { source = s; sourceMenu = false },
                            )
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = modelPath,
                onValueChange = { modelPath = it },
                label = { Text("Model path, e.g. over-ear/HD 600/HD 600") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            val url = resolvedUrl()
            Text(
                if (url.isEmpty()) "Enter a URL or a model path to build one."
                else url,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Button(
                onClick = { doDownload(resolvedUrl()) },
                enabled = !downloading && resolvedUrl().isNotBlank(),
            ) { Text(if (downloading) "Downloading…" else "Download") }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Filter downloaded (model substring)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        val shown = if (search.isBlank()) profiles
            else profiles.filter { it.key.contains(search.trim(), ignoreCase = true) }
        items(shown, key = { it.fileName }) { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(p.key, style = MaterialTheme.typography.titleMedium)
                    Text(p.summary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        File(context.filesDir, "autoeq/profiles/${p.fileName}").delete()
                                    }
                                    cacheGen++
                                    message("Deleted ${p.key}")
                                }
                            },
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }
}
