package com.vipjam.dsp

import com.vipjam.effect.VipJamEffects
import org.json.JSONObject
import kotlin.math.roundToInt

interface ParamSink {
    fun set(id: Int, v0: Int): Boolean
    fun set(id: Int, v0: Int, v1: Int): Boolean
    fun set(id: Int, v0: Int, v1: Int, v2: Int): Boolean
}

object PresetApplier {
    fun apply(sink: ParamSink, settingsJson: String, masterOn: Boolean): Boolean {
        val obj = JSONObject(settingsJson)
        var ok = sink.set(VipJamDispatcher.P_MASTER, if (masterOn) 1 else 0)
        ok = group(obj, VipJamEffects.BASS) { g ->
            sink.set(VipJamDispatcher.P_BASS_ENABLE, if (g.optBoolean("enable")) 1 else 0) &&
                sink.set(VipJamDispatcher.P_BASS_GAIN, g.optInt("gain", 50))
        } && ok
        ok = group(obj, VipJamEffects.CLARITY) { g ->
            sink.set(VipJamDispatcher.P_CLARITY_ENABLE, if (g.optBoolean("enable")) 1 else 0) &&
                sink.set(
                    VipJamDispatcher.F_CLARITY,
                    g.optInt("gain", 50),
                    g.optInt("mode", 0),
                )
        } && ok
        ok = group(obj, VipJamEffects.EQ) { g ->
            val bands = g.optJSONArray("bands")
            var r = sink.set(VipJamDispatcher.P_EQ_ENABLE, if (g.optBoolean("enable")) 1 else 0)
            if (bands != null) {
                for (i in 0 until bands.length()) {
                    r = sink.set(VipJamDispatcher.F_EQ, i, bands.optDouble(i).roundToInt()) && r
                }
            }
            r
        } && ok
        ok = group(obj, VipJamEffects.REVERB) { g ->
            sink.set(VipJamDispatcher.P_REVERB_ENABLE, if (g.optBoolean("enable")) 1 else 0) &&
                sink.set(
                    VipJamDispatcher.F_REVERB,
                    g.optInt("roomSize", 0),
                    g.optInt("width", 0),
                    g.optInt("damp", 0),
                )
        } && ok
        return ok
    }

    private inline fun group(
        obj: JSONObject,
        key: String,
        fn: (JSONObject) -> Boolean,
    ): Boolean {
        val g = obj.optJSONObject(key) ?: return true
        return fn(g)
    }
}
