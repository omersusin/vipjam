package com.vipjam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vipjam.data.DeviceRule
import com.vipjam.data.DeviceRules
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.appprofile.AppProfileMonitor
import com.vipjam.appprofile.AppProfileStore
import com.vipjam.dsp.PresetApplier
import com.vipjam.dsp.VipJamCommand
import com.vipjam.dsp.VipJamCommandParser
import com.vipjam.dsp.VipJamDispatcher
import com.vipjam.ui.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VipJamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatcher = VipJamDispatcher(0)
    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var appProfileMonitor: AppProfileMonitor? = null
    private var commandObserver: ContentObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            commandObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) {
        }
        commandObserver = null
        try {
            appProfileMonitor?.stop()
        } catch (_: Exception) {
        }
        appProfileMonitor = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
            } catch (_: Exception) {
            }
        }
        deviceCallback = null
        audioManager = null
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
        registerDeviceCallback()
        registerCommandObserver()
    }

    private fun registerCommandObserver() {
        val uri = Settings.Global.getUriFor(CMD_KEY)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { drainCommand() }
            }
        }
        commandObserver = observer
        try {
            contentResolver.registerContentObserver(uri, false, observer)
        } catch (_: Exception) {
            commandObserver = null
        }
        scope.launch { drainCommand() }
    }

    private suspend fun drainCommand() {
        val resolver = contentResolver
        val seq = runCatching {
            Settings.Global.getString(resolver, CMD_SEQ_KEY)?.toInt()
        }.getOrNull() ?: return
        val store = PresetStore(applicationContext.prefs)
        val prefs = applicationContext.prefs
        val last = prefs.data.first()[VipJamPrefs.CMD_SEQ] ?: -1
        if (seq <= last) return
        val text = runCatching {
            Settings.Global.getString(resolver, CMD_KEY)
        }.getOrNull()
        prefs.edit { it[VipJamPrefs.CMD_SEQ] = seq }
        when (val cmd = VipJamCommandParser.parse(text)) {
            is VipJamCommand.ToggleMaster -> {
                val cur = prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
                prefs.edit { it[VipJamPrefs.MASTER_ENABLE] = !cur }
                applyMaster(!cur)
            }
            is VipJamCommand.SetProfile -> {
                prefs.edit { it[VipJamPrefs.ACTIVE_PROFILE] = cmd.route }
                applyProfile(cmd.route)
            }
            is VipJamCommand.SetParam ->
                dispatchParamNow(cmd.id, cmd.v0, cmd.v1, cmd.v2)
            is VipJamCommand.ApplyPreset -> {
                val master = prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
                store.importText(cmd.settingsJson)
                applyPreset(cmd.settingsJson, master)
            }
            null -> Unit
        }
    }

    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                onOutputDevicesAdded(addedDevices.toList())
            }
        }
        deviceCallback = callback
        manager.registerAudioDeviceCallback(callback, null)
    }

    private fun onOutputDevicesAdded(devices: List<AudioDeviceInfo>) {
        scope.launch {
            val app = applicationContext
            val store = PresetStore(app.prefs)
            val map = try {
                store.devicePresetMap.first()
            } catch (_: Exception) {
                return@launch
            }
            if (map.isEmpty()) return@launch
            val rules = map.map { (id, preset) -> DeviceRule(id, "", preset) }
            for (device in devices) {
                val id = deviceIdOf(device) ?: continue
                val presetName = DeviceRules.match(rules, id, routeOf(device)) ?: continue
                val json = try {
                    store.entries.first().find { it.name == presetName }?.settingsJson
                } catch (_: Exception) {
                    null
                }
                if (json.isNullOrBlank()) continue
                val master = try {
                    app.prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
                } catch (_: Exception) {
                    false
                }
                applyPreset(json, master)
                return@launch
            }
        }
    }

    private fun deviceIdOf(device: AudioDeviceInfo): String? {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> device.address?.takeIf { it.isNotBlank() }
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> DeviceRules.WIRED_DEVICE_ID
            else -> null
        }
    }

    private fun routeOf(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> VipJamPrefs.Profiles.BLUETOOTH
            else -> VipJamPrefs.Profiles.HEADSET
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
            ACTION_DISPATCH_PARAM -> {
                val id = intent.getIntExtra(EXTRA_PARAM_ID, 0)
                val v0 = intent.getIntExtra(EXTRA_PARAM_V0, 0)
                val v1 = intent.getIntExtra(EXTRA_PARAM_V1, 0)
                val v2 = intent.getIntExtra(EXTRA_PARAM_V2, 0)
                scope.launch { dispatchParamNow(id, v0, v1, v2) }
            }
        }
        return START_STICKY
    }

    private fun applyMaster(on: Boolean) {
        scope.launch {
            if (!dispatcher.create()) return@launch
            dispatcher.setParam(VipJamDispatcher.P_MASTER, if (on) 1 else 0)
            dispatcher.enabled = on
            try {
                if (on) {
                    val app = applicationContext
                    val enabled = try {
                        AppProfileStore(app.prefs).monitorEnabled.first()
                    } catch (_: Exception) {
                        false
                    }
                    if (enabled) {
                        val monitor = appProfileMonitor
                            ?: AppProfileMonitor(AppProfileStore(app.prefs), PresetStore(app.prefs), app.prefs)
                                .also { appProfileMonitor = it }
                        monitor.start(app)
                    } else {
                        appProfileMonitor?.stop()
                    }
                } else {
                    appProfileMonitor?.stop()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun applyProfile(profile: String) {
        scope.launch {
            val prefs = applicationContext.prefs
            val store = PresetStore(applicationContext.prefs)
            val master = prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
            val linked = store.routePresetMap.first()[profile]
            val entries = store.entries.first()
            val target = linked?.let { name -> entries.find { it.name == name } }
                ?: entries.find { it.name == prefs.data.first()[VipJamPrefs.ACTIVE_PRESET] }
            if (target != null) {
                store.setRoutePreset(profile, target.name)
                applyPreset(target.settingsJson, master)
            }
        }
    }

    private fun dispatchParamNow(id: Int, v0: Int, v1: Int, v2: Int) {
        if (!dispatcher.create()) return
        if (id in SINGLE_INT_PARAMS) dispatcher.setParam(id, v0)
        else dispatcher.setParam(id, v0, v1, v2)
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
        const val CMD_KEY = "vipjam_cmd"
        const val CMD_SEQ_KEY = "vipjam_cmd_seq"
        const val ACTION_DISPATCH_PARAM = "com.vipjam.action.DISPATCH_PARAM"
        const val EXTRA_PARAM_ID = "param_id"
        const val EXTRA_PARAM_V0 = "param_v0"
        const val EXTRA_PARAM_V1 = "param_v1"
        const val EXTRA_PARAM_V2 = "param_v2"

        private val SINGLE_INT_PARAMS = setOf(
            VipJamDispatcher.P_MASTER,
            VipJamDispatcher.P_BASS_ENABLE,
            VipJamDispatcher.P_BASS_GAIN,
            VipJamDispatcher.P_CLARITY_ENABLE,
            VipJamDispatcher.P_EQ_ENABLE,
            VipJamDispatcher.P_REVERB_ENABLE,
            VipJamDispatcher.P_CONV_ENABLE,
        )

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

        fun dispatchParam(context: Context, id: Int, v0: Int, v1: Int = 0, v2: Int = 0) {
            val intent = Intent(context, VipJamService::class.java).apply {
                action = ACTION_DISPATCH_PARAM
                putExtra(EXTRA_PARAM_ID, id)
                putExtra(EXTRA_PARAM_V0, v0)
                putExtra(EXTRA_PARAM_V1, v1)
                putExtra(EXTRA_PARAM_V2, v2)
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
