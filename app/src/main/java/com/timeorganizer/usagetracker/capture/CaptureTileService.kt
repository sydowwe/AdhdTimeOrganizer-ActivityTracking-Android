package com.timeorganizer.usagetracker.capture

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.timeorganizer.usagetracker.util.openAndCollapse

/**
 * The "Capture" Quick Settings tile (prompt 18): a tap opens [CaptureActivity] over whatever is on screen and closes the
 * shade. On a locked phone it asks for the unlock first — the thought goes to the user's to-dos. It has no state to
 * show, so it is always drawn inactive (no "on" look). A long press opens the status screen, as for `PauseTileService`.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun open() = openAndCollapse(CaptureActivity.intent(this))
}
