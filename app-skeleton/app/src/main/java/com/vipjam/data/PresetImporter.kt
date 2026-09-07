package com.vipjam.data

import com.vipjam.effect.VipJamEffects
import org.json.JSONObject
import java.util.Base64

data class ImportedPreset(
    val name: String,
    val origin: String,
    val masterEnable: Boolean,
    val settingsJson: String,
)

object PresetImporter {
    const val LINK_SCHEME = "vipjam://preset?c="

    // Field shapes, required keys and ranges mirror presets/preset.schema.json.
    // Sparse presets (any subset of groups) are accepted; dense bank form is
    // produced by tools/convert_universal.py. JSON key for loudness is
    // "loudnessComp" (VipJamEffects.LOUDNESS).

    private val viperGroups = setOf(
        VipJamEffects.MASTER_LIMITER,
        VipJamEffects.PLAYBACK_GAIN,
        VipJamEffects.LUFS,
        VipJamEffects.FET,
        VipJamEffects.MBC,
        VipJamEffects.DDC,
        VipJamEffects.SPECTRUM,
        VipJamEffects.EQ,
        VipJamEffects.DYN_EQ,
        VipJamEffects.CONVOLVER,
        VipJamEffects.FIELD,
        VipJamEffects.DIFF,
        VipJamEffects.STEREO_IMG,
        VipJamEffects.HSURR,
        VipJamEffects.REVERB,
        VipJamEffects.DYN_SYS,
        VipJamEffects.PSYCHO_BASS,
        VipJamEffects.BASS,
        VipJamEffects.BASS_MONO,
        VipJamEffects.CLARITY,
        VipJamEffects.CURE,
        VipJamEffects.TUBE,
        VipJamEffects.ANALOGX,
        VipJamEffects.SPEAKER,
        VipJamEffects.LOUDNESS,
        VipJamEffects.LIVEPROG,
    )

    private val jamesStages = setOf(
        "masterswitch", "compression", "bass", "tone", "streq", "convolver",
        "ddc", "liveprog", "tube", "stereowide", "bs2b", "headphone",
    )

    private val origins = setOf(
        VipJamEffects.PRESET_ORIGIN_VIPER,
        VipJamEffects.PRESET_ORIGIN_JAMES,
        VipJamEffects.PRESET_ORIGIN_VIPJAM,
    )

    fun parseV3(text: String): Result<ImportedPreset> = runCatching {
        val obj = JSONObject(text)
        require(obj.optInt("schemaVersion", -1) == VipJamEffects.SCHEMA_VERSION) {
            "schemaVersion must be ${VipJamEffects.SCHEMA_VERSION}"
        }
        val origin = obj.optString("origin", "")
        require(origin in origins) { "unknown origin: $origin" }
        val name = obj.optString("name", "")
        require(name.isNotBlank()) { "name must be non-empty" }
        val keys = obj.keys().asSequence().toSet()
        for (key in keys) {
            if (key in setOf("schemaVersion", "origin", "name", "masterEnable", "route")) continue
            if (key == "source" || key == "sourceName" || key == "createdAt") {
                if (!obj.isNull(key)) require(obj.opt(key) is String) { "$key must be a string" }
                continue
            }
            if (key == "sourcePreampDb" || key == "sourceFilters" ||
                key == "sourceGraphicPoints" || key == "sourceText"
            ) continue
            if (key.toIntOrNull() != null) continue
            if (key.startsWith("dsp.")) continue
            if (key == "james") {
                val james = obj.getJSONObject("james")
                for (stage in james.keys()) {
                    require(stage in jamesStages || stage.startsWith("dsp.")) {
                        "unknown james stage: $stage"
                    }
                }
                continue
            }
            require(key in viperGroups) { "unknown group: $key" }
        }
        obj.optJSONObject(VipJamEffects.EQ)?.let { eq ->
            val bands = eq.optJSONArray("bands")
            if (bands != null) {
                require(eq.optInt("bandCount", -1) == bands.length()) {
                    "equalizer bandCount != len(bands)"
                }
                for (i in 0 until bands.length()) {
                    val b = bands.optDouble(i, Double.NaN)
                    require(b.isFinite() && b in -12.0..12.0) {
                        "equalizer band $b out of range (-12,12)"
                    }
                }
            }
        }
        checkRanges(obj)
        ImportedPreset(
            name = name,
            origin = origin,
            masterEnable = obj.optBoolean("masterEnable", true),
            settingsJson = obj.toString(),
        )
    }

    fun groupEnables(settingsJson: String): List<Pair<String, Boolean>> {
        val obj = JSONObject(settingsJson)
        return viperGroups.sorted().mapNotNull { group ->
            obj.optJSONObject(group)?.let {
                if (it.has("enable")) group to it.optBoolean("enable", false)
                else null
            }
        }
    }

