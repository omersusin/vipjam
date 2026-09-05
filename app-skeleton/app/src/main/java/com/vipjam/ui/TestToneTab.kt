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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vipjam.dsp.VipJamNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private const val RATE = 48000
private const val BLOCK = 2048

@Composable
fun TestToneTab(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var freq by remember { mutableStateOf(110f) }
    var bassOn by remember { mutableStateOf(true) }
    var bassGain by remember { mutableStateOf(300f) }
    var playing by remember { mutableStateOf(false) }
    var lastRms by remember { mutableStateOf<String?>(null) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun play(processed: Boolean) {
        if (playing) return
        playing = true
        scope.launch(Dispatchers.IO) {
            try {
                val rms = playTone(freq, bassOn && processed, bassGain)
                withContext(Dispatchers.Main) {
                    lastRms = "%.0f Hz %s: out %.3f rms".format(
                        freq, if (processed) "DSP" else "bypass", rms,
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message("Play failed: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { playing = false }
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
        Text("Sine %.0f Hz".format(freq))
        Slider(value = freq, onValueChange = { freq = it }, valueRange = 30f..4000f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ViPER bass", modifier = Modifier.weight(1f))
            Switch(checked = bassOn, onCheckedChange = { bassOn = it })
        }
        Text("Bass gain %.0f".format(bassGain))
        Slider(
            value = bassGain, onValueChange = { bassGain = it },
            valueRange = 50f..1000f, enabled = bassOn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { play(true) }, enabled = !playing) {
                Text("Play DSP")
            }
            OutlinedButton(onClick = { play(false) }, enabled = !playing) {
                Text("Play bypass")
            }
        }
        lastRms?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Plays 2s through the real fused engine. Compare DSP vs bypass RMS.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun playTone(freqHz: Float, dsp: Boolean, bassGain: Float): Float {
    val frames = RATE * 2
    val input = FloatArray(frames * 2)
    for (i in 0 until frames) {
        val v = (0.2 * sin(2.0 * PI * freqHz / RATE * i)).toFloat()
        input[i * 2] = v
        input[i * 2 + 1] = v
    }
    val output = input.clone()
    if (dsp) {
        val h = VipJamNative.create(RATE)
        try {
            VipJamNative.setMaster(h, true)
            VipJamNative.setParam(h, VipJamNative.BASS, bassGain / 100f, 0f, 0f)
            val tmp = FloatArray(BLOCK * 2)
            var off = 0
            while (off < frames) {
                val n = minOf(BLOCK, frames - off)
                System.arraycopy(input, off * 2, tmp, 0, n * 2)
                VipJamNative.process(h, tmp, tmp, n)
                System.arraycopy(tmp, 0, output, off * 2, n * 2)
                off += n
            }
        } finally {
            VipJamNative.free(h)
        }
    }
    var acc = 0.0
    for (i in frames / 2 until frames) acc += output[i * 2] * output[i * 2]
    val rms = sqrt(acc / (frames / 2)).toFloat()
    val track = AudioTrack.Builder()
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
        .setBufferSizeInBytes(output.size * 4)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
    track.play()
    while (track.playbackHeadPosition < frames) Thread.sleep(50)
    track.stop()
    track.release()
    return rms
}
