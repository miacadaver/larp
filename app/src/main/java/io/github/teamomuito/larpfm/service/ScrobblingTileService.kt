package io.github.teamomuito.larpfm.service

import io.github.teamomuito.larpfm.R
import io.github.teamomuito.larpfm.graph
import io.github.teamomuito.larpfm.work.FlushWorker
import kotlinx.coroutines.flow.StateFlow

class ScrobblingTileService : ScrobbleTileService() {
    override val setting: StateFlow<Boolean> get() = graph.settings.scrobblingEnabled
    override val tileLabel: String get() = getString(R.string.tile_scrobbling)

    override fun toggle() {
        val enabled = !setting.value
        graph.settings.setScrobblingEnabled(enabled)
        if (enabled) FlushWorker.enqueue(this)
    }
}
