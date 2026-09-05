package com.vipjam.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.datastore.preferences.core.edit
import com.vipjam.R
import com.vipjam.data.VipJamPrefs
import com.vipjam.ui.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MasterTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val on = applicationContext.prefs.data.first()[VipJamPrefs.MASTER_ENABLE] ?: false
            qsTile?.let {
                it.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                it.label = getString(R.string.qs_tile_master_label)
                it.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val desired = qsTile?.state != Tile.STATE_ACTIVE
        scope.launch {
            applicationContext.prefs.edit { it[VipJamPrefs.MASTER_ENABLE] = desired }
            VipJamService.start(this@MasterTileService, desired)
            qsTile?.let {
                it.state = if (desired) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                it.label = getString(R.string.qs_tile_master_label)
                it.updateTile()
            }
        }
    }
}
