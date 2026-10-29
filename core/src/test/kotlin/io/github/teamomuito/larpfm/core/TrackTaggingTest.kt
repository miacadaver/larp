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

    @Test
    fun `override matches full original artist ignoring case and surrounding spaces`() {
        val tagged = TrackTagging.apply(track, true, false, true,
            listOf(AlbumOverride("  ARTIST A FEAT. ARTIST B  ", "Chosen Album")))
        assertEquals(track.copy(artist = "Artist A", album = "Chosen Album"), tagged)
    }

    @Test
    fun `override does not match a partial artist`() {
        val tagged = TrackTagging.apply(track, true, false, true,
            listOf(AlbumOverride("Artist A", "Chosen Album")))
        assertEquals(track.copy(artist = "Artist A"), tagged)
    }

    @Test
    fun `send album off takes precedence over overrides`() {
        val tagged = TrackTagging.apply(track, false, true, false,
            listOf(AlbumOverride(track.artist, "Chosen Album")))
        assertEquals(track.withoutAlbum(), tagged)
    }

    @Test
    fun `explicit override keeps brackets even when album cleanup is on`() {
        val tagged = TrackTagging.apply(track, true, true, false,
            listOf(AlbumOverride(track.artist, "Chosen Album (Live)")))
        assertEquals(track.copy(album = "Chosen Album (Live)"), tagged)
    }

    @Test
    fun `override fills an absent album`() {
        val tagged = TrackTagging.apply(track.copy(album = null), true, false, false,
            listOf(AlbumOverride(track.artist, "Chosen Album")))
        assertEquals("Chosen Album", tagged.album)
    }

    @Test
    fun `unmatched artist keeps normal album cleanup`() {
        val tagged = TrackTagging.apply(track, true, true, false,
            listOf(AlbumOverride("Other Artist", "Chosen Album")))
        assertEquals(track.copy(album = "Album"), tagged)
    }
}
