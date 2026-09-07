package com.vipjam.ui

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vipjam.BuildConfig
import com.vipjam.data.PresetImporter
import com.vipjam.data.PresetStore
import com.vipjam.data.TransducerDb
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DriverProbe(
    val installed: Boolean,
    val versionCode: Int?,
    val versionName: String?,
    val latencyMs: Long,
    val failWhy: String?,
    val liveParams: Map<Int, Int?>,
    val probing: Boolean = false,
)

private val LIVE_PROBE_IDS = listOf(
    VipJamDispatcher.GET_ENABLED,
    VipJamDispatcher.GET_CONFIGURED,
    VipJamDispatcher.P_BASS_ENABLE,
    VipJamDispatcher.P_CLARITY_ENABLE,
    VipJamDispatcher.P_EQ_ENABLE,
    VipJamDispatcher.P_REVERB_ENABLE,
    VipJamDispatcher.P_CONV_ENABLE,
    VipJamDispatcher.P_PGC_ENABLE,
    VipJamDispatcher.P_DDC_ENABLE,
    VipJamDispatcher.P_DYNSYS_ENABLE,
    VipJamDispatcher.P_TUBE_ENABLE,
    VipJamDispatcher.P_CURE_ENABLE,
    VipJamDispatcher.P_ANALOGX_ENABLE,
    VipJamDispatcher.P_FET_ENABLE,
    VipJamDispatcher.P_VHE_ENABLE,
    VipJamDispatcher.P_DIFF_ENABLE,
    VipJamDispatcher.P_SPK_ENABLE,
)

private fun liveParamForStage(stage: String): Int? = when (stage) {
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

private suspend fun runDriverProbe(): DriverProbe = withContext(Dispatchers.IO) {
    val dispatcher = VipJamDispatcher(0)
    val start = SystemClock.elapsedRealtime()
    try {
        if (!dispatcher.create()) {
            return@withContext DriverProbe(
                installed = false,
                versionCode = null,
                versionName = null,
                latencyMs = SystemClock.elapsedRealtime() - start,
                failWhy = "AudioEffect constructor failed (driver effect not present)",
                liveParams = emptyMap(),
            )
        }
        val version = dispatcher.getParam(VipJamDispatcher.GET_VERSION_CODE)
        val versionName = dispatcher.getStringParam(VipJamDispatcher.GET_VERSION_NAME)
        val live = LIVE_PROBE_IDS.associateWith { dispatcher.getParam(it) }
        DriverProbe(
            installed = true,
            versionCode = version,
            versionName = versionName,
            latencyMs = SystemClock.elapsedRealtime() - start,
            failWhy = if (version == null) "driver reachable but version query returned no data" else null,
            liveParams = live,
        )
    } finally {
        dispatcher.release()
    }
}

private fun deviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth a2dp"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth sco"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb device"
    else -> "type $type"
}

private fun queryAudioOutputs(context: android.content.Context): String {
    return runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "unknown (needs API 23+, device API ${Build.VERSION.SDK_INT})"
        }
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return "unknown (AudioManager unavailable)"
        val outs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        if (outs.isEmpty()) return "none detected"
        outs.joinToString(separator = "; ") { device ->
            val name = runCatching { device.productName.toString() }.getOrNull().orEmpty()
            if (name.isBlank()) deviceTypeName(device.type) else "${deviceTypeName(device.type)} ($name)"
        }
    }.getOrElse { "unknown (${it.message ?: it.javaClass.simpleName})" }
}

private fun onOffUnknown(value: Int?): String = when (value) {
    1 -> "on"
    0 -> "off"
    null -> "unknown"
    else -> "unknown ($value)"
}

