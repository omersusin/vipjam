package com.vipjam.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.vipjam.BuildConfig
import com.vipjam.data.PresetSeeder
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.effect.VipJamEffects
import com.vipjam.log.VipJamLog
import com.vipjam.service.VipJamService
import com.vipjam.root.ReleaseApi
import com.vipjam.root.ReleaseInfo
import com.vipjam.ui.components.DriverStatusDialog
import com.vipjam.ui.components.EmptyState
import com.vipjam.ui.components.LoadingState
import com.vipjam.ui.components.PowerDot
import com.vipjam.ui.components.SectionCard
import com.vipjam.ui.theme.VipJamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Context.prefs by preferencesDataStore("vipjam_prefs")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VipJamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VipJamApp()
                }
            }
        }
    }
}

internal enum class Detail(val label: String) {
    Presets("Presets"),
    Lab("Lab"),
    System("System")
}

internal enum class LabTool(val label: String) {
    TestTone("Tone"),
    LiveProg("LiveProg"),
    AutoEq("AutoEq"),
    Ddc("DDC")
}

internal enum class SystemDetail(val label: String, val blurb: String) {
    Apps("App profiles", "Per-app routing rules"),
    Module("Module installer", "Root installer and updates"),
    Status("Diagnostics", "Driver, runtime and chain"),
    Logs("Logs", "On-device file log"),
    About("About", "Version and schema")
}

