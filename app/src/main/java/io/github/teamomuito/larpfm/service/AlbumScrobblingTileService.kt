package io.github.teamomuito.larpfm.service

import io.github.teamomuito.larpfm.R
import io.github.teamomuito.larpfm.graph
import kotlinx.coroutines.flow.StateFlow

class AlbumScrobblingTileService : ScrobbleTileService() {
    override val setting: StateFlow<Boolean> get() = graph.settings.sendAlbum
    override val tileLabel: String get() = getString(R.string.tile_album_scrobbling)

    override fun toggle() {
        graph.settings.setSendAlbum(!setting.value)
    }
}
