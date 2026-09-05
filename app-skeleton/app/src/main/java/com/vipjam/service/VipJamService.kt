package com.vipjam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vipjam.dsp.PresetApplier
import com.vipjam.dsp.VipJamDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VipJamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatcher = VipJamDispatcher(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        dispatcher.release()
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_MASTER -> {
                val on = intent.getBooleanExtra(EXTRA_MASTER_ENABLED, true)
                applyMaster(on)
            }
            ACTION_SET_PROFILE -> {
                val profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                applyProfile(profile)
            }
            ACTION_APPLY_PRESET -> {
                val json = intent.getStringExtra(EXTRA_PRESET_JSON).orEmpty()
                val master = intent.getBooleanExtra(EXTRA_MASTER_ENABLED, true)
                scope.launch { applyPreset(json, master) }
            }
        }
        return START_STICKY
    }

    private fun applyMaster(on: Boolean) {
        scope.launch {
            if (!dispatcher.create()) return@launch
            dispatcher.setParam(VipJamDispatcher.P_MASTER, if (on) 1 else 0)
            dispatcher.enabled = on
        }
    }

    private fun applyProfile(profile: String) {
    }

    private fun applyPreset(settingsJson: String, masterOn: Boolean) {
        if (settingsJson.isBlank()) return
        if (!dispatcher.create()) return
        PresetApplier.apply(dispatcher, settingsJson, masterOn)
        dispatcher.enabled = masterOn
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VipJam",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VipJam")
            .setContentText("Audio engine idle")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "vipjam_service"
        const val ACTION_START = "com.vipjam.action.START"
        const val ACTION_STOP = "com.vipjam.action.STOP"
        const val ACTION_TOGGLE_MASTER = "com.vipjam.action.TOGGLE_MASTER"
        const val ACTION_SET_PROFILE = "com.vipjam.action.SET_PROFILE"
        const val ACTION_APPLY_PRESET = "com.vipjam.action.APPLY_PRESET"
        const val EXTRA_MASTER_ENABLED = "master_enabled"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_PRESET_JSON = "preset_json"

        fun start(context: Context, masterOn: Boolean) {
            val intent = Intent(context, VipJamService::class.java).apply {
                action = if (masterOn) ACTION_TOGGLE_MASTER else ACTION_STOP
                putExtra(EXTRA_MASTER_ENABLED, masterOn)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun setProfile(context: Context, profile: String) {
            val intent = Intent(context, VipJamService::class.java).apply {
                action = ACTION_SET_PROFILE
                putExtra(EXTRA_PROFILE, profile)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun applyPreset(context: Context, settingsJson: String, masterOn: Boolean) {
            val intent = Intent(context, VipJamService::class.java).apply {
                action = ACTION_APPLY_PRESET
                putExtra(EXTRA_PRESET_JSON, settingsJson)
                putExtra(EXTRA_MASTER_ENABLED, masterOn)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