private fun routeTitle(route: String): String =
    route.lowercase().replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VipJamApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var detail by rememberSaveable { mutableStateOf<Detail?>(null) }
    var systemDetail by rememberSaveable { mutableStateOf<SystemDetail?>(null) }
    var labTool by rememberSaveable { mutableStateOf(LabTool.TestTone) }
    var statusOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val store = remember { PresetStore(context.prefs) }

    LaunchedEffect(Unit) {
        scope.launch {
            val seeded = PresetSeeder.seedOnce(context, store, context.prefs)
            if (seeded > 0) snackbar.showSnackbar("Seeded $seeded built-in presets")
        }
    }

    val masterOn by context.prefs.data
        .map { it[VipJamPrefs.MASTER_ENABLE] ?: false }
        .collectAsState(initial = false)
    val activeName by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PRESET] }
        .collectAsState(initial = null)
    val profile by context.prefs.data
        .map { it[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET }
        .collectAsState(initial = VipJamPrefs.Profiles.HEADSET)

    var driverText by rememberSaveable { mutableStateOf("Probing driver") }
    var driverOk by remember { mutableStateOf(false) }
    var driverDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val outcome = withContext(Dispatchers.IO) {
            val dispatcher = VipJamDispatcher(0)
            try {
                if (!dispatcher.create()) {
                    Triple(false, "Driver not installed", true)
                } else {
                    val version = dispatcher.getParam(VipJamDispatcher.GET_VERSION_CODE)
                    if (version == null) Triple(false, "Module missing", true)
                    else Triple(true, "Driver v$version", false)
                }
            } finally {
                dispatcher.release()
            }
        }
        driverOk = outcome.first
        driverText = outcome.second
        driverDone = true
    }

    fun persistMaster(on: Boolean) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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

    val current = detail
    val sysDetail = systemDetail
    fun popBack() {
        if (sysDetail != null) {
            systemDetail = null
        } else {
            systemDetail = null
            detail = null
        }
    }
    BackHandler(enabled = current != null) { popBack() }

    if (statusOpen) {
        DriverStatusDialog(onDismiss = { statusOpen = false })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (current != null) {
                val title = if (current == Detail.System && sysDetail != null) {
                    sysDetail.label
                } else {
                    current.label
                }
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() }
                        )
                    },
                    navigationIcon = {
                        TextButton(
                            onClick = { popBack() },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Back")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "VipJam",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { heading() }
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PowerDot(on = driverDone && driverOk)
                                Text(
                                    "${routeTitle(profile)} · $driverText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (driverDone && !driverOk) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                val order = VipJamPrefs.Profiles.ALL
                                persistProfile(order[(order.indexOf(profile) + 1) % order.size])
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Devices")
                        }
                        TextButton(
                            onClick = { statusOpen = true },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Status")
                        }
                        TextButton(
                            onClick = {
                                systemDetail = null
                                detail = Detail.Presets
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Presets")
                        }
                        TextButton(
                            onClick = {
                                systemDetail = null
                                detail = Detail.System
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Settings")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (current == null) {
                FloatingActionButton(
                    onClick = { persistMaster(!masterOn) },
                    containerColor = if (masterOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (masterOn) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    PowerDot(on = masterOn)
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                when (current) {
                    null -> HomeTab(
                        store = store,
                        snackbar = snackbar,
                        masterOn = masterOn,
                        profile = profile,
                        driverText = driverText,
                        driverOk = driverDone && driverOk,
                        activeName = activeName,
                        onToggleMaster = { persistMaster(!masterOn) },
                        onSelectProfile = { persistProfile(it) },
                        onOpenPresets = {
                            systemDetail = null
                            detail = Detail.Presets
                        },
                        onOpenModule = {
                            detail = Detail.System
                            systemDetail = SystemDetail.Module
                        }
                    )
                    Detail.Presets -> PresetsTab(store, snackbar)
                    Detail.Lab -> LabScreen(
                        snackbar = snackbar,
                        tool = labTool,
                        onToolChange = { labTool = it }
                    )
                    Detail.System -> SystemScreen(
                        store = store,
                        snackbar = snackbar,
                        detail = sysDetail,
                        onDetailChange = { systemDetail = it },
                        onOpenLab = {
                            systemDetail = null
                            detail = Detail.Lab
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LabScreen(
    snackbar: SnackbarHostState,
    tool: LabTool,
    onToolChange: (LabTool) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            LabTool.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = tool == entry,
                    onClick = { onToolChange(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, LabTool.entries.size),
                    label = { Text(entry.label) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (tool) {
                LabTool.TestTone -> TestToneTab(snackbar)
                LabTool.LiveProg -> LiveProgTab(snackbar)
                LabTool.AutoEq -> AutoEqTab(snackbar)
                LabTool.Ddc -> DdcTab(snackbar)
            }
        }
    }
}

@Composable
private fun SystemScreen(
    store: PresetStore,
    snackbar: SnackbarHostState,
    detail: SystemDetail?,
    onDetailChange: (SystemDetail?) -> Unit,
    onOpenLab: () -> Unit
) {
    if (detail == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SystemDetail.entries.forEach { entry ->
                ListItem(
                    headlineContent = { Text(entry.label) },
                    supportingContent = { Text(entry.blurb) },
                    trailingContent = { Text(">") },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onDetailChange(entry) }
                )
                HorizontalDivider()
            }
            ListItem(
                headlineContent = { Text("Lab tools") },
                supportingContent = { Text("Tone, LiveProg, AutoEq and DDC") },
                trailingContent = { Text(">") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onOpenLab() }
            )
            HorizontalDivider()
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                when (detail) {
                    SystemDetail.Apps -> AppProfilesTab(snackbar)
                    SystemDetail.Module -> ModuleTab(snackbar)
                    SystemDetail.Status -> StatusTab(store, snackbar)
                    SystemDetail.Logs -> LogsDetail()
                    SystemDetail.About -> AboutDetail()
                }
            }
        }
    }
}

@Composable
private fun LogsDetail() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var filter by rememberSaveable { mutableStateOf(0) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var seq by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(seq) {
        lines = withContext(Dispatchers.IO) {
            VipJamLog.init(context.cacheDir)
            VipJamLog.readLast(500)
        }
    }
    val shown = remember(lines, filter) {
        when (filter) {
            1 -> lines.filter { it.contains(" W/") || it.contains(" E/") }
            2 -> lines.filter { it.contains(" E/") }
            else -> lines
        }
    }
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VipJam log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share log"))
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "Log", subtitle = "vipjam.log in cacheDir") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("All", "Warn", "Error").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = filter == index,
                        onClick = { filter = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                        label = { Text(label) }
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(shown.joinToString("\n")))
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { VipJamLog.clear() }
                        seq++
                    }
                }) { Text("Clear") }
                Button(onClick = { share(shown.joinToString("\n")) }) { Text("Share") }
            }
        }
        if (shown.isEmpty()) {
            EmptyState(title = "No log lines", body = "Use the app and return here; warnings and errors appear first under filters.")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(shown) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutDetail() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReleaseInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "VipJam") {
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Preset schema v${VipJamEffects.SCHEMA_VERSION}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard(title = "Updates") {
            if (checking) {
                LoadingState("Checking for updates")
            } else {
                Button(
                    onClick = {
                        checking = true
                        result = null
                        error = null
                        scope.launch {
                            try {
                                result = ReleaseApi.latestRelease()
                            } catch (e: Exception) {
                                error = e.message ?: "check failed"
                            } finally {
                                checking = false
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Check for updates") }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            result?.let { info ->
                val newer = ReleaseApi.isNewer(info.tag, BuildConfig.VERSION_NAME)
                Text(
                    text = if (newer) {
                        "Update available: ${info.tag}"
                    } else {
                        "Up to date (${info.tag})"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        text = info.notes.take(2000),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
