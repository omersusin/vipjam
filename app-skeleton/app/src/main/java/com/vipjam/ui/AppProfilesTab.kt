package com.vipjam.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.vipjam.appprofile.AppProfileMonitor
import com.vipjam.appprofile.AppProfileStore
import com.vipjam.data.PresetStore
import kotlinx.coroutines.launch

private data class LaunchApp(val label: String, val packageName: String)

private fun queryLaunchable(pm: PackageManager): List<LaunchApp> {
    val out = LinkedHashMap<String, String>()
    for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        val infos = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            continue
        }
        for (info in infos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (out.containsKey(pkg)) continue
            val label = try {
                info.loadLabel(pm)?.toString()
            } catch (_: Exception) {
                null
            }.orEmpty().ifBlank { pkg }
            out[pkg] = label
        }
    }
    return out.map { (pkg, label) -> LaunchApp(label, pkg) }.sortedBy { it.label.lowercase() }
}

@Composable
fun AppProfilesTab(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presetStore = remember { PresetStore(context.prefs) }
    val store = remember { AppProfileStore(context.prefs) }
    val monitorEnabled by store.monitorEnabled.collectAsState(initial = false)
    val headphoneOnly by store.headphoneOnly.collectAsState(initial = true)
    val appMap by store.appPresetMap.collectAsState(initial = emptyMap())
    val entries by presetStore.entries.collectAsState(initial = emptyList())
    val presetNames = entries.map { it.name }
    var needsPerm by remember { mutableStateOf(true) }
    var permTick by remember { mutableStateOf(0) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    LaunchedEffect(permTick) {
        needsPerm = try {
            AppProfileMonitor.needsPermission(context)
        } catch (_: Exception) {
            true
        }
    }

    val apps = remember(permTick) {
        try {
            queryLaunchable(context.packageManager)
        } catch (_: Exception) {
            emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("App Profiles", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-switch", modifier = Modifier.weight(1f))
                Switch(
                    checked = monitorEnabled,
                    onCheckedChange = { scope.launch { store.setMonitorEnabled(it) } },
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Headphones only", modifier = Modifier.weight(1f))
                Switch(
                    checked = headphoneOnly,
                    onCheckedChange = { scope.launch { store.setHeadphoneOnly(it) } },
                )
            }
        }
        if (needsPerm) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Usage access needed for auto-switch.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                                        )
                                    } catch (_: Exception) {
                                        message("Cannot open settings")
                                    }
                                },
                            ) {
                                Text("Grant access")
                            }
                            OutlinedButton(onClick = { permTick++ }) {
                                Text("Refresh")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { permTick++ }) {
                        Text("Refresh")
                    }
                }
            }
        }
        items(apps, key = { it.packageName }) { app ->
            var expanded by remember { mutableStateOf(false) }
            val assigned = appMap[app.packageName]
            Box {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${app.packageName} → ${assigned ?: "Default"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Default") },
                        onClick = {
                            expanded = false
                            scope.launch {
                                store.clearAppPreset(app.packageName)
                                message("${app.label} → Default")
                            }
                        },
                    )
                    presetNames.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                expanded = false
                                scope.launch {
                                    store.setAppPreset(app.packageName, name)
                                    message("${app.label} → $name")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
