package com.vipjam.dsp

import org.json.JSONObject

sealed interface VipJamCommand {
    data object ToggleMaster : VipJamCommand
    data class SetProfile(val route: String) : VipJamCommand
    data class SetParam(val id: Int, val v0: Int, val v1: Int, val v2: Int) : VipJamCommand
    data class ApplyPreset(val settingsJson: String) : VipJamCommand
}

object VipJamCommandParser {
    fun parse(text: String?): VipJamCommand? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (obj.optString("cmd", "")) {
            "toggle" -> VipJamCommand.ToggleMaster
            "profile" -> {
                val route = obj.optString("route", "")
                if (route.isBlank()) null else VipJamCommand.SetProfile(route)
            }
            "param" -> VipJamCommand.SetParam(
                obj.optInt("id", 0),
                obj.optInt("v0", 0),
                obj.optInt("v1", 0),
                obj.optInt("v2", 0),
            ).takeIf { obj.has("id") }
            "preset" -> {
                val b64 = obj.optString("b64", "")
                if (b64.isNotBlank()) {
                    val decoded = runCatching {
                        String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrNull()
                    if (decoded.isNullOrBlank()) null else VipJamCommand.ApplyPreset(decoded)
                } else {
                    val raw = obj.opt("json")
                    val json = when (raw) {
                        is JSONObject -> raw.toString()
                        else -> obj.optString("json", "")
                    }
                    if (json.isBlank()) null else VipJamCommand.ApplyPreset(json)
                }
            }
            else -> null
        }
    }
}
