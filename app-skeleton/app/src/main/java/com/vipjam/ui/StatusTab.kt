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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vipjam.BuildConfig
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetImporter
import com.vipjam.data.PresetStore
import com.vipjam.data.TransducerDb
import com.vipjam.data.TransducerSpec
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import com.vipjam.kernel.KernelKind
import com.vipjam.kernel.StagedKernel
import com.vipjam.kernel.ddcStep
import com.vipjam.kernel.KernelStore as StagedKernels
import com.vipjam.service.VipJamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

private val DriverProbeSaver = Saver<DriverProbe?, List<String>>(
    save = {
        if (it == null) emptyList()
        else buildList {
            add(if (it.installed) "1" else "0")
            add(it.versionCode?.toString() ?: "null")
            add(it.versionName ?: "null")
            add(it.latencyMs.toString())
            add(it.failWhy ?: "null")
            add(if (it.probing) "1" else "0")
            add(it.liveParams.entries.joinToString(",") { e -> "${e.key}:${e.value?.toString() ?: "null"}" })
        }
    },
    restore = { saved ->
        if (saved.size < 7) null
        else runCatching {
            val live = saved[6].takeIf { s -> s.isNotEmpty() }?.split(",")?.mapNotNull { pair ->
                val idx = pair.indexOf(':')
                if (idx <= 0) null
                else {
                    val k = pair.substring(0, idx).toIntOrNull() ?: return@mapNotNull null
                    val v = pair.substring(idx + 1).let { s -> if (s == "null") null else s.toIntOrNull() }
                    k to v
                }
            }?.toMap().orEmpty()
            DriverProbe(
                installed = saved[0] == "1",
                versionCode = saved[1].takeIf { s -> s != "null" }?.toIntOrNull(),
                versionName = saved[2].takeIf { s -> s != "null" },
                latencyMs = saved[3].toLongOrNull() ?: 0L,
                failWhy = saved[4].takeIf { s -> s != "null" },
                liveParams = live,
                probing = saved[5] == "1",
            )
        }.getOrNull()
    },
)

@Composable
fun StatusTab(store: PresetStore, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefsData by context.prefs.data.collectAsState(initial = null)
    val entries by store.entries.collectAsState(initial = null)
    val routeMap by store.routePresetMap.collectAsState(initial = emptyMap())
    var probe by rememberSaveable(stateSaver = DriverProbeSaver) { mutableStateOf<DriverProbe?>(null) }
    var audioText by rememberSaveable { mutableStateOf("probing…") }
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
            Text(
                "Status",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() }
            )
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
                        TransducerSuggestion(
                            transducer = transducer,
                            store = store,
                            active = active,
                            snackbar = snackbar,
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

@Composable
private fun TransducerSuggestion(
    transducer: TransducerSpec,
    store: PresetStore,
    active: PresetEntry?,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var staged by remember { mutableStateOf(listOf<StagedKernel>()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(transducer.ddcHint) {
        staged = withContext(Dispatchers.IO) {
            StagedKernels(context.applicationContext).list().filter { it.kind == KernelKind.VDC }
        }
    }

    val hint = transducer.ddcHint
    val match = staged.firstOrNull {
        it.fileName.contains(hint, ignoreCase = true) || it.displayName.contains(hint, ignoreCase = true)
    } ?: staged.firstOrNull {
        it.fileName.contains(transducer.model, ignoreCase = true) ||
            it.displayName.contains(transducer.model, ignoreCase = true)
    }
    val stagedDevice = remember(active?.settingsJson) {
        runCatching {
            JSONObject(active?.settingsJson.orEmpty()).optJSONObject(VipJamEffects.DDC)?.optString("device").orEmpty()
        }.getOrDefault("")
    }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun apply(item: StagedKernel) {
        if (busy) return
        scope.launch {
            busy = true
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val kernels = StagedKernels(context.applicationContext)
                    ddcStep(kernels.readVdcText(item.fileName).getOrThrow()).getOrThrow()
                }
            }
            res.onSuccess { step ->
                VipJamService.dispatchBulk(context, step.id, step.values, step.v0, step.v1, step.v2)
                message("DDC applied: ${item.displayName}")
            }.onFailure { message("Apply failed: ${it.message}") }
            busy = false
        }
    }

    fun stageRef(deviceRef: String) {
        val target = active
        if (target == null) {
            message("No active preset to stage into")
            return
        }
        if (busy) return
        scope.launch {
            busy = true
            val latest = try {
                store.entries.first().find { it.name == target.name }?.settingsJson
            } catch (_: Exception) {
                null
            } ?: target.settingsJson
            val updated = runCatching {
                val obj = JSONObject(latest)
                val ddc = obj.optJSONObject(VipJamEffects.DDC) ?: JSONObject()
                ddc.put("device", deviceRef)
                ddc.put("enable", true)
                obj.put(VipJamEffects.DDC, ddc)
                obj.toString()
            }.getOrNull()
            if (updated == null) {
                message("Stage failed: bad preset JSON")
                busy = false
                return@launch
            }
            store.save(PresetEntry(target.name, updated))
                .onSuccess { message("Staged $deviceRef in ${target.name}") }
                .onFailure { message("Stage failed: ${it.message}") }
            busy = false
        }
    }

    if (match != null) {
        val alreadyStaged = stagedDevice == match.fileName && stagedDevice.isNotEmpty()
        Text(
            if (alreadyStaged) "Suggested correction ${match.displayName} is staged in ${active?.name ?: "preset"}"
            else "Suggested correction: ${match.displayName} (matches \"${if (match.fileName.contains(hint, ignoreCase = true) || match.displayName.contains(hint, ignoreCase = true)) hint else transducer.model}\")",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { apply(match) },
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Apply")
            }
            if (!alreadyStaged) {
                OutlinedButton(
                    onClick = { stageRef(match.fileName) },
                    enabled = !busy && active != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Save to preset")
                }
            }
        }
    } else {
        Text(
            "No staged correction matches hint \"$hint\" (${staged.size} staged). Stage a .vdc in Effects > DDC, or save the hint ref below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { stageRef(hint) },
            enabled = !busy && active != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Save hint to preset")
        }
    }
}
