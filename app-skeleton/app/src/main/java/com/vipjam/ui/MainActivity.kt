package com.vipjam.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.preferencesDataStore
import com.vipjam.data.PresetSeeder
import com.vipjam.data.PresetStore
import kotlinx.coroutines.launch

val Context.prefs by preferencesDataStore("vipjam_prefs")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    VipJamApp()
                }
            }
        }
    }
}

private enum class TabPage { Effects, Presets, TestTone, LiveProg, AutoEq, AppProfiles, Status }

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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            ScrollableTabRow(selectedTabIndex = page.ordinal, edgePadding = 0.dp) {
                TabPage.entries.forEach { tab ->
                    Tab(
                        selected = page == tab,
                        onClick = { page = tab },
                        text = { Text(tab.name) },
                    )
                }
            }
            when (page) {
                TabPage.Effects -> EffectsTab(store, snackbar)
                TabPage.Presets -> PresetsTab(store, snackbar)
                TabPage.TestTone -> TestToneTab(snackbar)
                TabPage.LiveProg -> LiveProgTab(snackbar)
                TabPage.AutoEq -> AutoEqTab(snackbar)
                TabPage.AppProfiles -> AppProfilesTab(snackbar)
                TabPage.Status -> StatusTab(store)
            }
        }
    }
}
