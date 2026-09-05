package com.vipjam.dsp

import com.vipjam.effect.VipJamEffects
import org.json.JSONObject
import kotlin.math.roundToInt

interface ParamSink {
    fun setParam(id: Int, v0: Int): Boolean
    fun setParam(id: Int, v0: Int, v1: Int): Boolean
    fun setParam(id: Int, v0: Int, v1: Int, v2: Int): Boolean
}

object PresetApplier {
    fun apply(sink: ParamSink, settingsJson: String, masterOn: Boolean): Boolean {
        val obj = JSONObject(settingsJson)
        var ok = sink.setParam(VipJamDispatcher.P_MASTER, if (masterOn) 1 else 0)
        ok = group(obj, VipJamEffects.BASS) { g ->
            sink.setParam(VipJamDispatcher.P_BASS_ENABLE, if (g.optBoolean("enable")) 1 else 0) &&
                sink.setParam(VipJamDispatcher.P_BASS_GAIN, g.optInt("gain", 50))
        } && ok
        ok = group(obj, VipJamEffects.CLARITY) { g ->
            sink.setParam(VipJamDispatcher.P_CLARITY_ENABLE, if (g.optBoolean("enable")) 1 else 0) &&
                sink.setParam(
                    VipJamDispatcher.F_CLARITY,
                    g.optInt("gain", 50),
                    g.optInt("mode", 0),
                )
        } && ok
        ok = group(obj, VipJamEffects.EQ) { g ->
            val bands = g.optJSONArray("bands")
            var r = sink.setParam(VipJamDispatcher.P_EQ_ENABLE, if (g.optBoolean("enable")) 1 else 0)
            if (bands != null) {
                for (i in 0 until bands.length()) {
                    r = sink.setParam(VipJamDispatcher.F_EQ, i, bands.optDouble(i).roundToInt()) && r
                }
            }
            r
        } && ok
        ok = group(obj, VipJamEffects.REVERB) { g ->
            sink.setParam(VipJamDispatcher.P_REVERB_ENABLE, if (g.optBoolean("enable")) 1 else 0) &&
                sink.setParam(
                    VipJamDispatcher.F_REVERB,
                    g.optInt("roomSize", 0),
                    g.optInt("width", 0),
                    g.optInt("damp", 0),
                )
        } && ok
        return ok
    }

    fun withGroupScalar(settingsJson: String, group: String, field: String, value: Double): String {
        require(group in SCALAR_GROUPS) { "unknown group: $group" }
        val obj = JSONObject(settingsJson)
        require(obj.has(group)) { "group absent: $group" }
        when (group) {
            VipJamEffects.BASS, VipJamEffects.CLARITY -> {
                require(field == "gain") { "unknown field: $group.$field" }
                obj.getJSONObject(group).put("gain", value.roundToInt())
            }
            VipJamEffects.REVERB -> {
                require(field in REVERB_FIELDS) { "unknown field: $group.$field" }
                obj.getJSONObject(group).put(field, value.roundToInt())
            }
            VipJamEffects.EQ -> {
                val g = obj.getJSONObject(group)
                require(g.has("bands")) { "group has no bands: $group" }
                val bands = g.getJSONArray("bands")
                val index = field.toIntOrNull()
                    ?: throw IllegalArgumentException("band index out of range: $field")
                require(index in 0 until bands.length()) {
                    "band index out of range: $field"
                }
                bands.put(index, value)
            }
        }
        return obj.toString()
    }

    private val SCALAR_GROUPS = setOf(
        VipJamEffects.BASS,
        VipJamEffects.CLARITY,
        VipJamEffects.REVERB,
        VipJamEffects.EQ,
    )

    private val REVERB_FIELDS = setOf("roomSize", "width", "damp")

    private inline fun group(
        obj: JSONObject,
        key: String,
        fn: (JSONObject) -> Boolean,
    ): Boolean {
        val g = obj.optJSONObject(key) ?: return true
        return fn(g)
    }
}
