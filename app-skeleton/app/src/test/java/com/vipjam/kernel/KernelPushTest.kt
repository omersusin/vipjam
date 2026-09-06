package com.vipjam.kernel

import com.vipjam.dsp.VipJamDispatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelPushTest {
    private fun vdcText(c44: List<Double>, c48: List<Double>): String {
        return "SR_44100:" + c44.joinToString(",") + "\nSR_48000:" + c48.joinToString(",") + "\n"
    }

    private fun wavBytes(
        format: Int,
        channels: Int,
        rate: Int,
        frames: List<List<Number>>,
        bits: Int,
    ): ByteArray {
        val bytesPerSample = bits / 8
        val dataSize = frames.size * channels * bytesPerSample
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(format.toShort())
        buf.putShort(channels.toShort())
        buf.putInt(rate)
        buf.putInt(rate * channels * bytesPerSample)
        buf.putShort((channels * bytesPerSample).toShort())
        buf.putShort(bits.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (frame in frames) {
            for (ch in 0 until channels) {
                if (format == 1) buf.putShort((frame[ch].toDouble() * 32767).toInt().toShort())
                else buf.putFloat(frame[ch].toFloat())
            }
        }
        return buf.array()
    }

    @Test
    fun `vdc text maps to ddc payload`() {
        val c44 = List(10) { (it + 1) * 0.5 }
        val c48 = List(10) { (it + 1) * 0.25 }
        val step = ddcStep(vdcText(c44, c48)).getOrThrow()
        assertEquals(VipJamDispatcher.DDC_NEW, step.id)
        assertEquals(20, step.values.size)
        for (i in c44.indices) {
            assertEquals(c44[i].toFloat(), step.values[i], 1e-6f)
            assertEquals(c48[i].toFloat(), step.values[i + 10], 1e-6f)
        }
        VipJamDispatcher.buildBulkParam(VipJamDispatcher.DDC_NEW, step.values)
    }

    @Test
    fun `parametric text converts to ddc payload`() {
        val text = "Preamp: -6.0 dB\n" +
            "Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.0\n" +
            "Filter 2: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.7\n"
        val step = ddcStep(text).getOrThrow()
        assertEquals(VipJamDispatcher.DDC_NEW, step.id)
        assertEquals(20, step.values.size)
    }

    @Test
    fun `wav 16-bit stereo parses and downmixes`() {
        val bytes = wavBytes(1, 2, 44100, listOf(listOf(0.5, -0.5), listOf(1.0, 0.0)), 16)
        val wav = parseWavBytes(bytes).getOrThrow()
        assertEquals(44100, wav.sampleRate)
        assertEquals(2, wav.channels)
        assertEquals(2, wav.samples.size)
        assertEquals(0.0f, wav.samples[0], 1e-3f)
        assertEquals(0.5f, wav.samples[1], 1e-3f)
    }

    @Test
    fun `wav 32-bit float mono parses`() {
        val bytes = wavBytes(3, 1, 48000, listOf(listOf(0.25), listOf(-0.75)), 32)
        val wav = parseWavBytes(bytes).getOrThrow()
        assertEquals(48000, wav.sampleRate)
        assertEquals(1, wav.channels)
        assertEquals(0.25f, wav.samples[0], 1e-6f)
        assertEquals(-0.75f, wav.samples[1], 1e-6f)
    }

    @Test
    fun `kernel plan sequences prep chunks commit`() {
        val samples = FloatArray(5000) { it * 0.001f }
        val plan = convPush(samples, 1, 44100, 48000, chunkSize = 2046).getOrThrow()
        assertEquals(5000, plan.totalFloats)
        assertEquals(1, plan.channels)
        assertTrue(plan.rateMismatch)
        assertEquals(1 + 3 + 1, plan.steps.size)
        assertEquals(VipJamDispatcher.CONV_PREP_NEW, plan.steps.first().id)
        assertEquals(VipJamDispatcher.CONV_COMMIT_NEW, plan.steps.last().id)
        assertEquals(5000, plan.steps.first().v0)
        assertEquals(1, plan.steps.first().v1)
        assertEquals(5000, plan.steps.last().v0)
        assertEquals(plan.crc32, plan.steps.last().v1)
        val chunks = plan.steps.subList(1, 4)
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertEquals(listOf(2046, 2046, 908), chunks.map { it.values.size })
        assertTrue(chunks.all { it.id == VipJamDispatcher.CONV_CHUNK_NEW })
    }

    @Test
    fun `kernel plan crc matches dispatcher`() {
        val samples = FloatArray(3000) { (it % 7) * 0.125f - 0.25f }
        val plan = convPush(samples, 2, 48000, 48000).getOrThrow()
        assertFalse(plan.rateMismatch)
        assertEquals(
            VipJamDispatcher.crc32IEEE(VipJamDispatcher.encodeFloatArrayLE(samples)),
            plan.crc32,
        )
    }

    @Test
    fun `error cases fail as results`() {
        assertTrue(ddcStep("").isFailure)
        assertTrue(ddcStep("not a vdc file").isFailure)
        assertTrue(ddcStep(vdcText(List(10) { 1.0 }, List(15) { 1.0 })).isFailure)
        assertTrue(parseWavBytes("garbage".toByteArray()).isFailure)
        assertTrue(parseWavBytes(ByteArray(0)).isFailure)
        assertTrue(parseIrsBytes(ByteArray(3)).isFailure)
        assertTrue(parseIrsBytes(ByteArray(0)).isFailure)
        assertTrue(convPush(FloatArray(0), 1, 48000, 48000).isFailure)
        assertTrue(convPush(FloatArray(8), 3, 48000, 48000).isFailure)
        assertTrue(convPush(FloatArray(8), 1, 48000, 48000, chunkSize = 0).isFailure)
        assertNull(kindForName("kernel.mp3"))
    }
}
