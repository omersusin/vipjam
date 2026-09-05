package com.vipjam.service

import android.service.quicksettings.TileService

class MasterTileService : TileService() {
    override fun onClick() {
        super.onClick()
        VipJamService.start(this, qsTile?.state != android.service.quicksettings.Tile.STATE_ACTIVE)
    }
}
