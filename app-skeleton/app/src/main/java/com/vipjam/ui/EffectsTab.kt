package com.vipjam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetImporter
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.PresetApplier
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.DebouncedSliderRow
import com.vipjam.ui.components.PopSwitch
import com.vipjam.ui.components.PowerDot
import com.vipjam.ui.components.StripChevron
import com.vipjam.ui.components.chainAnimateSpec
import com.vipjam.ui.components.consoleStaggerDelay
import com.vipjam.ui.components.rememberReducedMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class LiveParam(val id: Int, val v0: Int, val v1: Int, val v2: Int)

internal fun liveParam(settingsJson: String, group: String, field: String): LiveParam? {
    val obj = runCatching { JSONObject(settingsJson) }.getOrNull() ?: return null
    val g = obj.optJSONObject(group) ?: return null
    return when (group) {
        VipJamEffects.BASS ->
            LiveParam(VipJamDispatcher.P_BASS_GAIN, g.optInt("gain", 50), 0, 0)
        VipJamEffects.CLARITY ->
            LiveParam(
                VipJamDispatcher.F_CLARITY,
                g.optInt("gain", 50),
                g.optInt("mode", 0),
                0,
            )
        VipJamEffects.REVERB ->
            LiveParam(
                VipJamDispatcher.F_REVERB,
                g.optInt("roomSize", 0),
                g.optInt("width", 0),
                g.optInt("damp", 0),
            )
        VipJamEffects.EQ -> {
            val index = field.toIntOrNull() ?: return null
            val bands = g.optJSONArray("bands") ?: return null
            if (index !in 0 until bands.length()) return null
            LiveParam(VipJamDispatcher.F_EQ, index, bands.optDouble(index).roundToInt(), 0)
        }
        VipJamEffects.TUBE ->
            LiveParam(VipJamDispatcher.F_TUBE, g.optInt("drive", 0).coerceIn(0, 100), 0, 0)
        else -> null
    }
}

