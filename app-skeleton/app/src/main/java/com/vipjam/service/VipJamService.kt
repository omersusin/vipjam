package com.vipjam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
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
import com.vipjam.root.RootShell
import com.vipjam.ui.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VipJamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatcher = VipJamDispatcher(0)
    private val driverLock = Any()
    private val commandMutex = Mutex()
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
        try {
            dispatcher.release()
        } catch (_: Exception) {
        }
        scope.cancel()
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
        mirrorRootProps()
    }

    private var rootMirrorOk: Boolean? = null

    private fun mirrorRootProps() {
        scope.launch {
            applicationContext.prefs.data.collect { snap ->
                val ok = rootMirrorOk ?: RootShell.hasSu().also { rootMirrorOk = it }
                if (!ok) return@collect
                val master = snap[VipJamPrefs.MASTER_ENABLE] ?: false
                val profile = snap[VipJamPrefs.ACTIVE_PROFILE].orEmpty()
                RootShell.capture("setprop persist.vipjam.master ${if (master) 1 else 0}", 5_000)
                if (profile.isNotBlank()) {
                    RootShell.capture("setprop persist.vipjam.profile $profile", 5_000)
                }
            }
        }
    }

    private fun registerCommandObserver() {
        try {
            val uri = Settings.Global.getUriFor(CMD_KEY)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch {
                        try {
                            drainCommand()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            commandObserver = observer
            try {
                contentResolver.registerContentObserver(uri, false, observer)
            } catch (e: Exception) {
                Log.w(TAG, "command observer register failed", e)
                commandObserver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "command observer setup failed", e)
            commandObserver = null
        }
        scope.launch {
            try {
                drainCommand()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun drainCommand() {
        val cmd = try {
            commandMutex.withLock {
                val resolver = contentResolver
                val seq = runCatching {
                    Settings.Global.getString(resolver, CMD_SEQ_KEY)?.toIntOrNull()
                }.getOrNull() ?: return@withLock null
                val prefs = applicationContext.prefs
                val last = try {
                    prefs.data.first()[VipJamPrefs.CMD_SEQ] ?: -1
                } catch (_: Exception) {
                    return@withLock null
                }
                if (seq <= last) return@withLock null
                val text = runCatching {
                    Settings.Global.getString(resolver, CMD_KEY)
                }.getOrNull()
                try {
                    prefs.edit { it[VipJamPrefs.CMD_SEQ] = seq }
                } catch (e: Exception) {
                    Log.w(TAG, "command seq claim failed", e)
                    return@withLock null
                }
                VipJamCommandParser.parse(text)
            }
        } catch (_: Exception) {
            return
        } ?: return
        try {
            when (cmd) {
                is VipJamCommand.ToggleMaster -> {
                    val prefs = applicationContext.prefs
                    val cur = try {
                        prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
                    } catch (e: Exception) {
                        Log.w(TAG, "toggle: prefs read failed", e)
                        return
                    }
                    try {
                        prefs.edit { it[VipJamPrefs.MASTER_ENABLE] = !cur }
                    } catch (e: Exception) {
                        Log.w(TAG, "toggle: prefs write failed", e)
                        return
                    }
                    applyMaster(!cur)
                }
                is VipJamCommand.SetProfile -> {
                    if (cmd.route !in VipJamPrefs.Profiles.ALL) {
                        Log.w(TAG, "profile command with unknown route skipped: ${cmd.route}")
                        return
                    }
                    try {
                        applicationContext.prefs.edit { it[VipJamPrefs.ACTIVE_PROFILE] = cmd.route }
                    } catch (e: Exception) {
                        Log.w(TAG, "profile command prefs write failed", e)
                        return
                    }
                    applyProfile(cmd.route)
                }
                is VipJamCommand.SetParam ->
                    dispatchParamNow(cmd.id, cmd.v0, cmd.v1, cmd.v2)
                is VipJamCommand.ApplyPreset -> {
                    val prefs = applicationContext.prefs
                    val master = try {
                        prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
                    } catch (_: Exception) {
                        false
                    }
                    val store = PresetStore(applicationContext.prefs)
                    val imported = try {
                        store.importText(cmd.settingsJson)
                    } catch (e: Exception) {
                        Log.w(TAG, "preset command import failed", e)
                        return
                    }
                    if (imported.isFailure) {
                        Log.w(TAG, "preset command import rejected")
                        return
                    }
                    applyPreset(cmd.settingsJson, master)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = try {
            getSystemService(AudioManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return
        audioManager = manager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                try {
                    onOutputDevicesAdded(addedDevices.toList())
                } catch (_: Exception) {
                }
            }
        }
        deviceCallback = callback
        try {
            manager.registerAudioDeviceCallback(callback, null)
        } catch (e: Exception) {
            Log.w(TAG, "device callback register failed", e)
            deviceCallback = null
            audioManager = null
        }
    }

    private fun onOutputDevicesAdded(devices: List<AudioDeviceInfo>) {
        scope.launch {
            try {
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
                    val id = try {
                        deviceIdOf(device)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    val route = try {
                        routeOf(device)
                    } catch (_: Exception) {
                        continue
                    }
                    val presetName = try {
                        DeviceRules.match(rules, id, route)
                    } catch (_: Exception) {
                        null
                    } ?: continue
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
            } catch (_: Exception) {
            }
        }
    }

    private fun deviceIdOf(device: AudioDeviceInfo): String? {
        return try {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> device.address?.takeIf { it.isNotBlank() }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> DeviceRules.WIRED_DEVICE_ID
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun routeOf(device: AudioDeviceInfo): String {
        return try {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> VipJamPrefs.Profiles.BLUETOOTH
                else -> VipJamPrefs.Profiles.HEADSET
            }
        } catch (_: Exception) {
            VipJamPrefs.Profiles.HEADSET
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
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
                    val profile = try {
                        intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    if (profile !in VipJamPrefs.Profiles.ALL) {
                        Log.w(TAG, "set profile with unknown route skipped: $profile")
                    } else {
                        applyProfile(profile)
                    }
                }
                ACTION_APPLY_PRESET -> {
                    val json = try {
                        intent.getStringExtra(EXTRA_PRESET_JSON).orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    val master = intent.getBooleanExtra(EXTRA_MASTER_ENABLED, true)
                    if (json.isBlank()) {
                        Log.w(TAG, "apply preset with empty json skipped")
                    } else {
                        scope.launch {
                            try {
                                applyPreset(json, master)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                ACTION_DISPATCH_PARAM -> {
                    val id = intent.getIntExtra(EXTRA_PARAM_ID, 0)
                    val v0 = intent.getIntExtra(EXTRA_PARAM_V0, 0)
                    val v1 = intent.getIntExtra(EXTRA_PARAM_V1, 0)
                    val v2 = intent.getIntExtra(EXTRA_PARAM_V2, 0)
                    scope.launch {
                        try {
                            dispatchParamNow(id, v0, v1, v2)
                        } catch (_: Exception) {
                        }
                    }
                }
                ACTION_DISPATCH_BULK -> {
                    val id = intent.getIntExtra(EXTRA_PARAM_ID, 0)
                    val values = try {
                        intent.getFloatArrayExtra(EXTRA_PARAM_VALUES)
                    } catch (_: Exception) {
                        null
                    } ?: floatArrayOf()
                    val v0 = intent.getIntExtra(EXTRA_PARAM_V0, 0)
                    val v1 = intent.getIntExtra(EXTRA_PARAM_V1, 0)
                    val v2 = intent.getIntExtra(EXTRA_PARAM_V2, 0)
                    scope.launch {
                        try {
                            dispatchBulkNow(id, values, v0, v1, v2)
                        } catch (_: Exception) {
                        }
                    }
                }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    private fun applyMaster(on: Boolean) {
        scope.launch {
            try {
                val created = synchronized(driverLock) {
                    if (!dispatcher.create()) false
                    else {
                        dispatcher.setParam(VipJamDispatcher.P_MASTER, if (on) 1 else 0)
                        dispatcher.enabled = on
                        true
                    }
                }
                if (!created) {
                    Log.w(TAG, "applyMaster: driver unavailable")
                    return@launch
                }
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
                        try {
                            monitor.start(app)
                        } catch (e: Exception) {
                            Log.w(TAG, "app profile monitor start failed", e)
                        }
                    } else {
                        try {
                            appProfileMonitor?.stop()
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    try {
                        appProfileMonitor?.stop()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun applyProfile(profile: String) {
        scope.launch {
            try {
                if (profile !in VipJamPrefs.Profiles.ALL) {
                    Log.w(TAG, "applyProfile with unknown route skipped: $profile")
                    return@launch
                }
                val app = applicationContext
                val prefs = app.prefs
                val store = PresetStore(prefs)
                val snap = try {
                    prefs.data.first()
                } catch (e: Exception) {
                    Log.w(TAG, "applyProfile: prefs read failed", e)
                    return@launch
                }
                val master = snap[VipJamPrefs.MASTER_ENABLE] ?: false
                val linked = try {
                    store.routePresetMap.first()[profile]
                } catch (e: Exception) {
                    Log.w(TAG, "applyProfile: route map read failed", e)
                    return@launch
                }
                val entries = try {
                    store.entries.first()
                } catch (e: Exception) {
                    Log.w(TAG, "applyProfile: entries read failed", e)
                    return@launch
                }
                if (linked != null) {
                    val target = entries.find { it.name == linked }
                    if (target == null) {
                        Log.w(TAG, "applyProfile: linked preset missing: $linked")
                        return@launch
                    }
                    applyPreset(target.settingsJson, master)
                    return@launch
                }
                val fallbackName = snap[VipJamPrefs.ACTIVE_PRESET]
                if (fallbackName.isNullOrBlank()) return@launch
                val fallback = entries.find { it.name == fallbackName } ?: return@launch
                applyPreset(fallback.settingsJson, master)
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatchParamNow(id: Int, v0: Int, v1: Int, v2: Int) {
        try {
            if (id !in KNOWN_PARAM_IDS) {
                Log.w(TAG, "dispatch param with unknown id skipped: $id")
                return
            }
            val ok = synchronized(driverLock) {
                if (!dispatcher.create()) {
                    Log.w(TAG, "dispatch param: driver unavailable")
                    return
                }
                if (id in SINGLE_INT_PARAMS) dispatcher.setParam(id, v0)
                else dispatcher.setParam(id, v0, v1, v2)
            }
            if (!ok) Log.w(TAG, "dispatch param failed: id=$id")
        } catch (_: Exception) {
        }
    }

    private fun dispatchBulkNow(id: Int, values: FloatArray, v0: Int, v1: Int, v2: Int) {
        try {
            if (id !in KNOWN_PARAM_IDS || id in SINGLE_INT_PARAMS) {
                Log.w(TAG, "dispatch bulk with unknown id skipped: $id")
                return
            }
            val ok = synchronized(driverLock) {
                if (!dispatcher.create()) {
                    Log.w(TAG, "dispatch bulk: driver unavailable")
                    return
                }
                when (id) {
                    VipJamDispatcher.CONV_PREP_CLASSIC, VipJamDispatcher.CONV_PREP_NEW ->
                        dispatcher.sendRaw(
                            id,
                            VipJamDispatcher.buildKernelPrepare(v0, v1, v2),
                        )
                    VipJamDispatcher.CONV_CHUNK_CLASSIC, VipJamDispatcher.CONV_CHUNK_NEW ->
                        dispatcher.sendRaw(
                            id,
                            VipJamDispatcher.buildKernelChunk(v0, values),
                        )
                    VipJamDispatcher.CONV_COMMIT_CLASSIC, VipJamDispatcher.CONV_COMMIT_NEW ->
                        dispatcher.sendRaw(
                            id,
                            VipJamDispatcher.buildKernelCommit(v0, v1, v2),
                        )
                    else -> dispatcher.sendFloatArray(id, values)
                }
            }
            if (!ok) Log.w(TAG, "dispatch bulk failed: id=$id")
        } catch (_: Exception) {
        }
    }

    private fun applyPreset(settingsJson: String, masterOn: Boolean) {
        if (settingsJson.isBlank()) {
            Log.w(TAG, "applyPreset with empty json skipped")
            return
        }
        try {
            val ok = synchronized(driverLock) {
                if (!dispatcher.create()) {
                    Log.w(TAG, "applyPreset: driver unavailable")
                    return
                }
                val applied = try {
                    PresetApplier.apply(dispatcher, settingsJson, masterOn)
                } catch (e: Exception) {
                    Log.w(TAG, "applyPreset failed", e)
                    false
                }
                try {
                    dispatcher.enabled = masterOn
                } catch (_: Exception) {
                }
                applied
            }
            if (!ok) Log.w(TAG, "applyPreset: one or more params rejected by driver")
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        try {
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
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VipJam")
                .setContentText("Audio engine idle")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } catch (_: Exception) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VipJam")
                .setContentText("Audio engine idle")
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        const val TAG = "VipJamService"
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
        const val ACTION_DISPATCH_BULK = "com.vipjam.action.DISPATCH_BULK"
        const val EXTRA_PARAM_ID = "param_id"
        const val EXTRA_PARAM_VALUES = "param_values"
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
            VipJamDispatcher.P_PGC_ENABLE,
            VipJamDispatcher.P_DDC_ENABLE,
            VipJamDispatcher.P_DYNSYS_ENABLE,
            VipJamDispatcher.P_TUBE_ENABLE,
            VipJamDispatcher.P_CURE_ENABLE,
            VipJamDispatcher.P_ANALOGX_ENABLE,
            VipJamDispatcher.P_FET_ENABLE,
            VipJamDispatcher.P_VHE_ENABLE,
            VipJamDispatcher.P_DIFF_ENABLE,
            VipJamDispatcher.P_SPK_ENABLE,
        )

        private val KNOWN_PARAM_IDS = SINGLE_INT_PARAMS + setOf(
            VipJamDispatcher.F_BASS,
            VipJamDispatcher.F_EQ,
            VipJamDispatcher.F_REVERB,
            VipJamDispatcher.F_CLARITY,
            VipJamDispatcher.F_TUBE,
            VipJamDispatcher.F_XFEED,
            VipJamDispatcher.F_LIMITER,
            VipJamDispatcher.EQ_LEVELS_CLASSIC,
            VipJamDispatcher.EQ_LEVELS_NEW,
            VipJamDispatcher.DDC_CLASSIC,
            VipJamDispatcher.DDC_NEW,
            VipJamDispatcher.CONV_PREP_CLASSIC,
            VipJamDispatcher.CONV_PREP_NEW,
            VipJamDispatcher.CONV_CHUNK_CLASSIC,
            VipJamDispatcher.CONV_CHUNK_NEW,
            VipJamDispatcher.CONV_COMMIT_CLASSIC,
            VipJamDispatcher.CONV_COMMIT_NEW,
        )

        fun start(context: Context, masterOn: Boolean) {
            try {
                val intent = Intent(context, VipJamService::class.java).apply {
                    action = if (masterOn) ACTION_TOGGLE_MASTER else ACTION_STOP
                    putExtra(EXTRA_MASTER_ENABLED, masterOn)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }

        fun setProfile(context: Context, profile: String) {
            try {
                val intent = Intent(context, VipJamService::class.java).apply {
                    action = ACTION_SET_PROFILE
                    putExtra(EXTRA_PROFILE, profile)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "setProfile failed", e)
            }
        }

        fun dispatchParam(context: Context, id: Int, v0: Int, v1: Int = 0, v2: Int = 0) {
            try {
                val intent = Intent(context, VipJamService::class.java).apply {
                    action = ACTION_DISPATCH_PARAM
                    putExtra(EXTRA_PARAM_ID, id)
                    putExtra(EXTRA_PARAM_V0, v0)
                    putExtra(EXTRA_PARAM_V1, v1)
                    putExtra(EXTRA_PARAM_V2, v2)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "dispatchParam failed", e)
            }
        }

        fun dispatchBulk(context: Context, id: Int, values: FloatArray, v0: Int = 0, v1: Int = 0, v2: Int = 0) {
            try {
                val intent = Intent(context, VipJamService::class.java).apply {
                    action = ACTION_DISPATCH_BULK
                    putExtra(EXTRA_PARAM_ID, id)
                    putExtra(EXTRA_PARAM_VALUES, values)
                    putExtra(EXTRA_PARAM_V0, v0)
                    putExtra(EXTRA_PARAM_V1, v1)
                    putExtra(EXTRA_PARAM_V2, v2)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "dispatchBulk failed", e)
            }
        }

        fun applyPreset(context: Context, settingsJson: String, masterOn: Boolean) {
            try {
                val intent = Intent(context, VipJamService::class.java).apply {
                    action = ACTION_APPLY_PRESET
                    putExtra(EXTRA_PRESET_JSON, settingsJson)
                    putExtra(EXTRA_MASTER_ENABLED, masterOn)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "applyPreset failed", e)
            }
        }
    }
}
