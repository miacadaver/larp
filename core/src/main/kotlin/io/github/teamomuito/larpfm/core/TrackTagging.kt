package io.github.teamomuito.larpfm.core

/** An exact artist match, ignoring capitalization and surrounding spaces. */
data class AlbumOverride(val artist: String, val album: String) {
    fun matches(artistName: String): Boolean = artist.trim().equals(artistName.trim(), ignoreCase = true)
}

object TrackTagging {
    fun apply(
        track: Track,
        sendAlbum: Boolean,
        cleanAlbumTitles: Boolean,
        firstArtistOnly: Boolean,
        albumOverrides: List<AlbumOverride> = emptyList(),
    ): Track {
        val artist = if (firstArtistOnly) track.copy(artist = ArtistNames.first(track.artist)) else track
        return when {
            !sendAlbum -> artist.withoutAlbum()
            // Match the original artist before "First artist only"; preserve the album entered by the user.
            albumOverrides.any { it.matches(track.artist) } ->
                artist.copy(album = albumOverrides.first { it.matches(track.artist) }.album)
            cleanAlbumTitles -> artist.copy(album = artist.album?.let(TitleCleaner::stripBrackets))
            else -> artist
        }
    }
}
