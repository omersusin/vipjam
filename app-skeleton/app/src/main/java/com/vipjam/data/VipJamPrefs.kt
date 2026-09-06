package com.vipjam.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object VipJamPrefs {
    val MASTER_ENABLE = booleanPreferencesKey("master_enable")
    val AUTO_START = booleanPreferencesKey("auto_start")
    val GLOBAL_MODE = booleanPreferencesKey("global_mode")
    val DEBUG_MODE = booleanPreferencesKey("debug_mode")
    val V3_INITIALIZED = booleanPreferencesKey("v3_initialized")
    val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
    val ACTIVE_PRESET = stringPreferencesKey("active_preset")
    val CMD_SEQ = intPreferencesKey("cmd_seq")

    fun effectKey(effectKey: String, jsonKey: String): String =
        "${effectKey}_${jsonKey}"

    object Profiles {
        const val HEADSET = "headset"
        const val SPEAKER = "speaker"
        const val BLUETOOTH = "bluetooth"
        val ALL = listOf(HEADSET, SPEAKER, BLUETOOTH)
    }
}