@Composable
fun StatusTab(store: PresetStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefsData by context.prefs.data.collectAsState(initial = null)
    val entries by store.entries.collectAsState(initial = null)
    val routeMap by store.routePresetMap.collectAsState(initial = emptyMap())
    var probe by remember { mutableStateOf<DriverProbe?>(null) }
    var audioText by remember { mutableStateOf("probing…") }
    var refreshSeq by remember { mutableStateOf(0) }
    val activity = context as? ComponentActivity

    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshSeq++
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshSeq) {
        audioText = queryAudioOutputs(context)
        probe = probe?.copy(probing = true) ?: DriverProbe(
            installed = false,
            versionCode = null,
            versionName = null,
            latencyMs = 0,
            failWhy = null,
            liveParams = emptyMap(),
            probing = true,
        )
        probe = runDriverProbe()
    }

    fun refresh() {
        scope.launch {
            audioText = queryAudioOutputs(context)
            probe = runDriverProbe()
        }
    }

    val masterOn = prefsData?.get(VipJamPrefs.MASTER_ENABLE)
    val profile = prefsData?.get(VipJamPrefs.ACTIVE_PROFILE)
    val activeName = prefsData?.get(VipJamPrefs.ACTIVE_PRESET)
    val active = entries?.find { it.name == activeName }
    val stages: List<Pair<String, Boolean>>? = runCatching {
        active?.let { PresetImporter.groupEnables(it.settingsJson) }
    }.getOrNull()
    val currentProbe = probe
    val liveMaster = currentProbe?.liveParams?.get(VipJamDispatcher.GET_ENABLED)
    val liveConfigured = currentProbe?.liveParams?.get(VipJamDispatcher.GET_CONFIGURED)

    val diagnostics = buildString {
        appendLine("VipJam diagnostics")
        appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("preset schema: v${VipJamEffects.SCHEMA_VERSION}")
        if (currentProbe == null || currentProbe.probing) {
            appendLine("driver: probing…")
        } else {
            appendLine("driver installed: ${currentProbe.installed}")
            appendLine("driver versionCode: ${currentProbe.versionCode ?: "unknown (${currentProbe.failWhy})"}")
            appendLine("driver versionName: ${currentProbe.versionName ?: "unknown"}")
            appendLine("probe latencyMs: ${currentProbe.latencyMs}")
            appendLine("device master(GET_ENABLED): ${onOffUnknown(liveMaster)}")
            appendLine("device configured(GET_CONFIGURED): ${onOffUnknown(liveConfigured)}")
        }
        appendLine("master pref: ${masterOn?.let { if (it) "on" else "off" } ?: "loading…"}")
        appendLine("profile: ${profile ?: "loading…"}")
        appendLine("active preset: ${activeName ?: "none"}")
        appendLine("route preset map: ${if (routeMap.isEmpty()) "empty" else routeMap.entries.joinToString { "${it.key}->${it.value}" }}")
        appendLine("audio outputs: $audioText")
        when {
            entries == null -> appendLine("stages: loading…")
            active == null -> appendLine("stages: none (no active preset)")
            stages == null -> appendLine("stages: unknown (preset JSON unreadable)")
            else -> for ((stage, on) in stages) {
                val paramId = liveParamForStage(stage)
                val live = paramId?.let { currentProbe?.liveParams?.get(it) }
                val liveText = if (paramId == null) "no device probe id" else onOffUnknown(live)
                appendLine("stage $stage: stored=${if (on) "on" else "off"} device=$liveText")
            }
        }
    }

    fun copyAndShare() {
        clipboard.setText(AnnotatedString(diagnostics))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VipJam diagnostics")
            putExtra(Intent.EXTRA_TEXT, diagnostics)
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Status", style = MaterialTheme.typography.headlineLarge)
            OutlinedButton(onClick = ::refresh, enabled = currentProbe?.probing != true) {
                Text("Refresh")
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Driver", style = MaterialTheme.typography.titleMedium)
                when {
                    currentProbe == null || currentProbe.probing -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("Probing driver…", style = MaterialTheme.typography.bodyMedium)
                    }
                    !currentProbe.installed -> {
                        Text("Installed: no", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            currentProbe.failWhy ?: "unknown (no detail)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        Text("Installed: yes", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Version: ${currentProbe.versionCode ?: "unknown (driver returned no version data)"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Version name: ${currentProbe.versionName ?: "unknown"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Probe latency: ${currentProbe.latencyMs} ms",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        )
                        if (currentProbe.failWhy != null) {
                            Text(
                                currentProbe.failWhy,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Text(
                    "Source: AudioEffect open + GET_VERSION_CODE probe",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Runtime", style = MaterialTheme.typography.titleMedium)
                if (prefsData == null) {
                    Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Master: ${masterOn?.let { if (it) "on" else "off" } ?: "unknown (pref missing)"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (currentProbe?.installed == true) {
                        Text(
                            "Device master: ${onOffUnknown(liveMaster)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Device configured: ${onOffUnknown(liveConfigured)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "Active preset: ${activeName ?: "none"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Output route: ${profile ?: "unknown (pref missing)"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val linked = profile?.let { routeMap[it] }
                    Text(
                        "Route preset: ${linked ?: "unknown (no route mapping for this route)"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Audio device: $audioText", style = MaterialTheme.typography.bodyMedium)
                    val transducer = TransducerDb.resolve(audioText)
                    if (transducer != null) {
                        Text(
                            "Identified: ${transducer.brand} ${transducer.model} · DDC hint: ${transducer.ddcHint}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "Sources: DataStore prefs, route map, AudioManager outputs",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Effect chain", style = MaterialTheme.typography.titleMedium)
                when {
                    entries == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    active == null -> Text(
                        "No active preset. Import or apply one to see stages.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    stages == null -> Text(
                        "Unknown (active preset JSON unreadable)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    stages.isEmpty() -> Text(
                        "Empty (preset has no toggleable stages)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> for ((stage, on) in stages) {
                        val paramId = liveParamForStage(stage)
                        val live = paramId?.let { currentProbe?.liveParams?.get(it) }
                        Column {
                            Text(
                                "$stage: ${if (on) "on" else "off"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (paramId == null) "device: unknown (no probe id for this stage)"
                                else "device: ${onOffUnknown(live)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Sources: stored preset enables + live P_*_ENABLE probes where ids exist",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = ::copyAndShare, modifier = Modifier.fillMaxWidth()) {
            Text("Copy diagnostics")
        }
    }
}
