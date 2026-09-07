package com.vipjam.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vipjam.dsp.LevelBus
import kotlinx.coroutines.delay

@Composable
fun LiveOutputMeter(modifier: Modifier = Modifier) {
    val level by LevelBus.levels.collectAsState()
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(level) {
        nowMs = SystemClock.elapsedRealtime()
        if (level != null) {
            delay(LevelBus.STALE_TIMEOUT_MS + 100)
            nowMs = SystemClock.elapsedRealtime()
        }
    }
    val live = LevelBus.isLive(level, nowMs)
    val rms = if (live) level!!.rms else 0f
    val peak = if (live) level!!.peak else 0f
    val status = if (live) {
        "Live \u00b7 ${level!!.source} \u00b7 RMS ${"%.1f".format(LevelBus.rmsToDb(rms))} dB \u00b7 peak ${"%.1f".format(LevelBus.rmsToDb(peak))} dB"
    } else {
        "No signal"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Output meter: $status" },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "OUTPUT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { LevelBus.levelFraction(rms) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 8.dp),
        )
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!live) {
            Text(
                "Play a test tone to meter the real DSP path. Driver exposes on/off params only (no level params); mix capture would need RECORD_AUDIO plus a real session, so it is not used.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
