package com.vipjam.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.vipjam.R
import com.vipjam.data.VipJamPrefs
import com.vipjam.ui.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MasterTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            try {
                refresh()
            } catch (_: Exception) {
            }
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            try {
                toggle()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun refresh() {
        val on = try {
            applicationContext.prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
        } catch (e: Exception) {
            Log.w(TAG, "tile refresh: prefs read failed", e)
            return
        }
        updateTile(on)
    }

    private suspend fun toggle() {
        val prefs = applicationContext.prefs
        val current = try {
            prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
        } catch (e: Exception) {
            Log.w(TAG, "tile toggle: prefs read failed", e)
            return
        }
        val tileState = try {
            qsTile?.state
        } catch (_: Exception) {
            null
        }
        val desired = if (tileState == null) !current else tileState != Tile.STATE_ACTIVE
        try {
            prefs.edit { it[VipJamPrefs.MASTER_ENABLE] = desired }
        } catch (e: Exception) {
            Log.w(TAG, "tile toggle: prefs write failed", e)
            return
        }
        try {
            VipJamService.start(this@MasterTileService, desired)
        } catch (e: Exception) {
            Log.w(TAG, "tile toggle: service start failed", e)
        }
        refresh()
    }

    private fun updateTile(on: Boolean) {
        try {
            val tile = qsTile ?: return
            tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            try {
                tile.label = getString(R.string.qs_tile_master_label)
            } catch (_: Exception) {
            }
            tile.updateTile()
        } catch (e: Exception) {
            Log.w(TAG, "tile update failed", e)
        }
    }

    companion object {
        const val TAG = "MasterTileService"
    }
}
