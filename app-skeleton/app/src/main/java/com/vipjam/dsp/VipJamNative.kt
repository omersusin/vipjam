package com.vipjam.dsp

object VipJamNative {
    init {
        System.loadLibrary("vipjam_jni")
    }

    external fun create(sampleRate: Int): Long
    external fun free(handle: Long)
    external fun setRate(handle: Long, sampleRate: Int)
    external fun setMaster(handle: Long, on: Boolean)
    external fun setParam(handle: Long, id: Int, v0: Float, v1: Float, v2: Float): Int
    external fun process(handle: Long, input: FloatArray, output: FloatArray, frames: Int): Int
    external fun reset(handle: Long)

    const val MASTER_ENABLE = 0x20001
    const val LIMITER = 0x20010
    const val BASS = 0x20040
    const val EQ = 0x20050
    const val REVERB = 0x20090
    const val CLARITY = 0x200B0
    const val XFEED = 0x200C0
    const val TUBE = 0x200D0
}
