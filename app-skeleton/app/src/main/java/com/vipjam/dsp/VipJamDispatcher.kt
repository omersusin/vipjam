package com.vipjam.dsp

import android.media.audiofx.AudioEffect
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class VipJamDispatcher(private val sessionId: Int) : ParamSink {
    private var effect: AudioEffect? = null

    fun create(): Boolean {
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
            false
        }
    }

    fun release() {
        try {
            effect?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release failed", e)
        }
        effect = null
    }

    var enabled: Boolean
        get() = try {
            effect?.enabled == true
        } catch (e: Exception) {
            false
        }
        set(on) {
            try {
                effect?.enabled = on
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

    fun getParam(id: Int): Int? {
        val fx = effect ?: return null
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

    private fun setBytes(param: ByteArray, value: ByteArray): Boolean {
        val fx = effect ?: return false
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

        const val F_BASS = 0x20040
        const val F_EQ = 0x20050
        const val F_REVERB = 0x20090
        const val F_CLARITY = 0x200B0

        const val GET_ENABLED = 1
        const val GET_CONFIGURED = 2
        const val GET_VERSION_CODE = 6
        const val GET_VERSION_NAME = 7

        fun intBytes(v: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(v).array()

        private operator fun ByteArray.plus(other: ByteArray): ByteArray {
            val out = ByteArray(size + other.size)
            copyInto(out)
            other.copyInto(out, size)
            return out
        }
    }
}
