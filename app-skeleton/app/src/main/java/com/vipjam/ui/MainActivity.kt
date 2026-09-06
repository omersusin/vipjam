package com.vipjam.ui

import android.content.Context
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
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.vipjam.BuildConfig
import com.vipjam.data.PresetEntry
import com.vipjam.data.PresetSeeder
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.dsp.PresetApplier
import com.vipjam.effect.VipJamEffects
import com.vipjam.service.VipJamService
import com.vipjam.ui.components.PopSwitch
import com.vipjam.ui.components.SectionCard
import com.vipjam.ui.theme.VipJamTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

internal enum class TabPage(val label: String) {
    Home("Home"),
    Sound("Sound"),
    Presets("Presets"),
    Lab("Lab"),
    System("System"),
    Effects("Effects"),
    TestTone("Test Tone"),
    LiveProg("LiveProg"),
    AutoEq("AutoEq"),
    AppProfiles("Apps"),
    Status("Status"),
    Module("Module")
}

internal val TopDestinations = listOf(
    TabPage.Home,
    TabPage.Sound,
    TabPage.Presets,
    TabPage.Lab,
    TabPage.System
)

internal enum class Detail(val label: String) {
    Presets("Presets"),
    Lab("Lab"),
    System("System")
}

internal enum class LabTool(val label: String) {
    TestTone("Tone"),
    LiveProg("LiveProg"),
    AutoEq("AutoEq")
}

internal enum class SystemDetail(val label: String, val blurb: String) {
    Apps("App profiles", "Per-app routing rules"),
    Module("Module installer", "Root installer and updates"),
    Status("Diagnostics", "Driver, runtime and chain"),
    About("About", "Version and schema")
}

@Composable
fun VipJamApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var detail by rememberSaveable { mutableStateOf<Detail?>(null) }
    var systemDetail by remember { mutableStateOf<SystemDetail?>(null) }
    var labTool by rememberSaveable { mutableStateOf(LabTool.TestTone) }
    var menuOpen by remember { mutableStateOf(false) }
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

    fun flattenEq() {
        scope.launch {
            val current = try {
                val prefsData = context.prefs.data.first()
                val all = store.entries.first()
                all.find { it.name == prefsData[VipJamPrefs.ACTIVE_PRESET] }
                    ?: all.firstOrNull()
            } catch (_: Exception) {
                null
            } ?: run {
                launch { snackbar.showSnackbar("No preset to flatten") }
                return@launch
            }
            val bands = parseEqBands(current.settingsJson) ?: run {
                launch { snackbar.showSnackbar("EQ is off") }
                return@launch
            }
            var json = current.settingsJson
            for (i in bands.indices) {
                json = runCatching {
                    PresetApplier.withGroupScalar(json, VipJamEffects.EQ, i.toString(), 0.0)
                }.getOrElse {
                    launch { snackbar.showSnackbar("Edit failed: ${it.message}") }
                    return@launch
                }
            }
            store.save(PresetEntry(current.name, json))
                .onSuccess { launch { snackbar.showSnackbar("EQ flattened") } }
                .onFailure { launch { snackbar.showSnackbar("Edit failed: ${it.message}") } }
            for (i in bands.indices) {
                VipJamService.dispatchParam(context, com.vipjam.dsp.VipJamDispatcher.F_EQ, i, 0, 0)
            }
        }
    }

    fun openLab(tool: LabTool) {
        menuOpen = false
        labTool = tool
        systemDetail = null
        detail = Detail.Lab
    }

    fun openSystem(entry: SystemDetail) {
        menuOpen = false
        detail = Detail.System
        systemDetail = entry
    }

    val current = detail
    val sysDetail = systemDetail
    BackHandler(enabled = current != null && !(current == Detail.System && sysDetail != null)) {
        systemDetail = null
        detail = null
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            systemDetail = null
                            detail = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Back")
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() }
                    )
                }
            }
        },
        bottomBar = {
            BottomAppBar {
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        systemDetail = null
                        detail = Detail.Presets
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = activeName ?: "Choose preset",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (masterOn) "On" else "Off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PopSwitch(checked = masterOn, onToggle = ::persistMaster)
                }
                Box {
                    TextButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("More")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Preset manager") },
                            onClick = {
                                menuOpen = false
                                systemDetail = null
                                detail = Detail.Presets
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Revert-to-flat") },
                            onClick = {
                                menuOpen = false
                                flattenEq()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Tone") },
                            onClick = { openLab(LabTool.TestTone) }
                        )
                        DropdownMenuItem(
                            text = { Text("LiveProg") },
                            onClick = { openLab(LabTool.LiveProg) }
                        )
                        DropdownMenuItem(
                            text = { Text("AutoEq") },
                            onClick = { openLab(LabTool.AutoEq) }
                        )
                        DropdownMenuItem(
                            text = { Text("App profiles") },
                            onClick = { openSystem(SystemDetail.Apps) }
                        )
                        DropdownMenuItem(
                            text = { Text("Module installer") },
                            onClick = { openSystem(SystemDetail.Module) }
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            onClick = { openSystem(SystemDetail.Status) }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = { openSystem(SystemDetail.About) }
                        )
                    }
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
            LabTool.entries.forEach { entry ->
                Box(
                    modifier = if (tool == entry) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.clearAndSetSemantics {}.heightIn(max = 1.dp)
                    }
                ) {
                    when (entry) {
                        LabTool.TestTone -> TestToneTab(snackbar)
                        LabTool.LiveProg -> LiveProgTab(snackbar)
                        LabTool.AutoEq -> AutoEqTab(snackbar)
                    }
                }
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
    BackHandler(enabled = detail != null) { onDetailChange(null) }
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
                    trailingContent = { Text("›") },
                    modifier = Modifier.clickable { onDetailChange(entry) }
                )
                HorizontalDivider()
            }
            ListItem(
                headlineContent = { Text("Lab tools") },
                supportingContent = { Text("Tone, LiveProg and AutoEq") },
                trailingContent = { Text("›") },
                modifier = Modifier.clickable { onOpenLab() }
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
                    SystemDetail.Status -> StatusTab(store)
                    SystemDetail.About -> AboutDetail()
                }
            }
        }
    }
}

@Composable
private fun AboutDetail() {
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
        Spacer(modifier = Modifier.weight(1f))
    }
}