internal fun groupEnableParam(group: String): Int? = when (group) {
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

internal data class ChainStrip(
    val key: String,
    val title: String,
    val groups: List<String>
)

internal val CHAIN_STRIPS = listOf(
    ChainStrip("bass", "Bass", listOf(VipJamEffects.BASS)),
    ChainStrip("clarity", "Clarity", listOf(VipJamEffects.CLARITY)),
    ChainStrip("eq", "Equalizer", listOf(VipJamEffects.EQ)),
    ChainStrip("reverb", "Reverb", listOf(VipJamEffects.REVERB)),
    ChainStrip("conv", "Convolver", listOf(VipJamEffects.CONVOLVER)),
    ChainStrip("tube", "Tube", listOf(VipJamEffects.TUBE)),
    ChainStrip("dyn", "Dynamics", listOf(VipJamEffects.FET, VipJamEffects.DYN_SYS, VipJamEffects.MASTER_LIMITER)),
    ChainStrip("spatial", "Spatial", listOf(VipJamEffects.FIELD, VipJamEffects.DIFF, VipJamEffects.STEREO_IMG, VipJamEffects.HSURR)),
    ChainStrip("ddc", "Device Correction", listOf(VipJamEffects.DDC)),
    ChainStrip("pgc", "Playback Gain", listOf(VipJamEffects.PLAYBACK_GAIN)),
    ChainStrip("cure", "Cure", listOf(VipJamEffects.CURE)),
    ChainStrip("anx", "Analog", listOf(VipJamEffects.ANALOGX)),
    ChainStrip("spk", "Speaker", listOf(VipJamEffects.SPEAKER)),
)

internal fun groupTitle(group: String): String = when (group) {
    VipJamEffects.BASS -> "Bass"
    VipJamEffects.CLARITY -> "Clarity"
    VipJamEffects.EQ -> "Equalizer"
    VipJamEffects.REVERB -> "Reverb"
    VipJamEffects.CONVOLVER -> "Convolver"
    VipJamEffects.TUBE -> "Tube"
    VipJamEffects.FET -> "FET Compressor"
    VipJamEffects.DYN_SYS -> "Dynamic System"
    VipJamEffects.MASTER_LIMITER -> "Limiter"
    VipJamEffects.FIELD -> "Field Surround"
    VipJamEffects.DIFF -> "Diffuse Surround"
    VipJamEffects.STEREO_IMG -> "Stereo Width"
    VipJamEffects.HSURR -> "Headphone Spatial"
    VipJamEffects.DDC -> "Device Correction"
    VipJamEffects.PLAYBACK_GAIN -> "Playback Gain"
    VipJamEffects.CURE -> "Cure"
    VipJamEffects.ANALOGX -> "Analog"
    VipJamEffects.SPEAKER -> "Speaker"
    else -> group.replaceFirstChar { it.uppercase() }
}

internal fun groupBlurb(group: String): String = when (group) {
    VipJamEffects.BASS -> "Low-end weight and punch"
    VipJamEffects.CLARITY -> "Presence and detail"
    VipJamEffects.REVERB -> "Room size and space"
    VipJamEffects.CONVOLVER -> "Impulse response"
    VipJamEffects.TUBE -> "Warm saturation"
    VipJamEffects.FET -> "Fast peak control"
    VipJamEffects.DYN_SYS -> "Adaptive dynamics"
    VipJamEffects.MASTER_LIMITER -> "Ceiling and safety"
    VipJamEffects.FIELD -> "Spatial width"
    VipJamEffects.DIFF -> "Diffuse spaciousness"
    VipJamEffects.STEREO_IMG -> "Stereo spread"
    VipJamEffects.HSURR -> "Virtual surround on headphones"
    VipJamEffects.DDC -> "Headphone correction"
    VipJamEffects.PLAYBACK_GAIN -> "Output trim"
    VipJamEffects.CURE -> "High-frequency ease"
    VipJamEffects.ANALOGX -> "Console warmth"
    VipJamEffects.SPEAKER -> "Driver correction"
    else -> "Stored in preset"
}

internal fun groupStatusLine(group: String, settingsJson: String): String {
    val g = runCatching { JSONObject(settingsJson).optJSONObject(group) }.getOrNull()
        ?: return groupBlurb(group)
    return when (group) {
        VipJamEffects.BASS -> "Gain ${g.optInt("gain", 50)}"
        VipJamEffects.CLARITY -> "Gain ${g.optInt("gain", 50)}"
        VipJamEffects.REVERB -> "Room ${g.optInt("roomSize", 0)} · Width ${g.optInt("width", 0)}"
        VipJamEffects.TUBE -> "Drive ${g.optInt("drive", 0)}%"
        VipJamEffects.CONVOLVER -> "Impulse active"
        VipJamEffects.DDC -> "Correction active"
        else -> groupBlurb(group)
    }
}

internal fun stripSummary(strip: ChainStrip, enables: Map<String, Boolean>, settingsJson: String?): String {
    if (settingsJson == null) return "Apply a preset to tune"
    if (strip.groups.size == 1) {
        val group = strip.groups.first()
        if (enables[group] != true) return "Off"
        if (group == VipJamEffects.EQ) {
            val n = parseEqBandsStored(settingsJson)?.size ?: return "On"
            return "$n bands"
        }
        return groupStatusLine(group, settingsJson)
    }
    val on = strip.groups.count { enables[it] == true }
    if (strip.key == "dyn") {
        fun flag(g: String) = if (enables[g] == true) "On" else "Off"
        return "FET ${flag(VipJamEffects.FET)} · Sys ${flag(VipJamEffects.DYN_SYS)} · Lim ${flag(VipJamEffects.MASTER_LIMITER)}"
    }
    return "$on of ${strip.groups.size} on"
}

internal data class SliderSpec(
    val field: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val defaultValue: Float,
    val format: (Float) -> String,
)

internal fun percentFormat(v: Float): String = "${v.roundToInt()}%"

internal fun dbFormat(v: Float): String {
    val rounded = (v * 10).roundToInt() / 10.0
    return (if (rounded >= 0) "+" else "") + rounded + " dB"
}

internal fun sliderSpecs(group: String, g: JSONObject): List<SliderSpec>? = when (group) {
    VipJamEffects.BASS -> listOf(
        SliderSpec("gain", "Gain", 50f..1000f, 50f) { "${it.roundToInt()}" },
    )
    VipJamEffects.CLARITY -> listOf(
        SliderSpec("gain", "Gain", 0f..450f, 50f) { "${it.roundToInt()}" },
    )
    VipJamEffects.TUBE -> listOf(
        SliderSpec("drive", "Drive", 0f..100f, 0f, ::percentFormat),
    )
    VipJamEffects.REVERB -> listOf(
        SliderSpec("roomSize", "Room size", 0f..100f, 0f, ::percentFormat),
        SliderSpec("width", "Width", 0f..100f, 0f, ::percentFormat),
        SliderSpec("damp", "Damp", 0f..100f, 0f, ::percentFormat),
    )
    VipJamEffects.EQ -> {
        val bands = g.optJSONArray("bands") ?: return emptyList()
        val n = minOf(g.optInt("bandCount", bands.length()), bands.length())
        List(n) { i ->
            SliderSpec(
                i.toString(),
                EqCurveMath.shortFreqLabel(EqCurveMath.bandFreqHz(i)) + " Hz",
                -12f..12f,
                0f,
                ::dbFormat,
            )
        }
    }
    else -> null
}

internal fun specValue(group: String, spec: SliderSpec, settingsJson: String): Float {
    val g = runCatching { JSONObject(settingsJson).optJSONObject(group) }.getOrNull()
        ?: return spec.defaultValue
    return if (group == VipJamEffects.EQ) {
        val idx = spec.field.toIntOrNull() ?: return spec.defaultValue
        val bands = g.optJSONArray("bands") ?: return spec.defaultValue
        if (idx !in 0 until bands.length()) return spec.defaultValue
        bands.optDouble(idx).toFloat().coerceIn(spec.range.start, spec.range.endInclusive)
    } else {
        g.optDouble(spec.field, spec.defaultValue.toDouble()).toFloat()
            .coerceIn(spec.range.start, spec.range.endInclusive)
    }
}

@Composable
fun ConsoleChainSection(
    store: PresetStore,
    snackbar: SnackbarHostState,
    staggerBase: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val debounce = rememberDebouncedDispatcher(scope)
    val reducedMotion = rememberReducedMotion()
    var entered by remember { mutableStateOf(reducedMotion) }
    LaunchedEffect(Unit) { entered = true }
    val entries by store.entries.collectAsState(initial = null)
    val prefsData by context.prefs.data.collectAsState(initial = null)
    val resolvedName: String? = prefsData?.get(VipJamPrefs.ACTIVE_PRESET)
    val list = entries
    val active: PresetEntry? = list?.let { all ->
        all.find { it.name == resolvedName } ?: all.firstOrNull()
    }
    val enables = remember(active?.settingsJson) {
        active?.let {
            runCatching { PresetImporter.groupEnables(it.settingsJson).toMap() }.getOrNull()
        }.orEmpty()
    }
    var expanded by remember { mutableStateOf(setOf(VipJamEffects.EQ)) }

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

    fun onScalar(group: String, field: String, value: Double) {
        val current = active ?: return
        val live = try {
            liveParam(
                PresetApplier.withGroupScalar(current.settingsJson, group, field, value),
                group,
                field,
            )
        } catch (_: Exception) {
            return
        }
        debounce("$group:$field:tx", 120L) {
            if (live != null) {
                VipJamService.dispatchParam(context, live.id, live.v0, live.v1, live.v2)
            }
        }
        debounce("$group:$field:save", 400L) {
            val latest = try {
                store.entries.first().find { it.name == current.name }?.settingsJson
            } catch (_: Exception) {
                null
            }
            val merged = runCatching {
                PresetApplier.withGroupScalar(latest ?: current.settingsJson, group, field, value)
            }.getOrNull() ?: return@debounce
            store.save(PresetEntry(current.name, merged))
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
    }

    fun flattenEq() {
        val current = active ?: return
        val count = parseEqBandsStored(current.settingsJson)?.size ?: return
        scope.launch {
            val latest = try {
                store.entries.first().find { it.name == current.name }?.settingsJson
                    ?: current.settingsJson
            } catch (_: Exception) {
                current.settingsJson
            }
            var json = latest
            for (i in 0 until count) {
                json = runCatching {
                    PresetApplier.withGroupScalar(json, VipJamEffects.EQ, i.toString(), 0.0)
                }.getOrElse {
                    snackbar.showSnackbar("Edit failed: ${it.message}")
                    return@launch
                }
            }
            store.save(PresetEntry(current.name, json))
                .onSuccess { snackbar.showSnackbar("EQ flattened") }
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
            for (i in 0 until count) {
                VipJamService.dispatchParam(context, VipJamDispatcher.F_EQ, i, 0, 0)
            }
        }
    }

    if (active == null) {
        Text(
            "No preset loaded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    CHAIN_STRIPS.forEachIndexed { index, strip ->
        val stripBody: @Composable ColumnScope.() -> Unit = {
            val stripOn = strip.groups.any { enables[it] == true }
            val isOpen = strip.groups.any { expanded.contains(it) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PowerDot(on = stripOn)
                if (strip.groups.size == 1) {
                    val group = strip.groups.first()
                    PopSwitch(
                        checked = enables[group] == true,
                        onToggle = { flipGroup(group, it) }
                    )
                } else {
                    PopSwitch(
                        checked = stripOn,
                        onToggle = { next ->
                            strip.groups.forEach { flipGroup(it, next) }
                        }
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) {
                            expanded = if (isOpen) {
                                expanded - strip.groups.toSet()
                            } else {
                                expanded + strip.groups.first()
                            }
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        strip.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stripSummary(strip, enables, active.settingsJson),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StripChevron(expanded = isOpen)
            }
            AnimatedVisibility(
                visible = isOpen,
                enter = if (reducedMotion) fadeIn() else fadeIn(tween(240, easing = LinearOutSlowInEasing)),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (strip.groups.size == 1) {
                        SingleGroupBody(
                            group = strip.groups.first(),
                            settingsJson = active.settingsJson,
                            onScalar = ::onScalar,
                            onFlattenEq = ::flattenEq
                        )
                    } else {
                        strip.groups.forEach { group ->
                            SubToggleRow(
                                group = group,
                                on = enables[group] == true,
                                hasActive = true,
                                onToggle = { flipGroup(group, it) }
                            )
                        }
                    }
                }
            }
        }
        if (reducedMotion) {
            ChainStripShell(content = stripBody)
        } else {
            val delay = consoleStaggerDelay(staggerBase + index).toInt()
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(tween(240, delay, LinearOutSlowInEasing)) +
                    slideInVertically(tween(240, delay, LinearOutSlowInEasing)) { it / 4 },
                exit = fadeOut()
            ) {
                ChainStripShell(content = stripBody)
            }
        }
    }
}

@Composable
private fun ChainStripShell(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(chainAnimateSpec())
        ) {
            content()
        }
    }
}

@Composable
private fun SubToggleRow(
    group: String,
    on: Boolean,
    hasActive: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = hasActive, role = Role.Switch) { onToggle(!on) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PowerDot(on = on)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                groupTitle(group),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (groupEnableParam(group) == null) "Stored in preset" else if (on) "On" else "Off",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PopSwitch(checked = on, onToggle = onToggle, enabled = hasActive)
    }
}

