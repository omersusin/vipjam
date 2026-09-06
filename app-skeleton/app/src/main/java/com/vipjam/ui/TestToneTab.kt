package com.vipjam.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vipjam.dsp.VipJamNative
import com.vipjam.ui.components.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val RATE = 48000
private const val BLOCK = 2048

private class ToneSession {
    @Volatile var job: Job? = null
    @Volatile var track: AudioTrack? = null
}

@Composable
fun TestToneTab(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val session = remember { ToneSession() }
    var freq by remember { mutableStateOf(440f) }
    var gain by remember { mutableStateOf(20f) }
    var durationSec by remember { mutableStateOf(2f) }
    var playing by remember { mutableStateOf(false) }
    var liveRms by remember { mutableStateOf<Float?>(null) }
    var lastRms by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            session.job?.cancel()
            try {
                session.track?.stop()
            } catch (_: Exception) {
            }
            try {
                session.track?.release()
            } catch (_: Exception) {
            }
            session.track = null
        }
    }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun stop() {
        session.job?.cancel()
        try {
            session.track?.stop()
        } catch (_: Exception) {
        }
    }

    fun play(processed: Boolean) {
        if (playing) return
        playing = true
        liveRms = null
        val f = freq
        val g = gain / 100f
        val totalFrames = (RATE * durationSec).roundToInt().coerceAtLeast(RATE)
        session.job = scope.launch(Dispatchers.IO) {
            var handle = 0L
            var track: AudioTrack? = null
            var done = 0
            var acc = 0.0
            var accN = 0L
            var blocks = 0
            try {
                if (processed) {
                    handle = VipJamNative.create(RATE)
                    VipJamNative.setMaster(handle, true)
                }
                val minBuf = AudioTrack.getMinBufferSize(
                    RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, BLOCK * 8 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                session.track = track
                track.play()
                val buf = FloatArray(BLOCK * 2)
                var phase = 0L
                while (done < totalFrames && isActive) {
                    val n = minOf(BLOCK, totalFrames - done)
                    for (i in 0 until n) {
                        val v = (g * 0.5 * sin(2.0 * PI * f / RATE * (phase + i))).toFloat()
                        buf[i * 2] = v
                        buf[i * 2 + 1] = v
                    }
                    if (processed && handle != 0L) VipJamNative.process(handle, buf, buf, n)
                    var b = 0.0
                    for (i in 0 until n) {
                        val s = buf[i * 2].toDouble()
                        b += s * s
                    }
                    acc += b
                    accN += n
                    track.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    phase += n
                    done += n
                    blocks++
                    if (blocks % 10 == 0) {
                        val r = sqrt(acc / accN).toFloat()
                        withContext(Dispatchers.Main) { liveRms = r }
                    }
                }
                val rms = sqrt(acc / maxOf(accN, 1L)).toFloat()
                withContext(Dispatchers.Main) {
                    lastRms = "%.0f Hz %s, %.1fs: %.3f rms".format(
                        f, if (processed) "DSP" else "bypass", durationSec, rms,
                    )
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    lastRms = "Stopped at %.0f Hz (%s)".format(
                        f, if (processed) "DSP" else "bypass",
                    )
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message("Play failed: ${e.message}") }
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                try {
                    track?.release()
                } catch (_: Exception) {
                }
                if (handle != 0L) {
                    try {
                        VipJamNative.free(handle)
                    } catch (_: Exception) {
                    }
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    if (session.track === track) session.track = null
                    playing = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Test tone", style = MaterialTheme.typography.headlineLarge)
        Text("Frequency %.0f Hz".format(freq))
        Slider(
            value = freq,
            onValueChange = { freq = it },
            valueRange = 30f..4000f,
            enabled = !playing,
        )
        Text("Gain %.0f %%".format(gain))
        Slider(
            value = gain,
            onValueChange = { gain = it },
            valueRange = 0f..100f,
            enabled = !playing,
        )
        Text("Duration %.1fs".format(durationSec))
        Slider(
            value = durationSec,
            onValueChange = { durationSec = it },
            valueRange = 1f..10f,
            enabled = !playing,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { play(true) }, enabled = !playing) {
                Text("Play DSP")
            }
            OutlinedButton(onClick = { play(false) }, enabled = !playing) {
                Text("Play bypass")
            }
            OutlinedButton(onClick = ::stop, enabled = playing) {
                Text("Stop")
            }
        }
        if (playing) {
            Text(
                liveRms?.let { "Playing… live RMS %.3f".format(it) } ?: "Playing…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (lastRms != null) {
            Text(lastRms!!, style = MaterialTheme.typography.bodyMedium)
        } else {
            EmptyState(
                title = "No tone played yet",
                body = "Pick a frequency, gain and duration, then press Play.",
            )
        }
        Text(
            "Streams a sine through the real fused engine (DSP) or raw (bypass). Stop releases the AudioTrack immediately.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
