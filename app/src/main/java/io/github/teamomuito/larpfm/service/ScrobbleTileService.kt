package io.github.teamomuito.larpfm.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.teamomuito.larpfm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

abstract class ScrobbleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    protected abstract val setting: StateFlow<Boolean>
    protected abstract val tileLabel: String

    protected abstract fun toggle()

    override fun onTileAdded() {
        super.onTileAdded()
        render(setting.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = scope.launch {
            setting.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        toggle()
        render(setting.value)
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = tileLabel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}

object ScrobbleTiles {
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        TileService.requestListeningState(app, ComponentName(app, ScrobblingTileService::class.java))
        TileService.requestListeningState(app, ComponentName(app, AlbumScrobblingTileService::class.java))
    }
}
