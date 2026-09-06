package com.vipjam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vipjam.ui.theme.VipJamTheme
import kotlinx.coroutines.delay

@Composable
fun DebouncedSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: (Float) -> String = { "%.1f".format(it) },
    debounceMs: Long = 120L,
    enabled: Boolean = true,
    steps: Int = 0
) {
    var draft by remember { mutableStateOf(value) }
    val latestChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        draft = value
    }
    LaunchedEffect(draft, debounceMs) {
        if (draft != value) {
            delay(debounceMs)
            latestChange(draft)
        }
    }
    val coerced = draft.coerceIn(valueRange.start, valueRange.endInclusive)
    Column(
        modifier = modifier.heightIn(min = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText(coerced),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = coerced,
            onValueChange = { draft = it },
            modifier = Modifier.semantics { this.contentDescription = label },
            enabled = enabled,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DebouncedSliderRowPreview() {
    VipJamTheme {
        var gain by remember { mutableStateOf(300f) }
        DebouncedSliderRow(
            label = "Bass gain",
            value = gain,
            onValueChange = { gain = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            valueRange = 50f..1000f,
            valueText = { "%.0f".format(it) }
        )
    }
}
