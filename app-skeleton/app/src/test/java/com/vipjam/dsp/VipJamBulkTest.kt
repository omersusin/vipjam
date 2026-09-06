package com.vipjam.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VipJamBulkTest {
    private fun leInts(bytes: ByteArray, count: Int): IntArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(count) { buf.int }
    }

    private fun leFloats(bytes: ByteArray, offset: Int, count: Int): FloatArray {
        val buf = ByteBuffer.wrap(bytes, offset, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buf.float }
    }

    @Test
    fun `le encoding matches native memcpy layout`() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0x80.toByte(), 0x3F),
            VipJamDispatcher.encodeFloatArrayLE(floatArrayOf(1.0f)),
        )
    }

    @Test
    fun `be encoding is byte-swapped`() {
        assertArrayEquals(
            byteArrayOf(0x3F, 0x80.toByte(), 0, 0),
            VipJamDispatcher.encodeFloatArrayBE(floatArrayOf(1.0f)),
        )
    }

    @Test
    fun `eq payload matches native eq vector`() {
        val levels = FloatArray(10) { if (it % 2 == 0) -3.0f else 3.0f }
        val payload = VipJamDispatcher.buildEqLevelsPayload(levels)
        assertEquals(256, payload.size)
        assertEquals(10, leInts(payload, 1)[0])
        assertArrayEquals(levels, leFloats(payload, 4, 10), 0.0f)
        for (i in (4 + 10 * 4) until 256) assertEquals(0, payload[i])
    }

    @Test
    fun `eq payload rejects bad counts`() {
        try {
            VipJamDispatcher.buildEqLevelsPayload(FloatArray(0))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildEqLevelsPayload(FloatArray(32))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `ddc payload matches native ddc vector`() {
        val c44 = FloatArray(4) { 0.1f }
        val c48 = FloatArray(4) { 0.1f }
        val payload = VipJamDispatcher.buildDdcPayload(c44, c48)
        assertEquals(256, payload.size)
        assertEquals(4, leInts(payload, 1)[0])
        assertArrayEquals(c44, leFloats(payload, 4, 4), 0.0f)
        assertArrayEquals(c48, leFloats(payload, 20, 4), 0.0f)
    }

    @Test
    fun `ddc payload grows to 1024 for large per`() {
        val c = FloatArray(64) { 0.1f }
        assertEquals(1024, VipJamDispatcher.buildDdcPayload(c, c).size)
    }

    @Test
    fun `ddc payload rejects mismatch and oversize`() {
        try {
            VipJamDispatcher.buildDdcPayload(FloatArray(4), FloatArray(5))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildDdcPayload(FloatArray(200), FloatArray(200))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `crc32 matches standard vector`() {
        assertEquals(
            0xCBF43926.toInt(),
            VipJamDispatcher.crc32IEEE("123456789".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `kernel chunk matches native wire`() {
        val kern = FloatArray(32) { 1.0f / (it + 1) }
        val wire = VipJamDispatcher.buildKernelChunk(0, kern)
        assertEquals(8192, wire.size)
        val head = leInts(wire, 2)
        assertEquals(0, head[0])
        assertEquals(32, head[1])
        assertArrayEquals(kern, leFloats(wire, 8, 32), 0.0f)
        for (i in (8 + 32 * 4) until 8192) assertEquals(0, wire[i])
    }

    @Test
    fun `kernel chunk rejects bad index and size`() {
        try {
            VipJamDispatcher.buildKernelChunk(-1, FloatArray(4))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildKernelChunk(0, FloatArray(0))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildKernelChunk(0, FloatArray(2047))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `prepare and commit match native control order`() {
        assertArrayEquals(intArrayOf(64, 1, 0), leInts(VipJamDispatcher.buildKernelPrepare(64, 1, 0), 3))
        val crc = 0x12345678
        assertArrayEquals(intArrayOf(64, crc, 1234), leInts(VipJamDispatcher.buildKernelCommit(64, crc, 1234), 3))
    }

    @Test
    fun `chunking splits native 64-float kernel into two`() {
        val values = FloatArray(64) { it.toFloat() }
        val chunks = VipJamDispatcher.chunkFloats(values, 32)
        assertEquals(2, chunks.size)
        assertArrayEquals(values.sliceArray(0 until 32), chunks[0], 0.0f)
        assertArrayEquals(values.sliceArray(32 until 64), chunks[1], 0.0f)
    }

    @Test
    fun `chunking rejects bad size and empty input`() {
        try {
            VipJamDispatcher.chunkFloats(FloatArray(8), 0)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.chunkFloats(FloatArray(8), 2047)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.chunkFloats(FloatArray(0), 32)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `bulk param routes eq and ddc ids`() {
        val levels = FloatArray(10) { 1.0f }
        assertEquals(256, VipJamDispatcher.buildBulkParam(65552, levels).size)
        assertEquals(256, VipJamDispatcher.buildBulkParam(0x101A3, levels).size)
        val ddc = FloatArray(8) { 0.1f }
        assertEquals(256, VipJamDispatcher.buildBulkParam(65547, ddc).size)
        assertEquals(256, VipJamDispatcher.buildBulkParam(0x101C1, ddc).size)
    }

    @Test
    fun `bulk param rejects unknown id`() {
        try {
            VipJamDispatcher.buildBulkParam(7777777, FloatArray(8))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `senders return false without driver`() {
        val dispatcher = VipJamDispatcher(0)
        assertFalse(dispatcher.sendFloatArray(65552, FloatArray(10) { 1.0f }))
        assertFalse(dispatcher.sendBulkChunks(0x101B3, FloatArray(64) { 1.0f }, 32))
        assertFalse(dispatcher.sendFloatArray(7777777, FloatArray(8)))
        assertFalse(dispatcher.sendBulkChunks(0x101B3, FloatArray(0), 32))
    }

    @Test
    fun `wire id constants match native defines`() {
        assertEquals(65552, VipJamDispatcher.EQ_LEVELS_CLASSIC)
        assertEquals(0x101A3, VipJamDispatcher.EQ_LEVELS_NEW)
        assertEquals(65547, VipJamDispatcher.DDC_CLASSIC)
        assertEquals(0x101C1, VipJamDispatcher.DDC_NEW)
        assertEquals(65540, VipJamDispatcher.CONV_PREP_CLASSIC)
        assertEquals(0x101B2, VipJamDispatcher.CONV_PREP_NEW)
        assertEquals(65541, VipJamDispatcher.CONV_CHUNK_CLASSIC)
        assertEquals(0x101B3, VipJamDispatcher.CONV_CHUNK_NEW)
        assertEquals(65542, VipJamDispatcher.CONV_COMMIT_CLASSIC)
        assertEquals(0x101B4, VipJamDispatcher.CONV_COMMIT_NEW)
        assertEquals(8192, VipJamDispatcher.KERNEL_CHUNK_BYTES)
        assertEquals(2046, VipJamDispatcher.KERNEL_MAX_FLOATS_PER_CHUNK)
        assertTrue(VipJamDispatcher.EQ_MAX_BANDS == 31)
    }

    @Test
    fun `liveprog wire ids match native defines`() {
        assertEquals(8888, VipJamDispatcher.LIVEPROG_ALLOC)
        assertEquals(12001, VipJamDispatcher.LIVEPROG_CHUNK)
        assertEquals(10010, VipJamDispatcher.LIVEPROG_COMMIT)
        assertEquals(8192, VipJamDispatcher.LIVEPROG_CHUNK_BYTES)
        assertEquals(8184, VipJamDispatcher.LIVEPROG_BYTES_PER_CHUNK)
        assertEquals(1048576, VipJamDispatcher.LIVEPROG_MAX_BYTES)
    }

    @Test
    fun `script alloc and commit match native control order`() {
        assertArrayEquals(intArrayOf(128, 8184, 7), leInts(VipJamDispatcher.buildScriptAlloc(128, 8184, 7), 3))
        val crc = VipJamDispatcher.crc32IEEE("abc".toByteArray(Charsets.UTF_8))
        assertArrayEquals(intArrayOf(3, crc, 7), leInts(VipJamDispatcher.buildScriptCommit(3, crc, 7), 3))
    }

    @Test
    fun `script chunk matches native wire`() {
        val data = "spl0 = 1;".toByteArray(Charsets.UTF_8)
        val wire = VipJamDispatcher.buildScriptChunk(2, data)
        assertEquals(8192, wire.size)
        val head = leInts(wire, 2)
        assertEquals(2, head[0])
        assertEquals(data.size, head[1])
        assertArrayEquals(data, wire.sliceArray(8 until 8 + data.size))
        for (i in (8 + data.size) until 8192) assertEquals(0, wire[i])
    }

    @Test
    fun `script chunking splits across bytes per chunk`() {
        val data = ByteArray(8184 * 2 + 10) { it.toByte() }
        val chunks = VipJamDispatcher.chunkScriptBytes(data, 8184)
        assertEquals(3, chunks.size)
        assertEquals(8184, chunks[0].size)
        assertEquals(8184, chunks[1].size)
        assertEquals(10, chunks[2].size)
        assertArrayEquals(data.sliceArray(8184 * 2 until data.size), chunks[2])
    }

    @Test
    fun `script builders reject bad sizes`() {
        try {
            VipJamDispatcher.buildScriptAlloc(0, 8184, 1)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildScriptAlloc(3, 0, 1)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildScriptAlloc(1048577, 8184, 1)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildScriptAlloc(3, 8185, 1)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildScriptCommit(0, 1, 1)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildScriptChunk(-1, byteArrayOf(1))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildScriptChunk(0, ByteArray(0))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.buildScriptChunk(0, ByteArray(8185))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.chunkScriptBytes(ByteArray(0), 8184)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.chunkScriptBytes(byteArrayOf(1), 0)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VipJamDispatcher.chunkScriptBytes(byteArrayOf(1), 8185)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `script bytes round trip utf8 and crc stable`() {
        val text = "@init\ngain = 2.0;\n@sample\nspl0 = spl0 * gain;\n"
        val data = VipJamDispatcher.scriptBytes(text)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), data)
        assertEquals(VipJamDispatcher.crc32IEEE(data), VipJamDispatcher.crc32IEEE(data))
        assertTrue(VipJamDispatcher.crc32IEEE(data) != VipJamDispatcher.crc32IEEE(data + byteArrayOf(0)))
    }

    @Test
    fun `sendScript returns false without driver`() {
        val dispatcher = VipJamDispatcher(0)
        assertFalse(dispatcher.sendScript("@init\n@sample\n"))
        assertFalse(dispatcher.sendScript(""))
    }
}
