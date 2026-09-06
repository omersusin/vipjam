package com.vipjam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.PresetApplier
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.PopSwitch
import com.vipjam.ui.components.PowerDot
import com.vipjam.ui.components.SectionHeader
import com.vipjam.ui.components.consoleStaggerDelay
import com.vipjam.ui.components.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun routeTitle(route: String): String =
    route.lowercase().replaceFirstChar { it.uppercase() }

@Composable
fun HomeTab(
    store: PresetStore,
    snackbar: SnackbarHostState,
    onOpenPresets: () -> Unit,
    onOpenModule: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val debounce = rememberDebouncedDispatcher(scope)
    val reducedMotion = rememberReducedMotion()
    var entered by remember { mutableStateOf(reducedMotion) }
    LaunchedEffect(Unit) { entered = true }
    val masterOn by context.prefs.data
        .map { it[VipJamPrefs.MASTER_ENABLE] ?: false }
        .collectAsState(initial = false)
    val profile by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET }
        .collectAsState(initial = VipJamPrefs.Profiles.HEADSET)
    val prefsData by context.prefs.data.collectAsState(initial = null)
    val activeName = prefsData?.get(VipJamPrefs.ACTIVE_PRESET)
    val entries by store.entries.collectAsState(initial = null)
    var driverText by remember { mutableStateOf("Probing driver") }
    var driverOk by remember { mutableStateOf(false) }
    var driverDone by remember { mutableStateOf(false) }
    var showModuleLink by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val outcome = withContext(Dispatchers.IO) {
            val dispatcher = VipJamDispatcher(0)
            try {
                if (!dispatcher.create()) {
                    Triple(false, "Driver not installed", true)
                } else {
                    val version = dispatcher.getParam(VipJamDispatcher.GET_VERSION_CODE)
                    if (version == null) Triple(false, "Module missing", true)
                    else Triple(true, "Driver connected v$version", false)
                }
            } finally {
                dispatcher.release()
            }
        }
        driverOk = outcome.first
        driverText = outcome.second
        showModuleLink = outcome.third
        driverDone = true
    }
    val list = entries
    val active = list?.find { it.name == activeName } ?: list?.firstOrNull()
    val eqOn = remember(active?.settingsJson) {
        active?.settingsJson?.let { parseEqBands(it) } != null
    }
    val storedBands = remember(active?.settingsJson) {
        active?.settingsJson?.let { parseEqBandsStored(it) }
    }

    fun persistMaster(on: Boolean) {
        scope.launch {
            runCatching {
                context.prefs.edit { it[VipJamPrefs.MASTER_ENABLE] = on }
                VipJamService.start(context, on)
            }.onSuccess {
                launch { snackbar.showSnackbar(if (on) "Master on" else "Master off") }
            }.onFailure {
                launch { snackbar.showSnackbar("Master failed: ${it.message}") }
            }
        }
    }

    fun persistProfile(next: String) {
        scope.launch {
            runCatching {
                context.prefs.edit { it[VipJamPrefs.ACTIVE_PROFILE] = next }
                VipJamService.setProfile(context, next)
            }.onSuccess {
                launch { snackbar.showSnackbar("Output: ${routeTitle(next)}") }
            }.onFailure {
                launch { snackbar.showSnackbar("Output failed: ${it.message}") }
            }
        }
    }

    fun flipEq(on: Boolean) {
        val current = active ?: return
        scope.launch {
            val updated = runCatching {
                com.vipjam.data.PresetImporter.withGroupEnabled(
                    current.settingsJson, VipJamEffects.EQ, on
                )
            }.getOrElse {
                snackbar.showSnackbar("Edit failed: ${it.message}")
                return@launch
            }
            store.save(PresetEntry(current.name, updated))
                .onSuccess {
                    VipJamService.dispatchParam(
                        context, VipJamDispatcher.P_EQ_ENABLE, if (on) 1 else 0
                    )
                    snackbar.showSnackbar("Equalizer ${if (on) "on" else "off"}")
                }
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
    }

    fun onBandChange(index: Int, db: Double) {
        val current = active ?: return
        val live = try {
            liveParam(
                PresetApplier.withGroupScalar(
                    current.settingsJson, VipJamEffects.EQ, index.toString(), db
                ),
                VipJamEffects.EQ,
                index.toString(),
            )
        } catch (_: Exception) {
            return
        }
        debounce("eq:$index:tx", 120L) {
            if (live != null) {
                VipJamService.dispatchParam(context, live.id, live.v0, live.v1, live.v2)
            }
        }
        debounce("eq:$index:save", 400L) {
            val latest = try {
                store.entries.first().find { it.name == current.name }?.settingsJson
            } catch (_: Exception) {
                null
            }
            val merged = runCatching {
                PresetApplier.withGroupScalar(
                    latest ?: current.settingsJson, VipJamEffects.EQ, index.toString(), db
                )
            }.getOrNull() ?: return@debounce
            store.save(PresetEntry(current.name, merged))
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
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

    @Composable
    fun Staggered(index: Int, content: @Composable () -> Unit) {
        if (reducedMotion) {
            content()
        } else {
            val delay = consoleStaggerDelay(index).toInt()
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(tween(240, delay, LinearOutSlowInEasing)) +
                    slideInVertically(tween(240, delay, LinearOutSlowInEasing)) { it / 4 },
                exit = fadeOut()
            ) {
                content()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Staggered(0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "VipJam",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                PowerDot(on = driverDone && driverOk)
                Text(
                    driverText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (driverDone && !driverOk) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val order = VipJamPrefs.Profiles.ALL
                        persistProfile(order[(order.indexOf(profile) + 1) % order.size])
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(routeTitle(profile))
                }
            }
        }
        Staggered(1) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PowerDot(on = masterOn)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Master",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (masterOn) "On" else "Off",
                                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PopSwitch(checked = masterOn, onToggle = ::persistMaster)
                    }
                    when {
                        active == null -> Text(
                            "Apply a preset below to shape your sound",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        storedBands == null -> Text(
                            "No EQ data in this preset",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> {
                            if (!eqOn) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "EQ off — curve still edits stored bands",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { flipEq(true) },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("Enable")
                                    }
                                }
                            }
                            ConsoleEqCurve(
                                bands = storedBands,
                                onBandChange = ::onBandChange
                            )
                        }
                    }
                }
            }
        }
        Staggered(2) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "Presets")
                when {
                    list == null -> Text(
                        "Loading presets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    list.isEmpty() -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "No presets yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onOpenPresets,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Manage")
                        }
                    }
                    else -> {
                        val renderList = list
                        LazyRow(
                            state = rememberLazyListState(),
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(renderList, key = { it.name }) { entry ->
                                PresetChip(
                                    entry = entry,
                                    selected = entry.name == (activeName ?: active?.name),
                                    onApply = { applyEntry(entry, renderList) },
                                    onManage = onOpenPresets
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = onOpenPresets,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Manage")
                            }
                        }
                    }
                }
            }
        }
        Staggered(3) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "Chain", subtitle = "Signal order")
                ConsoleChainSection(store = store, snackbar = snackbar, staggerBase = 4)
            }
        }
        if (driverDone && showModuleLink) {
            Staggered(4) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Audio driver",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            driverText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = onOpenModule,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Install driver")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    entry: PresetEntry,
    selected: Boolean,
    onApply: () -> Unit,
    onManage: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onApply, onLongClick = onManage)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PowerDot(on = selected)
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
