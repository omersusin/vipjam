package com.vipjam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.ui.components.EmptyState
import com.vipjam.ui.components.LoadingState
import com.vipjam.ui.components.PowerDot

@Composable
fun HomeTab(
    store: PresetStore,
    snackbar: SnackbarHostState,
    masterOn: Boolean,
    profile: String,
    driverText: String,
    driverOk: Boolean,
    activeName: String?,
    onToggleMaster: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onOpenPresets: () -> Unit,
    onOpenModule: () -> Unit
) {
    val entries by store.entries.collectAsState(initial = null)
    val list = entries
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (masterOn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PowerDot(on = masterOn)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "MASTER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (masterOn) "Processing" else "Bypassed",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Switch(
                            checked = masterOn,
                            onCheckedChange = { onToggleMaster() },
                            modifier = Modifier.semantics {
                                contentDescription = "Master power"
                            }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VipJamPrefs.Profiles.ALL.forEach { route ->
                            val selected = route == profile
                            TextButton(
                                onClick = { onSelectProfile(route) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription = "Output $route"
                                        role = Role.Tab
                                        selected = selected
                                    }
                            ) {
                                Text(
                                    route.replaceFirstChar { it.uppercase() },
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = if (selected) {
                                        MaterialTheme.typography.titleMedium
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        (if (driverOk) "DRIVER " else "DRIVER? ") + driverText.uppercase() +
                            " · " + (activeName ?: "NO PRESET").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            when {
                list == null -> LoadingState("Loading presets")
                list.isEmpty() -> EmptyState(
                    title = "No presets yet",
                    body = "Load a preset to start shaping sound.",
                    actionLabel = "Manage",
                    onAction = onOpenPresets
                )
                else -> HybridChainSection(store = store, snackbar = snackbar, staggerBase = 0)
            }
        }
    }
}
