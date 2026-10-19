package io.github.teamomuito.larpfm.core

object TrackTagging {
    fun apply(
        track: Track,
        sendAlbum: Boolean,
        cleanAlbumTitles: Boolean,
        firstArtistOnly: Boolean,
    ): Track {
        val artist = if (firstArtistOnly) track.copy(artist = ArtistNames.first(track.artist)) else track
        return when {
            !sendAlbum -> artist.withoutAlbum()
            cleanAlbumTitles -> artist.copy(album = artist.album?.let(TitleCleaner::stripBrackets))
            else -> artist
        }
    }
}
