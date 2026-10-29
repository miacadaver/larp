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
        // Match the original artist before "First artist only".
        val override = albumOverrides.firstOrNull { it.matches(track.artist) }
        return when {
            !sendAlbum -> artist.withoutAlbum()
            // Preserve the album entered by the user, including brackets.
            override != null -> artist.copy(album = override.album)
            cleanAlbumTitles -> artist.copy(album = artist.album?.let(TitleCleaner::stripBrackets))
            else -> artist
        }
    }
}
