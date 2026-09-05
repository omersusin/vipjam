package com.vipjam.data

import org.json.JSONArray
import org.json.JSONObject

data class DeviceRule(
    val deviceId: String,
    val route: String,
    val presetName: String,
)

object DeviceRules {
    const val WIRED_DEVICE_ID = "wired"
    const val SPEAKER_DEVICE_ID = "speaker"

    fun match(rules: List<DeviceRule>, deviceId: String, route: String): String? {
        val want = route.trim().lowercase()
        rules.firstOrNull {
            it.deviceId == deviceId && it.route.trim().lowercase() == want
        }?.presetName?.let { return it }
        return rules.firstOrNull {
            it.deviceId == deviceId && it.route.isBlank()
        }?.presetName
    }

    fun parseRules(json: String): List<DeviceRule> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid preset_rules.json", e)
        }
        val array = try {
            root.getJSONArray("rules")
        } catch (e: Exception) {
            throw IllegalArgumentException("preset_rules.json must contain a rules array", e)
        }
        return (0 until array.length()).map { index ->
            val obj = try {
                array.getJSONObject(index)
            } catch (e: Exception) {
                throw IllegalArgumentException("rule $index is not an object", e)
            }
            val deviceId = obj.optString("deviceId", "").trim()
            val preset = obj.optString("preset", "").trim()
            val route = if (obj.has("routeId")) {
                obj.optString("routeId", "")
            } else {
                obj.optString("route", "")
            }.trim().lowercase()
            if (deviceId.isBlank()) throw IllegalArgumentException("rule $index has no deviceId")
            if (preset.isBlank()) throw IllegalArgumentException("rule $index has no preset")
            DeviceRule(deviceId, route, preset)
        }
    }

    fun renderRules(rules: List<DeviceRule>): String {
        val array = JSONArray()
        for (rule in rules) {
            array.put(
                JSONObject()
                    .put("deviceName", rule.deviceId)
                    .put("deviceId", rule.deviceId)
                    .put("preset", rule.presetName)
                    .put("routeName", rule.route)
                    .put("routeId", rule.route),
            )
        }
        return JSONObject().put("rules", array).toString()
    }
}
