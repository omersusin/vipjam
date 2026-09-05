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
                val prefs = app.prefs.data.first()
                if (prefs[VipJamPrefs.MASTER_ENABLE] != true) return@launch
                val presetName = prefs[VipJamPrefs.ACTIVE_PRESET]
                if (presetName.isNullOrBlank()) {
                    VipJamService.start(app, true)
                    return@launch
                }
                val json = PresetStore(app.prefs).entries.first()
                    .find { it.name == presetName }?.settingsJson
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