@Composable
private fun SingleGroupBody(
    group: String,
    settingsJson: String,
    onScalar: (String, String, Double) -> Unit,
    onFlattenEq: () -> Unit
) {
    if (group == VipJamEffects.EQ) {
        val bands = parseEqBandsStored(settingsJson)
        if (bands == null) {
            Text(
                "No band data in this preset",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Edited on the curve above",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onFlattenEq,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Flatten")
                }
            }
        }
        return
    }
    val parsed = runCatching { JSONObject(settingsJson).optJSONObject(group) }.getOrNull()
    if (parsed == null) {
        Text(
            "Could not read settings for this effect",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    val specs = sliderSpecs(group, parsed)
    if (specs == null) {
        if (groupEnableParam(group) == null) {
            Text(
                "No live switch for this stage yet — stored in preset",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    specs.forEach { spec ->
        val v = specValue(group, spec, settingsJson)
        DebouncedSliderRow(
            label = spec.label,
            value = v,
            onValueChange = { onScalar(group, spec.field, it.toDouble()) },
            valueRange = spec.range,
            valueText = { spec.format(it) }
        )
    }
}

@Composable
fun EffectsTab(store: PresetStore, snackbar: SnackbarHostState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ConsoleChainSection(store = store, snackbar = snackbar, staggerBase = 0)
    }
}
