package io.github.teamomuito.larpfm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackTaggingTest {
    private val track = Track(
        artist = "Artist A feat. Artist B",
        title = "Song",
        album = "Album (Remaster)",
        albumArtist = "Artist A",
    )

    @Test
    fun `album scrobbling off drops album metadata`() {
        val tagged = TrackTagging.apply(
            track,
            sendAlbum = false,
            cleanAlbumTitles = false,
            firstArtistOnly = false,
        )

        assertEquals(track.copy(album = null, albumArtist = null), tagged)
    }

    @Test
    fun `album scrobbling on keeps album metadata`() {
        val tagged = TrackTagging.apply(
            track,
            sendAlbum = true,
            cleanAlbumTitles = false,
            firstArtistOnly = false,
        )

        assertEquals(track, tagged)
    }

    @Test
    fun `clean album titles only applies while album scrobbling is on`() {
        val cleaned = TrackTagging.apply(
            track,
            sendAlbum = true,
            cleanAlbumTitles = true,
            firstArtistOnly = false,
        )
        val stripped = TrackTagging.apply(
            track,
            sendAlbum = false,
            cleanAlbumTitles = true,
            firstArtistOnly = false,
        )

        assertEquals("Album", cleaned.album)
        assertNull(stripped.album)
    }

    @Test
    fun `first artist only applies independently of the album setting`() {
        val tagged = TrackTagging.apply(
            track,
            sendAlbum = false,
            cleanAlbumTitles = false,
            firstArtistOnly = true,
        )

        assertEquals("Artist A", tagged.artist)
        assertNull(tagged.album)
    }
}
