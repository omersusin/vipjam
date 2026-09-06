package com.vipjam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.service.VipJamService
import com.vipjam.ui.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                val app = context.applicationContext
                val snap = app.prefs.data.first()
                if (snap[VipJamPrefs.MASTER_ENABLE] != true) return@launch
                val store = PresetStore(app.prefs)
                val profile = snap[VipJamPrefs.ACTIVE_PROFILE]
                val linked = if (!profile.isNullOrBlank() && profile in VipJamPrefs.Profiles.ALL) {
                    runCatching { store.routePresetMap.first()[profile] }.getOrNull()
                } else null
                val presetName = linked ?: snap[VipJamPrefs.ACTIVE_PRESET]
                if (presetName.isNullOrBlank()) {
                    VipJamService.start(app, true)
                    return@launch
                }
                val json = runCatching {
                    store.entries.first().find { it.name == presetName }?.settingsJson
                }.getOrNull()
                if (json.isNullOrBlank()) {
                    VipJamService.start(app, true)
                } else {
                    VipJamService.applyPreset(app, json, true)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
