package com.vipjam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.vipjam.ui.components.PressableCard
import com.vipjam.ui.components.rememberReducedMotion
import com.vipjam.ui.components.staggeredDelayForIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun groupEnableParam(group: String): Int? = when (group) {
    VipJamEffects.BASS -> VipJamDispatcher.P_BASS_ENABLE
    VipJamEffects.CLARITY -> VipJamDispatcher.P_CLARITY_ENABLE
    VipJamEffects.EQ -> VipJamDispatcher.P_EQ_ENABLE
    VipJamEffects.REVERB -> VipJamDispatcher.P_REVERB_ENABLE
    VipJamEffects.CONVOLVER -> VipJamDispatcher.P_CONV_ENABLE
    VipJamEffects.PLAYBACK_GAIN -> VipJamDispatcher.P_PGC_ENABLE
    VipJamEffects.DDC -> VipJamDispatcher.P_DDC_ENABLE
    VipJamEffects.DYN_SYS -> VipJamDispatcher.P_DYNSYS_ENABLE
    VipJamEffects.TUBE -> VipJamDispatcher.P_TUBE_ENABLE
    VipJamEffects.CURE -> VipJamDispatcher.P_CURE_ENABLE
    VipJamEffects.ANALOGX -> VipJamDispatcher.P_ANALOGX_ENABLE
    VipJamEffects.FET -> VipJamDispatcher.P_FET_ENABLE
    VipJamEffects.FIELD -> VipJamDispatcher.P_VHE_ENABLE
    VipJamEffects.DIFF -> VipJamDispatcher.P_DIFF_ENABLE
    VipJamEffects.SPEAKER -> VipJamDispatcher.P_SPK_ENABLE
    else -> null
}

private fun groupTitle(group: String): String = when (group) {
    VipJamEffects.BASS -> "Bass"
    VipJamEffects.CLARITY -> "Clarity"
    VipJamEffects.EQ -> "Equalizer"
    VipJamEffects.REVERB -> "Reverb"
    VipJamEffects.CONVOLVER -> "Convolver"
    VipJamEffects.TUBE -> "Tube"
    VipJamEffects.DDC -> "Device Correction"
    VipJamEffects.FET -> "FET Compressor"
    VipJamEffects.DYN_SYS -> "Dynamic System"
    VipJamEffects.MASTER_LIMITER -> "Limiter"
    VipJamEffects.FIELD -> "Field Surround"
    VipJamEffects.DIFF -> "Diffuse Surround"
    VipJamEffects.STEREO_IMG -> "Stereo Width"
    VipJamEffects.HSURR -> "Headphone Spatial"
    else -> group.replaceFirstChar { it.uppercase() }
}

private fun groupBlurb(group: String): String = when (group) {
    VipJamEffects.BASS -> "Low-end weight and punch"
    VipJamEffects.CLARITY -> "Presence and detail"
    VipJamEffects.REVERB -> "Room size and space"
    VipJamEffects.CONVOLVER -> "Impulse response"
    VipJamEffects.TUBE -> "Warm saturation"
    VipJamEffects.DDC -> "Headphone correction"
    VipJamEffects.FET -> "Dynamics control"
    VipJamEffects.DYN_SYS -> "Adaptive dynamics"
    VipJamEffects.MASTER_LIMITER -> "Ceiling and safety"
    VipJamEffects.FIELD -> "Spatial width"
    VipJamEffects.DIFF -> "Diffuse spaciousness"
    VipJamEffects.STEREO_IMG -> "Stereo spread"
    VipJamEffects.HSURR -> "Virtual surround on headphones"
    else -> "Stored in preset"
}

private fun routeTitle(route: String): String =
    route.lowercase().replaceFirstChar { it.uppercase() }

private fun groupStatus(group: String, settingsJson: String): String {
    val parsed = runCatching {
        JSONObject(settingsJson).optJSONObject(group)
    }.getOrNull() ?: return groupBlurb(group)
    return when (group) {
        VipJamEffects.BASS -> "Gain ${parsed.optInt("gain", 50)}"
        VipJamEffects.CLARITY -> "Gain ${parsed.optInt("gain", 50)}"
        VipJamEffects.REVERB -> "Room ${parsed.optInt("roomSize", 0)} · Width ${parsed.optInt("width", 0)}"
        VipJamEffects.TUBE -> "Drive ${parsed.optInt("drive", 0)}%"
        VipJamEffects.CONVOLVER -> "Impulse active"
        VipJamEffects.DDC -> "Correction active"
        else -> groupBlurb(group)
    }
}

