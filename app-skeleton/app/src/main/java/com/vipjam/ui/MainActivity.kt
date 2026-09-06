package com.vipjam.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.preferencesDataStore
import com.vipjam.data.PresetSeeder
import com.vipjam.data.PresetStore
import com.vipjam.ui.components.DestinationGlyph
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
    Effects("Effects"),
    Presets("Presets"),
    TestTone("Test Tone"),
    LiveProg("LiveProg"),
    AutoEq("AutoEq"),
    AppProfiles("Apps"),
    Status("Status"),
    Module("Module")
}

@Composable
fun VipJamApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(TabPage.Effects) }
    val snackbar = remember { SnackbarHostState() }
    val store = remember { PresetStore(context.prefs) }

    LaunchedEffect(Unit) {
        scope.launch {
            val seeded = PresetSeeder.seedOnce(context, store, context.prefs)
            if (seeded > 0) snackbar.showSnackbar("Seeded $seeded built-in presets")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TabPage.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = page == tab,
                        onClick = { page = tab },
                        icon = {
                            DestinationGlyph(
                                destination = tab,
                                contentDescription = tab.label,
                                tint = if (page == tab) {
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
                text = page.label,
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
                    when (page) {
                        TabPage.Effects -> EffectsTab(store, snackbar)
                        TabPage.Presets -> PresetsTab(store, snackbar)
                        TabPage.TestTone -> TestToneTab(snackbar)
                        TabPage.LiveProg -> LiveProgTab(snackbar)
                        TabPage.AutoEq -> AutoEqTab(snackbar)
                        TabPage.AppProfiles -> AppProfilesTab(snackbar)
                        TabPage.Status -> StatusTab(store)
                        TabPage.Module -> ModuleTab(snackbar)
                    }
                }
            }
        }
    }
}
