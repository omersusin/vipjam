package com.vipjam.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetImporter
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.EmptyState
import com.vipjam.ui.components.LoadingState
import com.vipjam.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun quickEnableParam(group: String): Int? = when (group) {
    VipJamEffects.BASS -> VipJamDispatcher.P_BASS_ENABLE
    VipJamEffects.CLARITY -> VipJamDispatcher.P_CLARITY_ENABLE
    else -> null
}

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
    val reducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    var entered by remember { mutableStateOf(reducedMotion) }
    LaunchedEffect(Unit) { entered = true }
    val masterOn by context.prefs.data
        .map { it[VipJamPrefs.MASTER_ENABLE] ?: false }
        .collectAsState(initial = false)
    val profile by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET }
        .collectAsState(initial = VipJamPrefs.Profiles.HEADSET)
    val activeName by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PRESET] }
        .collectAsState(initial = null)
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
    val active = list?.find { it.name == activeName }
    val enables = remember(active?.settingsJson) {
        active?.let {
            runCatching { PresetImporter.groupEnables(it.settingsJson).toMap() }.getOrNull()
        }.orEmpty()
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

    fun applyNext() {
        val all = list ?: return
        if (all.isEmpty()) return
        val index = all.indexOfFirst { it.name == active?.name }
        val next = all[(index + 1) % all.size]
        scope.launch {
            val master = context.prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
            context.prefs.edit { it[VipJamPrefs.ACTIVE_PRESET] = next.name }
            VipJamService.applyPreset(context, next.settingsJson, master)
            snackbar.showSnackbar("Applied ${next.name}")
        }
    }

    fun flipQuick(group: String, on: Boolean) {
        val current = active ?: return
        scope.launch {
            val updated = runCatching {
                PresetImporter.withGroupEnabled(current.settingsJson, group, on)
            }.getOrElse {
                snackbar.showSnackbar("Edit failed: ${it.message}")
                return@launch
            }
            store.save(PresetEntry(current.name, updated))
                .onSuccess {
                    quickEnableParam(group)?.let { id ->
                        VipJamService.dispatchParam(context, id, if (on) 1 else 0)
                    }
                    snackbar.showSnackbar("$group ${if (on) "on" else "off"}")
                }
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
    }

    @Composable
    fun Staggered(index: Int, content: @Composable () -> Unit) {
        if (reducedMotion) {
            content()
        } else {
            val delay = 30 + 45 * index
            val spec = tween<Float>(240, delay, LinearOutSlowInEasing)
            val offsetSpec = tween<Int>(240, delay, LinearOutSlowInEasing)
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(spec) + slideInVertically(offsetSpec) { it / 4 },
                exit = fadeOut()
            ) {
                content()
            }
        }
    }

    @Composable
    fun QuickRow(label: String, checked: Boolean, onFlip: (Boolean) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Switch) { onFlip(!checked) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(checked = checked, onCheckedChange = null)
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
            SectionCard(title = "Master") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Switch) { persistMaster(!masterOn) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (masterOn) "On" else "Off",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(checked = masterOn, onCheckedChange = null)
                }
                Text(
                    text = driverText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!driverDone || driverOk) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                if (driverDone && showModuleLink) {
                    TextButton(
                        onClick = onOpenModule,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(text = "Open System > Module")
                    }
                }
            }
        }
        Staggered(1) {
            SectionCard(title = "Active preset") {
                when {
                    list == null -> LoadingState(message = "Loading presets")
                    active == null -> EmptyState(
                        title = "No active preset",
                        body = "Pick a preset to shape your sound.",
                        actionLabel = "Browse presets",
                        onAction = onOpenPresets
                    )
                    else -> {
                        Text(
                            text = active.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = ::applyNext,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(text = "Apply next")
                            }
                            OutlinedButton(
                                onClick = onOpenPresets,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(text = "Change")
                            }
                        }
                    }
                }
            }
        }
        Staggered(2) {
            SectionCard(title = "Output route") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VipJamPrefs.Profiles.ALL.forEach { route ->
                        FilterChip(
                            selected = profile == route,
                            onClick = { persistProfile(route) },
                            label = { Text(text = routeTitle(route)) }
                        )
                    }
                }
            }
        }
        Staggered(3) {
            SectionCard(title = "Quick toggles") {
                if (active == null) {
                    Text(
                        text = "Apply a preset to tune Bass and Clarity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    QuickRow(
                        label = "Bass",
                        checked = enables[VipJamEffects.BASS] == true,
                        onFlip = { flipQuick(VipJamEffects.BASS, it) }
                    )
                    QuickRow(
                        label = "Clarity",
                        checked = enables[VipJamEffects.CLARITY] == true,
                        onFlip = { flipQuick(VipJamEffects.CLARITY, it) }
                    )
                }
            }
        }
    }
}