@Composable
fun HomeTab(
    store: PresetStore,
    snackbar: SnackbarHostState,
    onOpenSound: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenModule: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
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
    val active = list?.find { it.name == activeName } ?: list?.firstOrNull()
    val enables = remember(active?.settingsJson) {
        active?.let {
            runCatching { PresetImporter.groupEnables(it.settingsJson).toMap() }.getOrNull()
        }.orEmpty()
    }
    val eqBands = remember(active?.settingsJson) {
        active?.settingsJson?.let { parseEqBands(it) }
    }
    val hasActive = active != null

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

    fun flipGroup(group: String, on: Boolean) {
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
                    groupEnableParam(group)?.let { id ->
                        VipJamService.dispatchParam(context, id, if (on) 1 else 0)
                    }
                    snackbar.showSnackbar("${groupTitle(group)} ${if (on) "on" else "off"}")
                }
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
    }

    @Composable
    fun Staggered(index: Int, content: @Composable () -> Unit) {
        if (reducedMotion) {
            content()
        } else {
            val delay = staggeredDelayForIndex(index).toInt()
            val spec = tween<Float>(240, delay, LinearOutSlowInEasing)
            val offsetSpec = tween<IntOffset>(240, delay, LinearOutSlowInEasing)
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
    fun EffectCard(group: String, index: Int) {
        val on = enables[group] == true
        val status = when {
            !hasActive -> "Apply a preset to tune"
            !on -> "Off"
            active != null -> groupStatus(group, active.settingsJson)
            else -> groupBlurb(group)
        }
        Staggered(index) {
            PressableCard(onClick = onOpenSound) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            enabled = hasActive,
                            role = Role.Switch
                        ) { flipGroup(group, !on) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = groupTitle(group),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = on, onCheckedChange = null, enabled = hasActive)
                }
            }
        }
    }

    @Composable
    fun ChainRow(label: String, group: String) {
        val on = enables[group] == true
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    enabled = hasActive,
                    role = Role.Switch
                ) { flipGroup(group, !on) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (!hasActive) "Apply a preset to tune" else if (on) "On" else "Off",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = on, onCheckedChange = null, enabled = hasActive)
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
            PressableCard(onClick = onOpenSound) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Switch) { persistMaster(!masterOn) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Master",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (masterOn) "On · $driverText" else "Off · $driverText",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!driverDone || driverOk) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    Switch(checked = masterOn, onCheckedChange = null)
                }
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
        EffectCard(VipJamEffects.BASS, 1)
        EffectCard(VipJamEffects.CLARITY, 2)
        Staggered(3) {
            val eqOn = enables[VipJamEffects.EQ] == true
            val eqStatus = when {
                !hasActive -> "Apply a preset to tune"
                !eqOn -> "Off"
                eqBands != null -> "${eqBands.size} bands"
                else -> "On"
            }
            PressableCard(onClick = onOpenSound) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            enabled = hasActive,
                            role = Role.Switch
                        ) { flipGroup(VipJamEffects.EQ, !eqOn) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Equalizer",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = eqStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = eqOn, onCheckedChange = null, enabled = hasActive)
                }
                val bands = eqBands
                if (eqOn && bands != null) {
                    EqMiniPreview(bands = bands)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onOpenSound,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(text = "Edit")
                    }
                }
            }
        }
        EffectCard(VipJamEffects.REVERB, 4)
        EffectCard(VipJamEffects.CONVOLVER, 5)
        EffectCard(VipJamEffects.TUBE, 6)
        Staggered(7) {
            PressableCard(onClick = onOpenSound) {
                Text(
                    text = "Dynamics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "FET · Dynamic System · Limiter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChainRow(label = "FET Compressor", group = VipJamEffects.FET)
                ChainRow(label = "Dynamic System", group = VipJamEffects.DYN_SYS)
                ChainRow(label = "Limiter", group = VipJamEffects.MASTER_LIMITER)
            }
        }
        Staggered(8) {
            PressableCard(onClick = onOpenSound) {
                Text(
                    text = "Spatial",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Field · Diffuse · Stereo · Headphone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChainRow(label = "Field Surround", group = VipJamEffects.FIELD)
                ChainRow(label = "Diffuse Surround", group = VipJamEffects.DIFF)
                ChainRow(label = "Stereo Width", group = VipJamEffects.STEREO_IMG)
                ChainRow(label = "Headphone Spatial", group = VipJamEffects.HSURR)
            }
        }
        EffectCard(VipJamEffects.DDC, 9)
        Staggered(10) {
            PressableCard(onClick = onOpenPresets) {
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
                            text = "Preset",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = active.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
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
        if (driverDone && showModuleLink) {
            Staggered(11) {
                PressableCard(onClick = onOpenModule) {
                    Text(
                        text = "Audio driver",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = driverText,
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
                            Text(text = "Install driver")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqMiniPreview(bands: List<Double>) {
    val curve = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics { contentDescription = "EQ curve preview" }
    ) {
        val padX = 4.dp.toPx()
        val padY = 8.dp.toPx()
        val zeroY = EqCurveMath.dbToY(0f, size.height, padY, padY)
        drawLine(zero, androidx.compose.ui.geometry.Offset(padX, zeroY), androidx.compose.ui.geometry.Offset(size.width - padX, zeroY))
        val points = bands.mapIndexed { i, db ->
            androidx.compose.ui.geometry.Offset(
                EqCurveMath.freqToX(EqCurveMath.bandFreqHz(i), size.width, padX, padX),
                EqCurveMath.dbToY(db.toFloat(), size.height, padY, padY)
            )
        }
        for (i in 0 until points.size - 1) {
            drawLine(grid, points[i], points[i + 1])
        }
        val path = androidx.compose.ui.graphics.Path().apply {
            if (points.isNotEmpty()) {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
        }
        drawPath(
            path = path,
            color = curve,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
        )
        points.forEach { drawCircle(curve, radius = 3.dp.toPx(), center = it) }
    }
}
