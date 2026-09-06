package com.vipjam.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contracts.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vipjam.root.FlashEvent
import com.vipjam.root.ModuleFlasher
import com.vipjam.root.ReleaseApi
import com.vipjam.root.RootManager
import com.vipjam.root.RootShell
import com.vipjam.ui.components.EmptyState
import com.vipjam.ui.components.ErrorState
import com.vipjam.ui.components.LoadingState
import com.vipjam.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun ModuleTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flasher = remember { ModuleFlasher() }
    var probing by remember { mutableStateOf(true) }
    var hasSu by remember { mutableStateOf<Boolean?>(null) }
    var manager by remember { mutableStateOf<RootManager?>(null) }
    var prop by remember { mutableStateOf<Map<String, String>?>(null) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var flashing by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var flashLog by remember { mutableStateOf(listOf<String>()) }
    var finished by remember { mutableStateOf<FlashEvent.Finished?>(null) }

    suspend fun probeNow() {
        probing = true
        inlineError = null
        try {
            hasSu = withContext(Dispatchers.IO) { RootShell.hasSu() }
            manager = withContext(Dispatchers.IO) { RootShell.detectManager() }
            prop = withContext(Dispatchers.IO) { flasher.readInstalledProp() }
        } catch (e: Exception) {
            inlineError = e.message ?: e.javaClass.simpleName
        }
        probing = false
    }

    suspend fun runFlash(zip: File) {
        flashing = true
        showDialog = true
        flashLog = emptyList()
        finished = null
        try {
            flasher.flash(zip).collect { event ->
                when (event) {
                    is FlashEvent.Log -> flashLog = flashLog + event.line
                    is FlashEvent.Finished -> {
                        flashing = false
                        finished = event
                        if (!event.ok) {
                            inlineError = "Flash reported failure, see log for details"
                            snackbar.showSnackbar("Module flash failed")
                        }
                    }
                }
            }
            prop = withContext(Dispatchers.IO) { flasher.readInstalledProp() }
        } catch (e: Exception) {
            flashing = false
            inlineError = e.message ?: e.javaClass.simpleName
            snackbar.showSnackbar("Module flash failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun onDownloadAndFlash() {
        scope.launch {
            downloading = true
            downloadProgress = null
            inlineError = null
            try {
                val asset = withContext(Dispatchers.IO) { ReleaseApi.latestModuleAsset() }
                val dest = File(context.cacheDir, asset.name)
                withContext(Dispatchers.IO) {
                    ReleaseApi.download(asset.url, dest) { p -> downloadProgress = p }
                }
                downloading = false
                runFlash(dest)
            } catch (e: Exception) {
                downloading = false
                inlineError = e.message ?: e.javaClass.simpleName
                snackbar.showSnackbar("Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    suspend fun copyLocalToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val dest = File(context.cacheDir, "vipjam-local-" + System.currentTimeMillis() + ".zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Unable to open selected file")
        dest
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            inlineError = null
            try {
                runFlash(copyLocalToCache(uri))
            } catch (e: Exception) {
                inlineError = e.message ?: e.javaClass.simpleName
                snackbar.showSnackbar("Local flash failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    LaunchedEffect(Unit) { probeNow() }

    val canFlash = hasSu == true && manager != null && manager != RootManager.NONE
    val managerLabel = when (manager) {
        RootManager.MAGISK -> "Magisk"
        RootManager.KERNELSU -> "KernelSU"
        RootManager.APATCH -> "APatch"
        RootManager.NONE -> "none"
        null -> "unknown"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = "Root status") {
            if (probing) {
                LoadingState("Checking root…")
            } else {
                Text(
                    "Root (su): " + if (hasSu == true) "yes" else "no",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Manager: $managerLabel",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!canFlash) {
                    Text(
                        "Grant root access and install Magisk, KernelSU, or APatch to flash the module.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SectionCard(title = "Installed module") {
            if (probing) {
                LoadingState("Reading module…")
            } else {
                val current = prop
                if (current == null) {
                    EmptyState(
                        title = "Module not installed",
                        body = "Flash the module below, then reboot to activate it.",
                    )
                } else {
                    Text(
                        "Version: " + (current["version"] ?: "unknown"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Code: " + (current["versionCode"] ?: "unknown"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Name: " + (current["name"] ?: "unknown"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        SectionCard(
            title = "Flash",
            subtitle = "Latest release zip from GitHub, or a zip already on this device",
        ) {
            if (downloading) {
                LoadingState("Downloading…" + (downloadProgress?.let { " $it%" } ?: ""))
                val progress = downloadProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Button(
                onClick = ::onDownloadAndFlash,
                enabled = canFlash && !downloading && !flashing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download & Flash")
            }
            OutlinedButton(
                onClick = { picker.launch("*/*") },
                enabled = canFlash && !downloading && !flashing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Flash local zip")
            }
            val err = inlineError
            if (err != null) {
                ErrorState(
                    message = err,
                    onRetry = { scope.launch { probeNow() } },
                )
            }
        }
    }

    if (showDialog) {
        val listState = rememberLazyListState()
        LaunchedEffect(flashLog.size) {
            if (flashLog.isNotEmpty()) listState.animateScrollToItem(flashLog.size - 1)
        }
        AlertDialog(
            onDismissRequest = { if (!flashing) showDialog = false },
            title = { Text("Flashing module") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (flashing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val done = finished
                    if (done != null) {
                        Text(
                            if (done.ok) "Flash finished. Reboot to activate." else "Flash failed. See log for details.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (done.ok) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(flashLog) { line ->
                            Text(
                                line.ifBlank { " " },
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val done = finished
                    if (done != null && done.ok) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { flasher.reboot() }
                                }
                            },
                        ) {
                            Text("Reboot")
                        }
                    }
                    TextButton(
                        onClick = { showDialog = false },
                        enabled = !flashing,
                    ) {
                        Text("Close")
                    }
                }
            },
        )
    }
}
