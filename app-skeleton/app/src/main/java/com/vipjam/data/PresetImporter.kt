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
            if (key == "james") {
                val james = obj.getJSONObject("james")
                for (stage in james.keys()) {
                    require(stage in jamesStages) { "unknown james stage: $stage" }
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
            }
        }
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

    fun withGroupEnabled(settingsJson: String, group: String, on: Boolean): String {
        require(group in viperGroups) { "unknown group: $group" }
        val obj = JSONObject(settingsJson)
        obj.getJSONObject(group).put("enable", on)
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
