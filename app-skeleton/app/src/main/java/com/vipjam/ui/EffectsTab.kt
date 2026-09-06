package com.vipjam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetImporter
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.PresetApplier
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import com.vipjam.service.VipJamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

private data class LiveParam(val id: Int, val v0: Int, val v1: Int, val v2: Int)

private fun liveParam(settingsJson: String, group: String, field: String): LiveParam? {
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
        else -> null
    }
}

private fun groupEnableParam(group: String): Int? = when (group) {
    VipJamEffects.BASS -> VipJamDispatcher.P_BASS_ENABLE
    VipJamEffects.CLARITY -> VipJamDispatcher.P_CLARITY_ENABLE
    VipJamEffects.EQ -> VipJamDispatcher.P_EQ_ENABLE
    VipJamEffects.REVERB -> VipJamDispatcher.P_REVERB_ENABLE
    VipJamEffects.CONVOLVER -> VipJamDispatcher.P_CONV_ENABLE
    else -> null
}

private val GROUP_ORDER = listOf(
    VipJamEffects.BASS,
    VipJamEffects.CLARITY,
    VipJamEffects.REVERB,
    VipJamEffects.FIELD,
    VipJamEffects.DIFF,
    VipJamEffects.STEREO_IMG,
    VipJamEffects.HSURR,
    VipJamEffects.FET,
    VipJamEffects.MBC,
    VipJamEffects.DYN_SYS,
    VipJamEffects.DYN_EQ,
    VipJamEffects.MASTER_LIMITER,
    VipJamEffects.PLAYBACK_GAIN,
    VipJamEffects.LUFS,
    VipJamEffects.CONVOLVER,
    VipJamEffects.SPECTRUM,
    VipJamEffects.DDC,
    VipJamEffects.CURE,
    VipJamEffects.TUBE,
    VipJamEffects.ANALOGX,
    VipJamEffects.SPEAKER,
    VipJamEffects.LOUDNESS,
    VipJamEffects.BASS_MONO,
    VipJamEffects.PSYCHO_BASS,
    VipJamEffects.LIVEPROG,
)

private fun orderIndex(group: String): Int {
    val i = GROUP_ORDER.indexOf(group)
    return if (i < 0) Int.MAX_VALUE else i
}

private fun groupTitle(group: String): String = when (group) {
    VipJamEffects.BASS -> "Bass"
    VipJamEffects.CLARITY -> "Clarity"
    VipJamEffects.EQ -> "Equalizer"
    VipJamEffects.REVERB -> "Reverb"
    VipJamEffects.MASTER_LIMITER -> "Limiter"
    VipJamEffects.FET -> "Dynamics"
    VipJamEffects.MBC -> "Multiband Dynamics"
    VipJamEffects.DYN_SYS -> "Dynamic System"
    VipJamEffects.DYN_EQ -> "Dynamic EQ"
    VipJamEffects.FIELD -> "Spatial"
    VipJamEffects.DIFF -> "Spatial Diffuse"
    VipJamEffects.STEREO_IMG -> "Stereo Width"
    VipJamEffects.HSURR -> "Headphone Spatial"
    VipJamEffects.CONVOLVER -> "Convolver"
    VipJamEffects.PLAYBACK_GAIN -> "Playback Gain"
    VipJamEffects.LUFS -> "Loudness Level"
    VipJamEffects.SPECTRUM -> "Spectrum Extension"
    VipJamEffects.DDC -> "Device Correction"
    VipJamEffects.CURE -> "Cure"
    VipJamEffects.TUBE -> "Tube"
    VipJamEffects.ANALOGX -> "Analog"
    VipJamEffects.SPEAKER -> "Speaker Correction"
    VipJamEffects.LOUDNESS -> "Loudness"
    VipJamEffects.BASS_MONO -> "Bass Mono"
    VipJamEffects.PSYCHO_BASS -> "Psychoacoustic Bass"
    VipJamEffects.LIVEPROG -> "Live Programming"
    else -> group.replaceFirstChar { it.uppercase() }
}

