package com.vipjam.dsp

import android.util.Log
import com.vipjam.effect.VipJamEffects
import org.json.JSONObject
import kotlin.math.roundToInt

interface ParamSink {
    fun setParam(id: Int, v0: Int): Boolean
    fun setParam(id: Int, v0: Int, v1: Int): Boolean
    fun setParam(id: Int, v0: Int, v1: Int, v2: Int): Boolean
}

object PresetApplier {
    const val TAG = "PresetApplier"

    fun apply(sink: ParamSink, settingsJson: String, masterOn: Boolean): Boolean {
        val obj = runCatching { JSONObject(settingsJson) }.getOrElse {
            Log.w(TAG, "apply: invalid settingsJson, skipped", it)
            return false
        }
        var ok = runCatching {
            sink.setParam(VipJamDispatcher.P_MASTER, if (masterOn) 1 else 0)
        }.getOrDefault(false)
        ok = group(obj, VipJamEffects.BASS) { g ->
            val en = runCatching {
                sink.setParam(VipJamDispatcher.P_BASS_ENABLE, if (g.optBoolean("enable")) 1 else 0)
            }.getOrDefault(false)
            val gain = runCatching {
                sink.setParam(VipJamDispatcher.P_BASS_GAIN, g.optInt("gain", 50))
            }.getOrDefault(false)
            en and gain
        } and ok
        ok = group(obj, VipJamEffects.CLARITY) { g ->
            val en = runCatching {
                sink.setParam(VipJamDispatcher.P_CLARITY_ENABLE, if (g.optBoolean("enable")) 1 else 0)
            }.getOrDefault(false)
            val param = runCatching {
                sink.setParam(
                    VipJamDispatcher.F_CLARITY,
                    g.optInt("gain", 50),
                    g.optInt("mode", 0),
                )
            }.getOrDefault(false)
            en and param
        } and ok
        ok = group(obj, VipJamEffects.EQ) { g ->
            var r = runCatching {
                sink.setParam(VipJamDispatcher.P_EQ_ENABLE, if (g.optBoolean("enable")) 1 else 0)
            }.getOrDefault(false)
            val bands = runCatching { g.optJSONArray("bands") }.getOrNull()
            if (bands != null) {
                for (i in 0 until bands.length()) {
                    val v = runCatching { bands.optDouble(i).roundToInt() }.getOrDefault(0)
                    r = runCatching { sink.setParam(VipJamDispatcher.F_EQ, i, v) }.getOrDefault(false) and r
                }
            }
            r
        } and ok
        ok = group(obj, VipJamEffects.REVERB) { g ->
            val en = runCatching {
                sink.setParam(VipJamDispatcher.P_REVERB_ENABLE, if (g.optBoolean("enable")) 1 else 0)
            }.getOrDefault(false)
            val param = runCatching {
                sink.setParam(
                    VipJamDispatcher.F_REVERB,
                    g.optInt("roomSize", 0),
                    g.optInt("width", 0),
                    g.optInt("damp", 0),
                )
            }.getOrDefault(false)
            en and param
        } and ok
        ok = group(obj, VipJamEffects.CONVOLVER) { g ->
            runCatching {
                sink.setParam(VipJamDispatcher.P_CONV_ENABLE, if (g.optBoolean("enable")) 1 else 0)
            }.getOrDefault(false)
        } and ok
        ok = group(obj, VipJamEffects.MASTER_LIMITER) { g ->
            runCatching {
                sink.setParam(
                    VipJamDispatcher.F_LIMITER,
                    g.optInt("threshold", 100).coerceIn(0, 100),
                )
            }.getOrDefault(false)
        } and ok
        for ((name, pid) in listOf(
            VipJamEffects.PLAYBACK_GAIN to VipJamDispatcher.P_PGC_ENABLE,
            VipJamEffects.DDC to VipJamDispatcher.P_DDC_ENABLE,
            VipJamEffects.DYN_SYS to VipJamDispatcher.P_DYNSYS_ENABLE,
            VipJamEffects.TUBE to VipJamDispatcher.P_TUBE_ENABLE,
            VipJamEffects.CURE to VipJamDispatcher.P_CURE_ENABLE,
            VipJamEffects.ANALOGX to VipJamDispatcher.P_ANALOGX_ENABLE,
            VipJamEffects.FET to VipJamDispatcher.P_FET_ENABLE,
            VipJamEffects.FIELD to VipJamDispatcher.P_VHE_ENABLE,
            VipJamEffects.DIFF to VipJamDispatcher.P_DIFF_ENABLE,
            VipJamEffects.SPEAKER to VipJamDispatcher.P_SPK_ENABLE,
        )) {
            ok = group(obj, name) { g ->
                val en = runCatching {
                    sink.setParam(pid, if (g.optBoolean("enable")) 1 else 0)
                }.getOrDefault(false)
                val extra = if (name == VipJamEffects.TUBE) {
                    runCatching {
                        sink.setParam(
                            VipJamDispatcher.F_TUBE,
                            g.optInt("drive", 0).coerceIn(0, 100),
                        )
                    }.getOrDefault(false)
                } else if (name == VipJamEffects.CURE) {
                    runCatching {
                        sink.setParam(
                            VipJamDispatcher.F_XFEED,
                            g.optInt("crossfeedPreset", 0).coerceIn(0, 5),
                        )
                    }.getOrDefault(false)
                } else {
                    true
                }
                en and extra
            } and ok
        }
        skipUnmapped(obj)
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
            VipJamEffects.TUBE -> {
                require(field == "drive") { "unknown field: $group.$field" }
                obj.getJSONObject(group).put("drive", value.roundToInt().coerceIn(0, 100))
            }
            VipJamEffects.MASTER_LIMITER -> {
                require(field == "threshold") { "unknown field: $group.$field" }
                obj.getJSONObject(group).put("threshold", value.roundToInt().coerceIn(0, 100))
            }
            VipJamEffects.CURE -> {
                require(field == "crossfeedPreset") { "unknown field: $group.$field" }
                obj.getJSONObject(group).put("crossfeedPreset", value.roundToInt().coerceIn(0, 5))
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
        VipJamEffects.TUBE,
        VipJamEffects.MASTER_LIMITER,
        VipJamEffects.CURE,
    )

    private val REVERB_FIELDS = setOf("roomSize", "width", "damp")

    private inline fun group(
        obj: JSONObject,
        key: String,
        fn: (JSONObject) -> Boolean,
    ): Boolean {
        val g = runCatching { obj.optJSONObject(key) }.getOrNull() ?: return true
        return try {
            fn(g)
        } catch (e: Exception) {
            Log.w(TAG, "apply: group $key failed, skipped", e)
            false
        }
    }

    private fun skipUnmapped(obj: JSONObject) {
        val keys = runCatching { obj.keys().asSequence().toSet() }.getOrNull() ?: return
        for (key in keys) {
            if (key in META_KEYS) continue
            if (key in DISPATCHED_GROUPS) continue
            if (key in PASS_THROUGH_GROUPS) {
                Log.d(TAG, "apply: group $key enable-only, values queued for future driver params")
                continue
            }
            if (key == JAMES_KEY) {
                Log.d(TAG, "apply: james file-backed groups (ddc/convolver) pushed by service; scalars need LiveProg tab")
                continue
            }
            Log.w(TAG, "apply: unknown group skipped: $key")
        }
    }

    private const val JAMES_KEY = "james"

    private val META_KEYS = setOf("schemaVersion", "origin", "name", "masterEnable", "route")

    private val DISPATCHED_GROUPS = setOf(
        VipJamEffects.BASS,
        VipJamEffects.CLARITY,
        VipJamEffects.EQ,
        VipJamEffects.REVERB,
        VipJamEffects.CONVOLVER,
        VipJamEffects.MASTER_LIMITER,
    )

    private val PASS_THROUGH_GROUPS = setOf(
        VipJamEffects.PLAYBACK_GAIN,
        VipJamEffects.LUFS,
        VipJamEffects.FET,
        VipJamEffects.MBC,
        VipJamEffects.DDC,
        VipJamEffects.SPECTRUM,
        VipJamEffects.DYN_EQ,
        VipJamEffects.FIELD,
        VipJamEffects.DIFF,
        VipJamEffects.STEREO_IMG,
        VipJamEffects.HSURR,
        VipJamEffects.DYN_SYS,
        VipJamEffects.PSYCHO_BASS,
        VipJamEffects.BASS_MONO,
        VipJamEffects.CURE,
        VipJamEffects.TUBE,
        VipJamEffects.ANALOGX,
        VipJamEffects.SPEAKER,
        VipJamEffects.LOUDNESS,
        VipJamEffects.LIVEPROG,
    )
}