    private fun range(obj: JSONObject, group: String, field: String, lo: Double, hi: Double) {
        val g = obj.optJSONObject(group) ?: return
        if (g.isNull(field)) return
        val v = g.optDouble(field, Double.NaN)
        require(v.isFinite() && v in lo..hi) { "$group.$field $v out of range ($lo,$hi)" }
    }

    private fun checkRanges(obj: JSONObject) {
        range(obj, VipJamEffects.MASTER_LIMITER, "threshold", 30.0, 100.0)
        range(obj, VipJamEffects.MASTER_LIMITER, "outputVolume", 1.0, 200.0)
        range(obj, VipJamEffects.MASTER_LIMITER, "channelPan", -100.0, 100.0)
        range(obj, VipJamEffects.PLAYBACK_GAIN, "strength", 50.0, 300.0)
        range(obj, VipJamEffects.PLAYBACK_GAIN, "maxGain", 100.0, 1000.0)
        range(obj, VipJamEffects.PLAYBACK_GAIN, "outputThreshold", 30.0, 100.0)
        range(obj, VipJamEffects.FET, "threshold", -48.0, 0.0)
        range(obj, VipJamEffects.SPECTRUM, "strength", 2200.0, 8200.0)
        range(obj, VipJamEffects.SPECTRUM, "exciter", 0.0, 100.0)
        range(obj, VipJamEffects.CONVOLVER, "crossChannel", 0.0, 100.0)
        range(obj, VipJamEffects.FIELD, "widening", 0.0, 8.0)
        range(obj, VipJamEffects.FIELD, "midImage", 0.0, 10.0)
        range(obj, VipJamEffects.FIELD, "depth", 0.0, 10.0)
        range(obj, VipJamEffects.DIFF, "delay", 1.0, 20.0)
        range(obj, VipJamEffects.DIFF, "wetDryMix", 0.0, 100.0)
        range(obj, VipJamEffects.DIFF, "lpCutoff", 0.0, 20000.0)
        range(obj, VipJamEffects.REVERB, "roomSize", 0.0, 10.0)
        range(obj, VipJamEffects.REVERB, "width", 0.0, 10.0)
        range(obj, VipJamEffects.REVERB, "damp", 0.0, 10.0)
        range(obj, VipJamEffects.REVERB, "wet", 0.0, 100.0)
        range(obj, VipJamEffects.REVERB, "dry", 0.0, 100.0)
        range(obj, VipJamEffects.DYN_SYS, "strength", 0.0, 100.0)
        range(obj, VipJamEffects.DYN_SYS, "xLow", 0.0, 2400.0)
        range(obj, VipJamEffects.DYN_SYS, "xHigh", 0.0, 12000.0)
        range(obj, VipJamEffects.DYN_SYS, "yLow", 0.0, 200.0)
        range(obj, VipJamEffects.DYN_SYS, "yHigh", 0.0, 300.0)
        range(obj, VipJamEffects.DYN_SYS, "sideGainLow", 0.0, 100.0)
        range(obj, VipJamEffects.DYN_SYS, "sideGainHigh", 0.0, 100.0)
        range(obj, VipJamEffects.BASS, "frequency", 0.0, 135.0)
        range(obj, VipJamEffects.BASS, "gain", 50.0, 1000.0)
        range(obj, VipJamEffects.BASS_MONO, "frequency", 0.0, 135.0)
        range(obj, VipJamEffects.BASS_MONO, "gain", 50.0, 1000.0)
        range(obj, VipJamEffects.CLARITY, "gain", 0.0, 450.0)
        obj.optJSONObject(VipJamEffects.DDC)?.let { ddc ->
            if (ddc.optBoolean("enable", false) && ddc.optString("device", "").isEmpty()) {
                for (sr in arrayOf("sr44100", "sr48000")) {
                    val coeffs = ddc.optJSONArray(sr)
                    require(coeffs != null && coeffs.length() > 0) {
                        "ddc enabled but $sr missing/empty (need SR coeffs or a device .vdc ref)"
                    }
                    require(coeffs.length() % 5 == 0) { "ddc $sr length % 5 != 0" }
                }
            }
        }
    }

    fun withGroupEnabled(settingsJson: String, group: String, on: Boolean): String {
        require(group in viperGroups) { "unknown group: $group" }
        val obj = JSONObject(settingsJson)
        val g = obj.optJSONObject(group) ?: JSONObject().also { obj.put(group, it) }
        g.put("enable", on)
        return obj.toString()
    }

    fun packLink(settingsJson: String): String {
        val raw = settingsJson.toByteArray(Charsets.UTF_8)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return LINK_SCHEME + b64
    }

    fun unpackLink(link: String): Result<String> = runCatching {
        require(link.startsWith(LINK_SCHEME)) { "not a vipjam preset link" }
        val b64 = link.removePrefix(LINK_SCHEME)
        val raw = try {
            Base64.getUrlDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("link payload is not valid base64")
        }
        val text = String(raw, Charsets.UTF_8)
        parseV3(text).getOrThrow()
        text
    }
}
