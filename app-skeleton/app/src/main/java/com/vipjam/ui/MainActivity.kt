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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.preferencesDataStore
import com.vipjam.BuildConfig
import com.vipjam.data.PresetSeeder
import com.vipjam.data.PresetStore
import com.vipjam.effect.VipJamEffects
import com.vipjam.ui.components.DestinationGlyph
import com.vipjam.ui.components.SectionCard
import com.vipjam.ui.theme.VipJamTheme
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
    var top by rememberSaveable { mutableStateOf(TabPage.Home) }
    var systemDetail by remember { mutableStateOf<SystemDetail?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val store = remember { PresetStore(context.prefs) }

    LaunchedEffect(Unit) {
        scope.launch {
            val seeded = PresetSeeder.seedOnce(context, store, context.prefs)
            if (seeded > 0) snackbar.showSnackbar("Seeded $seeded built-in presets")
        }
    }

    val detail = systemDetail
    val headerTitle = if (top == TabPage.System && detail != null) {
        detail.label
    } else {
        top.label
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TopDestinations.forEach { tab ->
                    NavigationBarItem(
                        selected = top == tab,
                        onClick = {
                            systemDetail = null
                            top = tab
                        },
                        icon = {
                            DestinationGlyph(
                                destination = tab,
                                contentDescription = tab.label,
                                tint = if (top == tab) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        label = { Text(tab.label) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = headerTitle,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
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
                    when (top) {
                        TabPage.Home -> HomeTab(
                            store = store,
                            snackbar = snackbar,
                            onOpenPresets = { top = TabPage.Presets },
                            onOpenModule = {
                                top = TabPage.System
                                systemDetail = SystemDetail.Module
                            }
                        )
                        TabPage.Sound -> EffectsTab(store, snackbar)
                        TabPage.Presets -> PresetsTab(store, snackbar)
                        TabPage.Lab -> LabScreen(snackbar)
                        TabPage.System -> SystemScreen(
                            store = store,
                            snackbar = snackbar,
                            detail = systemDetail,
                            onDetailChange = { systemDetail = it }
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun LabScreen(snackbar: SnackbarHostState) {
    var tool by rememberSaveable { mutableStateOf(LabTool.TestTone) }
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
                    onClick = { tool = entry },
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
                        Modifier.clearAndSetSemantics {}.size(0.dp)
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
    onDetailChange: (SystemDetail?) -> Unit
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
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onDetailChange(null) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Back")
                }
                Text(
                    text = detail.label,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
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
    }
}
