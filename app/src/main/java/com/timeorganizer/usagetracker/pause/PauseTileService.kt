package com.timeorganizer.usagetracker.pause

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.util.openAndCollapse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Pause tracking" Quick Settings tile (prompt 19). While recording a tap offers the four lengths ([PauseChoiceActivity]),
 * never a fixed one: a stray tap in the shade must not silently stop the recording (decision 19-4). While paused it is lit,
 * says until when, and a tap is Resume at once. On a locked phone it asks for the unlock first. A long press opens the status
 * screen (the manifest's `QS_TILE_PREFERENCES`).
 *
 * The system calls [onStartListening] each time the shade shows it, so it is never stale when seen and nothing has to tell it
 * about a pause (`requestListeningState` reaches only active tiles).
 */
class PauseTileService : TileService() {

    private val scope = MainScope()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        scope.launch { draw() }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { act() } else act()
    }

    private fun act() {
        val app = applicationContext
        scope.launch {
            if (withContext(Dispatchers.IO) { Pauses.active(app) } == null) {
                openAndCollapse(PauseChoiceActivity.intent(this@PauseTileService))
            } else {
                withContext(NonCancellable + Dispatchers.IO) { Pauses.resume(app) }
                draw()
            }
        }
    }

    private suspend fun draw() {
        val active = withContext(Dispatchers.IO) { Pauses.active(applicationContext) }
        qsTile?.apply {
            state = if (active == null) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = getString(if (active == null) R.string.pause_tile_label else R.string.pause_tile_paused)
            subtitle = active?.let { PauseNotifier.until(this@PauseTileService, it) } ?: getString(R.string.pause_tile_recording)
            updateTile()
        }
    }
}