private fun groupBlurb(group: String): String = when (group) {
    VipJamEffects.BASS -> "Low-end weight and punch"
    VipJamEffects.CLARITY -> "Presence and detail"
    VipJamEffects.REVERB -> "Room size and space"
    VipJamEffects.FIELD -> "Spatial width"
    VipJamEffects.DIFF -> "Diffuse spaciousness"
    VipJamEffects.STEREO_IMG -> "Stereo spread"
    VipJamEffects.HSURR -> "Virtual surround on headphones"
    VipJamEffects.FET -> "Dynamics control"
    VipJamEffects.MBC -> "Per-band dynamics"
    VipJamEffects.MASTER_LIMITER -> "Ceiling and safety"
    else -> "Stored in preset"
}

private data class SliderSpec(
    val field: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val defaultValue: Float,
    val format: (Float) -> String,
)

private fun percentFormat(v: Float): String = "${v.roundToInt()}%"

private fun dbFormat(v: Float): String {
    val rounded = (v * 10).roundToInt() / 10.0
    return (if (rounded >= 0) "+" else "") + rounded + " dB"
}

private fun sliderSpecs(group: String, g: JSONObject): List<SliderSpec>? = when (group) {
    VipJamEffects.BASS -> listOf(
        SliderSpec("gain", "Gain", 50f..1000f, 50f) { "${it.roundToInt()}" },
    )
    VipJamEffects.CLARITY -> listOf(
        SliderSpec("gain", "Gain", 0f..450f, 50f) { "${it.roundToInt()}" },
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

private fun specValue(group: String, spec: SliderSpec, settingsJson: String): Float {
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
fun EffectsTab(store: PresetStore, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val debounce = rememberDebouncedDispatcher(scope)
    val masterOn by context.prefs.data
        .map { it[VipJamPrefs.MASTER_ENABLE] ?: false }
        .collectAsState(initial = false)
    val profile by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET }
        .collectAsState(initial = VipJamPrefs.Profiles.HEADSET)
    val activeName by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PRESET] }
        .collectAsState(initial = null)
    var loadedEntries by remember { mutableStateOf<List<PresetEntry>?>(null) }
    LaunchedEffect(store) {
        store.entries.collect { loadedEntries = it }
    }
    var driverText by remember { mutableStateOf("probing…") }
    var driverOk by remember { mutableStateOf(false) }
    var driverProbing by remember { mutableStateOf(true) }
    var probeNonce by remember { mutableStateOf(0) }
    LaunchedEffect(probeNonce) {
        driverProbing = true
        val outcome: Pair<Boolean, String> = withContext(Dispatchers.IO) {
            val d = VipJamDispatcher(0)
            try {
                if (!d.create()) {
                    false to "not installed"
                } else {
                    val v = d.getParam(VipJamDispatcher.GET_VERSION_CODE)
                    if (v == null) false to "unreachable" else true to "connected v$v"
                }
            } finally {
                d.release()
            }
        }
        driverOk = outcome.first
        driverText = outcome.second
        driverProbing = false
    }
    val entries = loadedEntries
    val active: PresetEntry? = entries?.let { list ->
        list.find { it.name == activeName } ?: list.firstOrNull()
    }
    val groups: List<Pair<String, Boolean>> = active?.let {
        runCatching { PresetImporter.groupEnables(it.settingsJson) }.getOrDefault(emptyList())
    }.orEmpty()
    val orderedGroups = groups
        .filter { it.first != VipJamEffects.EQ }
        .sortedWith(compareBy({ orderIndex(it.first) }, { it.first }))
    val eqEnabled = groups.firstOrNull { it.first == VipJamEffects.EQ }?.second
    val eqBands = remember(active?.settingsJson) {
        active?.settingsJson?.let { parseEqBands(it) }
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
                launch { snackbar.showSnackbar("Profile: $next") }
            }.onFailure {
                launch { snackbar.showSnackbar("Profile failed: ${it.message}") }
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
        val count = eqBands?.size ?: return
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "hero") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Master", style = MaterialTheme.typography.headlineSmall)
                            val status = if (driverProbing) {
                                "Driver probing…"
                            } else {
                                "Driver $driverText"
                            }
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!driverProbing && driverOk) {
                                    MaterialTheme.colorScheme.primary
                                } else if (!driverProbing) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (!masterOn && !driverProbing && driverOk) {
                                Text(
                                    "Bypassed — enable to hear effects",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = masterOn, onCheckedChange = ::persistMaster)
                    }
                    if (!driverProbing && !driverOk) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Install the audio driver, then retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { probeNonce++ }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
        item(key = "profile") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Output profile", style = MaterialTheme.typography.titleMedium)
                    VipJamPrefs.Profiles.ALL.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                option.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (option == profile) {
                                Text(
                                    "active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                OutlinedButton(onClick = { persistProfile(option) }) {
                                    Text("Use")
                                }
                            }
                        }
                    }
                }
            }
        }
        when {
            entries == null -> {
                item(key = "loading") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Loading presets…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            active == null -> {
                item(key = "empty") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("No presets yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Import one in the Presets tab, then tune it here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            else -> {
                item(key = "editing") {
                    Text(
                        "Editing: ${active.name}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                when {
                    eqEnabled == true && eqBands != null -> {
                        item(key = "eqcurve") {
                            EqCurveEditorCard(
                                bands = eqBands,
                                onBandChange = { index, db ->
                                    onScalar(VipJamEffects.EQ, index.toString(), db)
                                },
                            )
                        }
                        item(key = "eqbands") {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Bands",
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = ::flattenEq) {
                                            Text("Flatten")
                                        }
                                    }
                                    val eqObj = runCatching {
                                        JSONObject(active.settingsJson)
                                            .optJSONObject(VipJamEffects.EQ)
                                    }.getOrNull()
                                    val specs = eqObj?.let { sliderSpecs(VipJamEffects.EQ, it) }
                                    if (specs.isNullOrEmpty()) {
                                        Text(
                                            "No band data in this preset",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        specs.forEach { spec ->
                                            val v = specValue(
                                                VipJamEffects.EQ,
                                                spec,
                                                active.settingsJson,
                                            )
                                            DebouncedSliderRow(
                                                label = spec.label,
                                                valueLabel = spec.format(v),
                                                value = v,
                                                range = spec.range,
                                                onCommit = {
                                                    onScalar(
                                                        VipJamEffects.EQ,
                                                        spec.field,
                                                        it.toDouble(),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    eqEnabled == false -> {
                        item(key = "eqoff") {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Equalizer",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            "Turn it on to shape the 10-band curve",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { flipGroup(VipJamEffects.EQ, true) },
                                    ) {
                                        Text("Enable")
                                    }
                                }
                            }
                        }
                    }
                }
                items(orderedGroups, key = { it.first }) { (group, on) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        groupTitle(group),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        groupBlurb(group),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = on,
                                    onCheckedChange = { flipGroup(group, it) },
                                )
                            }
                            if (on) {
                                val parsed = runCatching {
                                    JSONObject(active.settingsJson).optJSONObject(group)
                                }.getOrNull()
                                if (parsed == null) {
                                    Text(
                                        "Could not read settings for this effect",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    val specs = sliderSpecs(group, parsed)
                                    if (specs == null) {
                                        Text(
                                            "Included in preset — no manual controls yet",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        specs.forEach { spec ->
                                            val v = specValue(
                                                group,
                                                spec,
                                                active.settingsJson,
                                            )
                                            DebouncedSliderRow(
                                                label = spec.label,
                                                valueLabel = spec.format(v),
                                                value = v,
                                                range = spec.range,
                                                onCommit = {
                                                    onScalar(group, spec.field, it.toDouble())
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebouncedSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var local by remember(value) {
        mutableStateOf(value.coerceIn(range.start, range.endInclusive))
    }
    var pending by remember { mutableStateOf<Job?>(null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = local.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                local = it
                pending?.cancel()
                val v = it
                pending = scope.launch {
                    delay(120L)
                    onCommit(v)
                }
            },
            valueRange = range,
        )
    }
}
