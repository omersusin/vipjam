package com.vipjam.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vipjam.appprofile.AppProfileMonitor
import com.vipjam.appprofile.AppProfileStore
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.EmptyState
import com.vipjam.ui.components.PressableCard
import com.vipjam.ui.components.SectionHeader
import com.vipjam.ui.components.StatRow
import com.vipjam.ui.components.rememberReducedMotion
import com.vipjam.ui.components.staggeredDelayForIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

private val PARKED_KEY = stringPreferencesKey("app_profile_parked_map")

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

private fun decodeParked(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val obj = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return emptyMap()
    }
    val out = LinkedHashMap<String, String>()
    for (key in obj.keys()) out[key] = obj.optString(key, "")
    return out.filterValues { it.isNotBlank() }
}

private fun encodeParked(map: Map<String, String>): String {
    val obj = JSONObject()
    for ((key, value) in map) obj.put(key, value)
    return obj.toString()
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
    val parked by context.prefs.data
        .map { decodeParked(it[PARKED_KEY].orEmpty()) }
        .collectAsState(initial = emptyMap())
    val entries by presetStore.entries.collectAsState(initial = emptyList())
    val presetNames = entries.map { it.name }
    var needsPerm by remember { mutableStateOf(true) }
    var permTick by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    val reducedMotion = rememberReducedMotion()

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
    val shown = if (search.isBlank()) apps
    else apps.filter {
        it.label.contains(search.trim(), ignoreCase = true) ||
            it.packageName.contains(search.trim(), ignoreCase = true)
    }

    fun setLinked(pkg: String, label: String, preset: String?) {
        scope.launch {
            if (preset == null) {
                store.clearAppPreset(pkg)
                message("$label set to default")
            } else {
                try {
                    store.setAppPreset(pkg, preset)
                    message("$label linked to $preset")
                } catch (e: Exception) {
                    message("Link failed: ${e.message}")
                }
            }
        }
    }

    fun setAppEnabled(pkg: String, label: String, assigned: String?, on: Boolean) {
        scope.launch {
            if (on) {
                val restore = parked[pkg]
                if (restore.isNullOrBlank()) {
                    message("$label enabled (no linked preset)")
                } else {
                    try {
                        store.setAppPreset(pkg, restore)
                        context.prefs.edit {
                            it[PARKED_KEY] = encodeParked(parked - pkg)
                        }
                        message("$label enabled with $restore")
                    } catch (e: Exception) {
                        message("Enable failed: ${e.message}")
                    }
                }
            } else {
                if (!assigned.isNullOrBlank()) {
                    context.prefs.edit {
                        it[PARKED_KEY] = encodeParked(parked + (pkg to assigned))
                    }
                    store.clearAppPreset(pkg)
                }
                message("$label disabled")
            }
        }
    }

    fun applyNow(pkg: String, label: String, presetName: String) {
        scope.launch {
            val json = entries.find { it.name == presetName }?.settingsJson
            if (json.isNullOrBlank()) {
                message("Preset $presetName not found")
                return@launch
            }
            val master = try {
                context.prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
            } catch (_: Exception) {
                false
            }
            try {
                VipJamService.applyPreset(context, json, master)
                message("Applied $presetName for $label")
            } catch (e: Exception) {
                message("Apply failed: ${e.message}")
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(title = "App Profiles")
        }
        item {
            Text(
                "Links are written through AppProfileStore.setAppPreset / clearAppPreset — the same map AppProfileMonitor reads before applying via VipJamService.applyPreset.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Auto-switch",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = monitorEnabled,
                    onCheckedChange = { scope.launch { store.setMonitorEnabled(it) } },
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Headphones only",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
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
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Usage access needed for auto-switch.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { permTick++ }) {
                        Text("Refresh")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search apps (${apps.size})") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        if (presetNames.isEmpty()) {
            item {
                Text(
                    "No presets installed yet — linking is disabled until a preset exists.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (apps.isEmpty()) {
            item {
                EmptyState(
                    title = "No launchable apps found",
                    body = "Retry with Refresh once packages are available.",
                )
            }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    "No apps match \"${search.trim()}\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(shown, key = { _, it -> it.packageName }) { index, app ->
            StaggeredAppProfile(index, reducedMotion) {
                var expanded by remember { mutableStateOf(false) }
                val assigned = appMap[app.packageName]
                val isDisabled = assigned == null && parked.containsKey(app.packageName)
                Box {
                    PressableCard(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        StatRow(
                            label = app.packageName,
                            value = (assigned ?: "Default") + if (isDisabled) " (disabled)" else "",
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (isDisabled) "Disabled" else "Enabled",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = !isDisabled,
                                onCheckedChange = {
                                    setAppEnabled(app.packageName, app.label, assigned, it)
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text("Link preset")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (assigned != null) applyNow(app.packageName, app.label, assigned)
                                },
                                enabled = assigned != null,
                            ) {
                                Text("Apply now")
                            }
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
                                setLinked(app.packageName, app.label, null)
                            },
                        )
                        presetNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    expanded = false
                                    setLinked(app.packageName, app.label, name)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaggeredAppProfile(index: Int, reducedMotion: Boolean, content: @Composable () -> Unit) {
    if (reducedMotion) {
        content()
    } else {
        val delay = staggeredDelayForIndex(index)
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(240, delayMillis = delay.toInt(), easing = LinearOutSlowInEasing)) +
                slideInVertically(tween(240, delayMillis = delay.toInt(), easing = LinearOutSlowInEasing)) { it / 4 },
            exit = fadeOut(),
        ) {
            content()
        }
    }
}
