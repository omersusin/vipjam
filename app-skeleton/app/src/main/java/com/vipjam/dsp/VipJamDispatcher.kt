package com.vipjam.dsp

import android.media.audiofx.AudioEffect
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class VipJamDispatcher(private val sessionId: Int) : ParamSink {
    private val lock = Any()
    private var effect: AudioEffect? = null

    fun create(): Boolean {
        synchronized(lock) {
            if (effect != null) return true
            return try {
                val ctor = AudioEffect::class.java.getConstructor(
                    UUID::class.java, UUID::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
                val fx = ctor.newInstance(EFFECT_TYPE, EFFECT_UUID, 0, sessionId)
                    as AudioEffect
                effect = fx
                true
            } catch (e: Exception) {
                Log.w(TAG, "AudioEffect unavailable (driver not installed?)", e)
                effect = null
                false
            }
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                effect?.release()
            } catch (e: Exception) {
                Log.w(TAG, "release failed", e)
            }
            effect = null
        }
    }

    var enabled: Boolean
        get() {
            val fx = synchronized(lock) { effect } ?: return false
            return try {
                fx.enabled
            } catch (e: Exception) {
                Log.w(TAG, "get enabled failed", e)
                false
            }
        }
        set(on) {
            val fx = synchronized(lock) { effect }
            if (fx == null) {
                Log.w(TAG, "set enabled ignored (effect not present)")
                return
            }
            try {
                fx.enabled = on
            } catch (e: Exception) {
                Log.w(TAG, "set enabled failed", e)
            }
        }

    override fun setParam(id: Int, v0: Int): Boolean =
        setBytes(intBytes(id), intBytes(v0))

    override fun setParam(id: Int, v0: Int, v1: Int): Boolean =
        setBytes(intBytes(id), intBytes(v0) + intBytes(v1))

    override fun setParam(id: Int, v0: Int, v1: Int, v2: Int): Boolean =
        setBytes(intBytes(id), intBytes(v0) + intBytes(v1) + intBytes(v2))

    fun sendRaw(cmdId: Int, payload: ByteArray): Boolean =
        setBytes(intBytes(cmdId), payload)

    fun sendFloatArray(cmdId: Int, values: FloatArray): Boolean {
        val payload = try {
            buildBulkParam(cmdId, values)
        } catch (e: Exception) {
            Log.w(TAG, "sendFloatArray rejected", e)
            return false
        }
        return setBytes(intBytes(cmdId), payload)
    }

    fun sendBulkChunks(chunkCmdId: Int, values: FloatArray, chunkSize: Int): Boolean {
        val chunks = try {
            chunkFloats(values, chunkSize)
        } catch (e: Exception) {
            Log.w(TAG, "sendBulkChunks rejected", e)
            return false
        }
        for ((index, chunk) in chunks.withIndex()) {
            if (!setBytes(intBytes(chunkCmdId), buildKernelChunk(index, chunk))) return false
        }
        return true
    }

    fun sendScript(script: String, scriptId: Int = 1): Boolean {
        val data = try {
            scriptBytes(script)
        } catch (e: Exception) {
            Log.w(TAG, "sendScript rejected", e)
            return false
        }
        if (data.isEmpty() || data.size > LIVEPROG_MAX_BYTES) {
            Log.w(TAG, "sendScript rejected: bad size ${data.size}")
            return false
        }
        val chunks = try {
            chunkScriptBytes(data, LIVEPROG_BYTES_PER_CHUNK)
        } catch (e: Exception) {
            Log.w(TAG, "sendScript rejected", e)
            return false
        }
        if (!setBytes(intBytes(LIVEPROG_ALLOC), buildScriptAlloc(data.size, LIVEPROG_BYTES_PER_CHUNK, scriptId))) return false
        for ((index, chunk) in chunks.withIndex()) {
            if (!setBytes(intBytes(LIVEPROG_CHUNK), buildScriptChunk(index, chunk))) return false
        }
        return setBytes(intBytes(LIVEPROG_COMMIT), buildScriptCommit(data.size, crc32IEEE(data), scriptId))
    }

    fun getParam(id: Int): Int? {
        val fx = synchronized(lock) { effect } ?: return null
        return try {
            val m = AudioEffect::class.java.getMethod(
                "getParameter", ByteArray::class.java, ByteArray::class.java,
            )
            val reply = ByteArray(64)
            val status = m.invoke(fx, intBytes(id), reply) as Int
            if (status < 0) return null
            val buf = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
            buf.int
            buf.position(buf.position() + 4)
            buf.int
        } catch (e: Exception) {
            Log.w(TAG, "getParameter failed", e)
            null
        }
    }

    fun getStringParam(id: Int): String? {
        val fx = synchronized(lock) { effect } ?: return null
        return try {
            val m = AudioEffect::class.java.getMethod(
                "getParameter", ByteArray::class.java, ByteArray::class.java,
            )
            val reply = ByteArray(256)
            val status = m.invoke(fx, intBytes(id), reply) as Int
            if (status < 0) return null
            val end = reply.indexOfFirst { it == 0.toByte() }.let { if (it < 0) reply.size else it }
            if (end == 0) return null
            String(reply, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "getParameter failed", e)
            null
        }
    }

    private fun setBytes(param: ByteArray, value: ByteArray): Boolean {
        val fx = synchronized(lock) { effect }
        if (fx == null) {
            Log.w(TAG, "setParameter ignored (effect not present)")
            return false
        }
        return try {
            val m = AudioEffect::class.java.getMethod(
                "setParameter", ByteArray::class.java, ByteArray::class.java,
            )
            (m.invoke(fx, param, value) as Int) == AudioEffect.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "setParameter failed", e)
            false
        }
    }

    companion object {
        const val TAG = "VipJamDispatcher"
        val EFFECT_TYPE: UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
        val EFFECT_UUID: UUID = UUID.fromString("1b222930-cde3-5b6f-81a4-f67b3334a73e")

        const val P_MASTER = 36868
        const val P_BASS_ENABLE = 65574
        const val P_BASS_GAIN = 65577
        const val P_CLARITY_ENABLE = 65578
        const val P_EQ_ENABLE = 65551
        const val P_REVERB_ENABLE = 65559
        const val P_CONV_ENABLE = 65538
        const val P_PGC_ENABLE = 65565
        const val P_DDC_ENABLE = 65546
        const val P_DYNSYS_ENABLE = 65569
        const val P_TUBE_ENABLE = 65583
        const val P_CURE_ENABLE = 65581
        const val P_ANALOGX_ENABLE = 65584
        const val P_FET_ENABLE = 65610
        const val P_VHE_ENABLE = 65544
        const val P_DIFF_ENABLE = 65557
        const val P_SPK_ENABLE = 65603

        const val F_BASS = 0x20040
        const val F_EQ = 0x20050
        const val F_REVERB = 0x20090
        const val F_CLARITY = 0x200B0
        const val F_TUBE = 0x200D0
        const val F_XFEED = 0x200C0
        const val F_LIMITER = 0x20010

        const val EQ_LEVELS_CLASSIC = 65552
        const val EQ_LEVELS_NEW = 0x101A3
        const val DDC_CLASSIC = 65547
        const val DDC_NEW = 0x101C1
        const val CONV_PREP_CLASSIC = 65540
        const val CONV_PREP_NEW = 0x101B2
        const val CONV_CHUNK_CLASSIC = 65541
        const val CONV_CHUNK_NEW = 0x101B3
        const val CONV_COMMIT_CLASSIC = 65542
        const val CONV_COMMIT_NEW = 0x101B4
        const val LIVEPROG_ALLOC = 8888
        const val LIVEPROG_CHUNK = 12001
        const val LIVEPROG_COMMIT = 10010
        const val LIVEPROG_CHUNK_BYTES = 8192
        const val LIVEPROG_BYTES_PER_CHUNK = 8184
        const val LIVEPROG_MAX_BYTES = 1048576
        const val KERNEL_CHUNK_BYTES = 8192
        const val KERNEL_MAX_FLOATS_PER_CHUNK = 2046
        const val KERNEL_MAX_TOTAL_FLOATS = 4194304
        const val EQ_MAX_BANDS = 31
        const val EQ_LEVELS_BYTES = 256
        const val DDC_SMALL_BYTES = 256
        const val DDC_LARGE_BYTES = 1024

        const val GET_ENABLED = 1
        const val GET_CONFIGURED = 2
        const val GET_VERSION_CODE = 6
        const val GET_VERSION_NAME = 7

        fun intBytes(v: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(v).array()

        fun encodeFloatArrayLE(values: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) buf.putFloat(v)
            return buf.array()
        }

        fun encodeFloatArrayBE(values: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN)
            for (v in values) buf.putFloat(v)
            return buf.array()
        }

        fun crc32IEEE(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = CRC_TABLE[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
            }
            return crc xor -1
        }

        fun buildEqLevelsPayload(levels: FloatArray): ByteArray {
            require(levels.isNotEmpty() && levels.size <= EQ_MAX_BANDS)
            val out = ByteBuffer.allocate(EQ_LEVELS_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(levels.size)
            for (v in levels) out.putFloat(v)
            return out.array()
        }

        fun buildDdcPayload(c44: FloatArray, c48: FloatArray): ByteArray {
            require(c44.isNotEmpty() && c44.size == c48.size)
            val per = c44.size
            val size = if (4 + per * 8 <= DDC_SMALL_BYTES) DDC_SMALL_BYTES else DDC_LARGE_BYTES
            require(4 + per * 8 <= size)
            val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(per)
            for (v in c44) out.putFloat(v)
            for (v in c48) out.putFloat(v)
            return out.array()
        }

        fun buildBulkParam(cmdId: Int, values: FloatArray): ByteArray {
            if (cmdId == EQ_LEVELS_CLASSIC || cmdId == EQ_LEVELS_NEW) {
                return buildEqLevelsPayload(values)
            }
            if (cmdId == DDC_CLASSIC || cmdId == DDC_NEW) {
                require(values.isNotEmpty() && values.size % 2 == 0)
                val per = values.size / 2
                return buildDdcPayload(values.sliceArray(0 until per), values.sliceArray(per until values.size))
            }
            throw IllegalArgumentException("unsupported bulk param: $cmdId")
        }

        fun buildScriptAlloc(totalBytes: Int, chunkSize: Int, scriptId: Int): ByteArray {
            require(totalBytes in 1..LIVEPROG_MAX_BYTES)
            require(chunkSize in 1..LIVEPROG_BYTES_PER_CHUNK)
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(totalBytes).putInt(chunkSize).putInt(scriptId).array()
        }

        fun buildScriptCommit(totalBytes: Int, crc32: Int, scriptId: Int): ByteArray {
            require(totalBytes in 1..LIVEPROG_MAX_BYTES)
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(totalBytes).putInt(crc32).putInt(scriptId).array()
        }

        fun buildScriptChunk(index: Int, data: ByteArray): ByteArray {
            require(index >= 0)
            require(data.isNotEmpty() && data.size <= LIVEPROG_BYTES_PER_CHUNK)
            val out = ByteBuffer.allocate(LIVEPROG_CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(index)
            out.putInt(data.size)
            out.put(data)
            return out.array()
        }

        fun scriptBytes(script: String): ByteArray =
            script.toByteArray(Charsets.UTF_8)

        fun chunkScriptBytes(data: ByteArray, chunkSize: Int): List<ByteArray> {
            require(chunkSize in 1..LIVEPROG_BYTES_PER_CHUNK)
            require(data.isNotEmpty() && data.size <= LIVEPROG_MAX_BYTES)
            val out = ArrayList<ByteArray>((data.size + chunkSize - 1) / chunkSize)
            var off = 0
            while (off < data.size) {
                val end = minOf(off + chunkSize, data.size)
                out.add(data.sliceArray(off until end))
                off = end
            }
            return out
        }

        fun buildKernelPrepare(totalFloats: Int, channels: Int, resetFlag: Int): ByteArray {
            require(totalFloats > 0 && totalFloats <= KERNEL_MAX_TOTAL_FLOATS)
            require(channels == 1 || channels == 2)
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(totalFloats).putInt(channels).putInt(resetFlag).array()
        }

        fun buildKernelCommit(totalFloats: Int, crc32: Int, kernelId: Int): ByteArray {
            require(totalFloats > 0 && totalFloats <= KERNEL_MAX_TOTAL_FLOATS)
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(totalFloats).putInt(crc32).putInt(kernelId).array()
        }

        fun buildKernelChunk(index: Int, chunk: FloatArray): ByteArray {
            require(index >= 0)
            require(chunk.isNotEmpty() && chunk.size <= KERNEL_MAX_FLOATS_PER_CHUNK)
            val out = ByteBuffer.allocate(KERNEL_CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(index)
            out.putInt(chunk.size)
            for (v in chunk) out.putFloat(v)
            return out.array()
        }

        fun chunkFloats(values: FloatArray, chunkSize: Int): List<FloatArray> {
            require(chunkSize in 1..KERNEL_MAX_FLOATS_PER_CHUNK)
            require(values.isNotEmpty() && values.size <= KERNEL_MAX_TOTAL_FLOATS)
            val out = ArrayList<FloatArray>((values.size + chunkSize - 1) / chunkSize)
            var off = 0
            while (off < values.size) {
                val end = minOf(off + chunkSize, values.size)
                out.add(values.sliceArray(off until end))
                off = end
            }
            return out
        }

        private val CRC_TABLE: IntArray = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 1 != 0) -306674912 xor (c ushr 1) else c ushr 1 }
            c
        }

        private operator fun ByteArray.plus(other: ByteArray): ByteArray {
            val out = ByteArray(size + other.size)
            copyInto(out)
            other.copyInto(out, size)
            return out
        }
    }
}
