package com.vipjam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vipjam.data.VipJamPrefs
import com.vipjam.ui.components.RailCard
import com.vipjam.ui.components.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun ChainOrderCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsData by context.prefs.data.collectAsState(initial = null)
    val order = ChainOrder.sanitize(prefsData?.get(VipJamPrefs.CHAIN_DISPLAY_ORDER))

    fun persist(next: List<String>) {
        scope.launch {
            context.prefs.edit { it[VipJamPrefs.CHAIN_DISPLAY_ORDER] = ChainOrder.encode(next) }
        }
    }

    SectionHeader(title = "Chain order", subtitle = "Display order (${order.size})")
    RailCard(stateOn = false, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Display order only — the driver still plays a fixed order " +
                    "(James block, ViPER block, loudness, limiter last). " +
                    "Driver-side reorder is pending chain + protocol + app work.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            order.forEach { group ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        groupTitle(group) + if (group == ChainOrder.LIMITER_GROUP) " (pinned last)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { persist(ChainOrder.move(order, group, -1)) },
                        enabled = group != ChainOrder.LIMITER_GROUP && order.firstOrNull() != group,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Up") }
                    TextButton(
                        onClick = { persist(ChainOrder.move(order, group, 1)) },
                        enabled = group != ChainOrder.LIMITER_GROUP && order.getOrNull(order.size - 2) != group,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Down") }
                }
            }
            TextButton(
                onClick = { persist(ChainOrder.DEFAULT_DISPLAY_ORDER) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Reset order") }
        }
    }
}
