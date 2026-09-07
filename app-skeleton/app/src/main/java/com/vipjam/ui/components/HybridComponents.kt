package com.vipjam.ui.components

import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vipjam.dsp.VipJamDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EffectGlyph(group: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = group }
    ) {
        val stroke = 2.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        when (group) {
            "equalizer" -> {
                listOf(6.dp.toPx(), 12.dp.toPx(), 18.dp.toPx()).forEach { y ->
                    drawLine(tint, Offset(3.dp.toPx(), y), Offset(21.dp.toPx(), y), stroke, StrokeCap.Round)
                    drawCircle(tint, 3.dp.toPx(), Offset(cx, y))
                }
            }
            "reverb" -> {
                drawCircle(tint, 8.dp.toPx(), center, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(tint, 3.dp.toPx(), Offset(cx, cy))
            }
            "bass", "psychoacousticBass", "bassMono" -> {
                drawLine(tint, Offset(4.dp.toPx(), 16.dp.toPx()), Offset(20.dp.toPx(), 16.dp.toPx()), stroke, StrokeCap.Round)
                drawLine(tint, Offset(6.dp.toPx(), 16.dp.toPx()), Offset(6.dp.toPx(), 8.dp.toPx()), stroke, StrokeCap.Round)
                drawLine(tint, Offset(12.dp.toPx(), 16.dp.toPx()), Offset(12.dp.toPx(), 5.dp.toPx()), stroke, StrokeCap.Round)
                drawLine(tint, Offset(18.dp.toPx(), 16.dp.toPx()), Offset(18.dp.toPx(), 10.dp.toPx()), stroke, StrokeCap.Round)
            }
            else -> {
                drawCircle(tint, 8.dp.toPx(), center, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(tint, 2.dp.toPx(), center)
            }
        }
    }
}

@Composable
fun HybridSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    unit: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    LaunchedEffect(value) { draft = value }
    Column(
        modifier = modifier.heightIn(min = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText(draft.coerceIn(valueRange.start, valueRange.endInclusive)),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled, role = Role.Button) { dialogOpen = true }
                    .semantics { contentDescription = "$label value" }
            )
        }
        Slider(
            value = draft.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = {
                draft = it
                onValueChange(it)
            },
            modifier = Modifier.semantics { contentDescription = label },
            enabled = enabled,
            valueRange = valueRange
        )
    }
    if (dialogOpen) {
        var text by remember(draft) { mutableStateOf(valueText(draft).filter { it.isDigit() || it == '.' || it == '-' }) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        label = { Text(if (unit.isBlank()) "Value" else "Value ($unit)") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = {
                            Text("Range ${valueRange.start} to ${valueRange.endInclusive}${if (unit.isBlank()) "" else " $unit"}")
                        }
                    )
                    if (error != null) {
                        Text(error ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = text.toFloatOrNull()
                        if (parsed == null) {
                            error = "Enter a number"
                            return@TextButton
                        }
                        val clamped = parsed.coerceIn(valueRange.start, valueRange.endInclusive)
                        onValueChange(clamped)
                        dialogOpen = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        )
    }
}

private data class DriverStatus(
    val installed: Boolean,
    val versionCode: Int?,
    val versionName: String?,
    val arch: String,
    val streaming: String,
    val samplingRate: String
)

@Composable
fun DriverStatusDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var status by remember {
        mutableStateOf(
            DriverStatus(false, null, null, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown", "probing", "probing")
        )
    }
    var probing by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(refreshTick) {
        status = withContext(Dispatchers.IO) {
            runCatching { runDriverStatus(context.getSystemService(AudioManager::class.java)) }
                .getOrNull() ?: status.copy(streaming = "probe failed")
        }
        probing = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Driver status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (probing && status.versionCode == null && !status.installed) {
                    Text("Probing driver", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatRow(label = "Installed", value = if (status.installed) "yes" else "no")
                StatRow(label = "Version code", value = status.versionCode?.toString() ?: "unknown")
                StatRow(label = "Version name", value = status.versionName ?: "unknown")
                StatRow(label = "Arch", value = status.arch)
                StatRow(label = "Streaming", value = status.streaming)
                StatRow(label = "Sampling rate", value = status.samplingRate)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { probing = true; refreshTick++ },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Refresh") }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
        }
    )
}

private fun runDriverStatus(manager: AudioManager?): DriverStatus {
    val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    val sampling = runCatching { manager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }?.getOrNull() ?: "unknown"
    val frames = runCatching { manager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }?.getOrNull() ?: "unknown"
    val dispatcher = VipJamDispatcher(0)
    return try {
        if (!dispatcher.create()) {
            DriverStatus(false, null, null, arch, "unavailable", sampling ?: "unknown")
        } else {
            val version = dispatcher.getParam(VipJamDispatcher.GET_VERSION_CODE)
            val name = dispatcher.getStringParam(VipJamDispatcher.GET_VERSION_NAME)
            DriverStatus(true, version, name, arch, frames ?: "unknown", sampling ?: "unknown")
        }
    } finally {
        dispatcher.release()
    }
}
