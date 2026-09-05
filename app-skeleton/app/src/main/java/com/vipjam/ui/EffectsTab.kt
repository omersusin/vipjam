package com.vipjam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    val entries by store.entries.collectAsState(initial = emptyList())
    val active: PresetEntry? =
        entries.find { it.name == activeName } ?: entries.firstOrNull()
    val groups = active?.let { PresetImporter.groupEnables(it.settingsJson) }.orEmpty()
    val eqBands = remember(active?.settingsJson) {
        active?.settingsJson?.let { parseEqBands(it) }
    }

    fun persistMaster(on: Boolean) {
        scope.launch {
            context.prefs.edit { it[VipJamPrefs.MASTER_ENABLE] = on }
            VipJamService.start(context, on)
        }
    }

    fun persistProfile(next: String) {
        scope.launch {
            context.prefs.edit { it[VipJamPrefs.ACTIVE_PROFILE] = next }
            VipJamService.setProfile(context, next)
        }
    }

    fun flipGroup(group: String, on: Boolean) {
        val current = active ?: return
        scope.launch {
            val updated = PresetImporter.withGroupEnabled(current.settingsJson, group, on)
            store.save(PresetEntry(current.name, updated))
                .onSuccess { snackbar.showSnackbar("$group ${if (on) "on" else "off"}") }
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
    }

    fun onScalar(group: String, field: String, value: Double) {
        val current = active ?: return
        val updated = try {
            PresetApplier.withGroupScalar(current.settingsJson, group, field, value)
        } catch (e: Exception) {
            return
        }
        val live = liveParam(updated, group, field)
        scope.launch {
            store.save(PresetEntry(current.name, updated))
                .onFailure { snackbar.showSnackbar("Edit failed: ${it.message}") }
        }
        val key = "$group:$field"
        debounce(key, 120L) {
            if (live != null) {
                VipJamService.dispatchParam(context, live.id, live.v0, live.v1, live.v2)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("VipJam", style = MaterialTheme.typography.headlineLarge)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Master", modifier = Modifier.weight(1f))
                Switch(checked = masterOn, onCheckedChange = ::persistMaster)
            }
        }
        item {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
        }
        items(VipJamPrefs.Profiles.ALL) { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.replaceFirstChar { it.uppercase() },
                    modifier = Modifier.weight(1f),
                )
                if (option == profile) {
                    Text("active", style = MaterialTheme.typography.labelMedium)
                } else {
                    OutlinedButton(onClick = { persistProfile(option) }) {
                        Text("Use")
                    }
                }
            }
        }
        item {
            Text(
                "Editing: ${active?.name ?: "no preset — import one first"}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (eqBands != null) {
            item {
                EqCurveEditorCard(
                    bands = eqBands,
                    onBandChange = { index, db ->
                        onScalar(VipJamEffects.EQ, index.toString(), db)
                    },
                )
            }
        }
        items(groups, key = { it.first }) { (group, on) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { flipGroup(group, it) })
                }
                if (on && active != null) {
                    GroupScalars(
                        settingsJson = active.settingsJson,
                        group = group,
                        onScalar = ::onScalar,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupScalars(
    settingsJson: String,
    group: String,
    onScalar: (String, String, Double) -> Unit,
) {
    val obj = runCatching { JSONObject(settingsJson) }.getOrNull() ?: return
    val g = obj.optJSONObject(group) ?: return
    when (group) {
        VipJamEffects.BASS -> {
            ScalarSlider(
                label = "Gain",
                value = g.optInt("gain", 50).toFloat(),
                range = 50f..1000f,
                onChange = { onScalar(group, "gain", it.toDouble()) },
            )
        }
        VipJamEffects.CLARITY -> {
            ScalarSlider(
                label = "Gain",
                value = g.optInt("gain", 50).toFloat(),
                range = 0f..450f,
                onChange = { onScalar(group, "gain", it.toDouble()) },
            )
        }
        VipJamEffects.REVERB -> {
            ScalarSlider(
                label = "Room size",
                value = g.optInt("roomSize", 0).toFloat(),
                range = 0f..100f,
                onChange = { onScalar(group, "roomSize", it.toDouble()) },
            )
            ScalarSlider(
                label = "Width",
                value = g.optInt("width", 0).toFloat(),
                range = 0f..100f,
                onChange = { onScalar(group, "width", it.toDouble()) },
            )
            ScalarSlider(
                label = "Damp",
                value = g.optInt("damp", 0).toFloat(),
                range = 0f..100f,
                onChange = { onScalar(group, "damp", it.toDouble()) },
            )
        }
        VipJamEffects.EQ -> {
            val bands = g.optJSONArray("bands") ?: return
            val bandCount = g.optInt("bandCount", bands.length())
            for (i in 0 until minOf(bandCount, bands.length())) {
                ScalarSlider(
                    label = "Band $i",
                    value = bands.optDouble(i).toFloat(),
                    range = -12f..12f,
                    onChange = { onScalar(group, i.toString(), it.toDouble()) },
                )
            }
        }
    }
}

@Composable
private fun ScalarSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val coerced = value.coerceIn(range.start, range.endInclusive)
    Column {
        Text("$label: $coerced", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = coerced,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}
