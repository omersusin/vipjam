package com.vipjam.appprofile

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.vipjam.data.PresetStore
import com.vipjam.data.VipJamPrefs
import com.vipjam.service.VipJamService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppProfileMonitor(
    private val store: AppProfileStore,
    private val presetStore: PresetStore,
    private val prefs: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    sealed interface Action {
        data class Apply(val presetName: String) : Action
        data object Restore : Action
        data object None : Action
    }

    private var pollJob: Job? = null
    private var debounceJob: Job? = null
    private var appContext: Context? = null
    internal var lastAutoPkg: String? = null
    internal var preSwitchPreset: String? = null

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (needsPermission(app)) {
            stop()
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val pkg = withContext(Dispatchers.IO) { queryForegroundPackage(app) }
                    if (pkg != null) handleForeground(pkg)
                } catch (_: Exception) {
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        debounceJob?.cancel()
        debounceJob = null
        lastAutoPkg = null
        preSwitchPreset = null
    }

    private fun handleForeground(pkg: String) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            applyForPackage(pkg)
        }
    }

    private suspend fun applyForPackage(pkg: String) {
        val ctx = appContext ?: return
        val map = try {
            store.appPresetMap.first()
        } catch (_: Exception) {
            return
        }
        val onlyHeadphones = try {
            store.headphoneOnly.first()
        } catch (_: Exception) {
            false
        }
        val snap = try {
            prefs.data.first()
        } catch (_: Exception) {
            return
        }
        val profile = snap[VipJamPrefs.ACTIVE_PROFILE] ?: VipJamPrefs.Profiles.HEADSET
        val master = snap[VipJamPrefs.MASTER_ENABLE] ?: false
        when (val action = resolveAction(pkg, map[pkg], lastAutoPkg, onlyHeadphones, isHeadphoneRoute(profile))) {
            is Action.Apply -> {
                if (lastAutoPkg == null) preSwitchPreset = snap[VipJamPrefs.ACTIVE_PRESET]
                val json = try {
                    presetStore.entries.first().find { it.name == action.presetName }?.settingsJson
                } catch (_: Exception) {
                    null
                }
                if (json.isNullOrBlank()) return
                try {
                    VipJamService.applyPreset(ctx, json, master)
                } catch (_: Exception) {
                    return
                }
                lastAutoPkg = pkg
            }
            Action.Restore -> {
                val fallback = preSwitchPreset
                if (fallback.isNullOrBlank()) {
                    lastAutoPkg = null
                    return
                }
                val json = try {
                    presetStore.entries.first().find { it.name == fallback }?.settingsJson
                } catch (_: Exception) {
                    null
                }
                if (!json.isNullOrBlank()) {
                    try {
                        VipJamService.applyPreset(ctx, json, master)
                    } catch (_: Exception) {
                    }
                }
                lastAutoPkg = null
                preSwitchPreset = null
            }
            Action.None -> Unit
        }
    }

    private fun queryForegroundPackage(context: Context): String? {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - QUERY_WINDOW_MS, now) ?: return null
            var last: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.packageName
            }
            last
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val POLL_MS = 2000L
        const val DEBOUNCE_MS = 300L
        private const val QUERY_WINDOW_MS = 10_000L

        fun resolveAction(
            currentPkg: String?,
            mappedPreset: String?,
            lastAutoPkg: String?,
            headphoneOnly: Boolean,
            isHeadphoneRoute: Boolean,
        ): Action {
            if (currentPkg.isNullOrBlank()) return Action.None
            if (headphoneOnly && !isHeadphoneRoute) return Action.None
            if (!mappedPreset.isNullOrBlank()) {
                if (currentPkg == lastAutoPkg) return Action.None
                return Action.Apply(mappedPreset)
            }
            if (lastAutoPkg != null && currentPkg != lastAutoPkg) return Action.Restore
            return Action.None
        }

        fun isHeadphoneRoute(profile: String?): Boolean {
            return profile == VipJamPrefs.Profiles.HEADSET || profile == VipJamPrefs.Profiles.BLUETOOTH
        }

        fun needsPermission(context: Context): Boolean {
            return try {
                val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
                val mode = ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
                mode != AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                true
            }
        }
    }
}
