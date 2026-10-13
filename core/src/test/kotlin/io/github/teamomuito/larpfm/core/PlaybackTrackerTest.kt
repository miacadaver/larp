package io.github.teamomuito.larpfm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackerTest {
    private class FakeClock : Clock {
        var elapsed = 0L
        var epoch = 1_700_000_000_000L

        fun advance(ms: Long) {
            elapsed += ms
            epoch += ms
        }

        override fun elapsedMs() = elapsed
        override fun epochMs() = epoch
    }

    private val clock = FakeClock()
    private val tracker = PlaybackTracker(clock)
    private val song = Track("Artist", "Song", durationMs = 200_000)

    @Test
    fun `uses the configured threshold, read on every check`() {
        var percent = 10
        val custom = PlaybackTracker(clock) { percent }
        custom.onMetadata(song)
        custom.onPlaybackState(true)
        assertEquals(20_000L, custom.msUntilScrobble())

        percent = 1
        clock.advance(2_000)

        assertTrue(custom.checkThreshold() is TrackerEvent.ScrobbleReady)
    }

    @Test
    fun `at zero percent a track scrobbles as soon as it starts`() {
        val instant = PlaybackTracker(clock) { 0 }
        instant.onMetadata(song)
        instant.onPlaybackState(true)

        assertEquals(0L, instant.msUntilScrobble())
        assertEquals(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_000)), instant.checkThreshold())
    }

    @Test
    fun `at zero percent skipping past a track still scrobbles it`() {
        val instant = PlaybackTracker(clock) { 0 }
        val next = Track("Artist", "Next", durationMs = 180_000)
        instant.onMetadata(song)
        instant.onPlaybackState(true)
        clock.advance(300)

        assertEquals(
            listOf(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_000)), TrackerEvent.NowPlaying(next)),
            instant.onMetadata(next),
        )
    }

    @Test
    fun `sends now playing when playback starts`() {
        assertEquals(emptyList<TrackerEvent>(), tracker.onMetadata(song))
        assertEquals(listOf(TrackerEvent.NowPlaying(song)), tracker.onPlaybackState(true))
    }

    @Test
    fun `scrobbles after half the track with the start time`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true)
        clock.advance(99_000)
        assertNull(tracker.checkThreshold())
        assertEquals(1_000L, tracker.msUntilScrobble())

        clock.advance(1_000)
        val event = tracker.checkThreshold()

        assertEquals(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_000)), event)
        assertNull("only scrobbles once", tracker.checkThreshold())
        assertNull(tracker.msUntilScrobble())
    }

    @Test
    fun `long tracks need four minutes`() {
        tracker.onMetadata(song.copy(durationMs = 3_600_000))
        tracker.onPlaybackState(true)

        assertEquals(ScrobbleRules.MAX_THRESHOLD_MS, tracker.msUntilScrobble())
    }

    @Test
    fun `short tracks are never scrobbled`() {
        tracker.onMetadata(song.copy(durationMs = 30_000))
        tracker.onPlaybackState(true)
        clock.advance(30_000)

        assertNull(tracker.msUntilScrobble())
        val next = Track("Other", "Song")
        assertEquals(listOf(TrackerEvent.NowPlaying(next)), tracker.onMetadata(next))
    }

    @Test
    fun `paused time does not count`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true)
        clock.advance(60_000)
        tracker.onPlaybackState(false)
        clock.advance(600_000)

        assertEquals(60_000L, tracker.totalPlayedMs())
        assertNull(tracker.msUntilScrobble())

        tracker.onPlaybackState(true)
        clock.advance(40_000)
        val event = tracker.onPlaybackState(false).single()

        assertEquals(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_000)), event)
    }

    @Test
    fun `skipping early does not scrobble`() {
        val next = Track("Artist", "Next", durationMs = 180_000)
        tracker.onMetadata(song)
        tracker.onPlaybackState(true)
        clock.advance(20_000)

        assertEquals(listOf(TrackerEvent.NowPlaying(next)), tracker.onMetadata(next))
        assertEquals(0L, tracker.totalPlayedMs())
    }

    @Test
    fun `changing track scrobbles the previous one if it qualified`() {
        val next = Track("Artist", "Next", durationMs = 180_000)
        tracker.onMetadata(song)
        tracker.onPlaybackState(true)
        clock.advance(150_000)

        val events = tracker.onMetadata(next)

        assertEquals(
            listOf(
                TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_000)),
                TrackerEvent.NowPlaying(next),
            ),
            events,
        )
    }

    @Test
    fun `repeated metadata for the same song keeps progress and picks up the duration`() {
        tracker.onMetadata(song.copy(durationMs = 0))
        tracker.onPlaybackState(true)
        clock.advance(120_000)
        assertEquals(120_000L, tracker.msUntilScrobble())

        val events = tracker.onMetadata(song.copy(album = "Album"))

        assertEquals(1, events.size)
        assertTrue(events.single() is TrackerEvent.ScrobbleReady)
        assertEquals("Album", tracker.track?.album)
    }

    @Test
    fun `metadata arriving after playback starts uses that moment as the start`() {
        tracker.onPlaybackState(true)
        clock.advance(5_000)

        assertEquals(listOf(TrackerEvent.NowPlaying(song)), tracker.onMetadata(song))
        assertEquals(0L, tracker.totalPlayedMs())
        assertEquals(100_000L, tracker.msUntilScrobble())
    }

    @Test
    fun `finish scrobbles a qualifying track and stops tracking`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true)
        clock.advance(100_000)

        assertTrue(tracker.finish() is TrackerEvent.ScrobbleReady)
        assertNull(tracker.track)
        assertNull(tracker.msUntilScrobble())
    }

    @Test
    fun `resuming a scrobbled track after a pause scrobbles it again straight away`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true)
        clock.advance(100_000)
        assertEquals(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_000)), tracker.onPlaybackState(false).single())

        clock.advance(60_000)
        assertEquals(
            listOf(
                TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_160)),
                TrackerEvent.NowPlaying(song),
            ),
            tracker.onPlaybackState(true),
        )
        assertNull(tracker.msUntilScrobble())
    }

    @Test
    fun `moving to another point in a scrobbled track scrobbles it again`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true, positionMs = 0L)
        clock.advance(100_000)
        assertEquals(emptyList<TrackerEvent>(), tracker.onPlaybackState(true, positionMs = 100_000L))
        assertTrue(tracker.checkThreshold() is TrackerEvent.ScrobbleReady)

        clock.advance(10_000)
        assertEquals(
            listOf(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_110))),
            tracker.onPlaybackState(true, positionMs = 0L),
        )
    }

    @Test
    fun `a move to another point is not a scrobble for a track that hasn't counted yet`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true, positionMs = 0L)
        clock.advance(20_000)

        assertEquals(emptyList<TrackerEvent>(), tracker.onPlaybackState(true, positionMs = 120_000L))
        assertEquals(20_000L, tracker.totalPlayedMs())
    }

    @Test
    fun `playing a little off the reported position is not a move`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true, positionMs = 0L)
        clock.advance(100_000)
        assertTrue(tracker.checkThreshold() is TrackerEvent.ScrobbleReady)

        clock.advance(2_000)
        assertEquals(emptyList<TrackerEvent>(), tracker.onPlaybackState(true, positionMs = 101_000L))
        assertNull(tracker.msUntilScrobble())
    }

    @Test
    fun `metadata repeated for a scrobbled track is not a new play`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true)
        clock.advance(100_000)
        assertTrue(tracker.checkThreshold() is TrackerEvent.ScrobbleReady)

        assertEquals(emptyList<TrackerEvent>(), tracker.onMetadata(song.copy(album = "Album")))
        assertNull(tracker.msUntilScrobble())
        clock.advance(100_000)
        assertNull(tracker.checkThreshold())
    }

    @Test
    fun `every move in a scrobbled track scrobbles it again`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true, positionMs = 0L)
        clock.advance(100_000)
        tracker.onPlaybackState(true, positionMs = 100_000L)
        assertTrue(tracker.checkThreshold() is TrackerEvent.ScrobbleReady)

        clock.advance(10_000)
        assertEquals(
            listOf(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_110))),
            tracker.onPlaybackState(true, positionMs = 60_000L),
        )

        clock.advance(10_000)
        assertEquals(
            listOf(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_120))),
            tracker.onPlaybackState(true, positionMs = 0L),
        )

        clock.advance(21_000)
        assertEquals(
            listOf(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_141))),
            tracker.onPlaybackState(true, positionMs = 150_000L),
        )
    }

    @Test
    fun `moves while paused scrobble once, when the track is played again`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true, positionMs = 0L)
        clock.advance(100_000)
        tracker.onPlaybackState(true, positionMs = 100_000L)
        assertTrue(tracker.checkThreshold() is TrackerEvent.ScrobbleReady)
        assertEquals(emptyList<TrackerEvent>(), tracker.onPlaybackState(false, positionMs = 100_000L))

        clock.advance(60_000)
        assertEquals(emptyList<TrackerEvent>(), tracker.onPlaybackState(false, positionMs = 0L))

        clock.advance(5_000)
        assertEquals(
            listOf(
                TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_165)),
                TrackerEvent.NowPlaying(song),
            ),
            tracker.onPlaybackState(true, positionMs = 0L),
        )
    }

    @Test
    fun `turning scrobble again off leaves resuming and moving as plain plays`() {
        val quiet = PlaybackTracker(clock, scrobbleAgain = { false })
        quiet.onMetadata(song)
        quiet.onPlaybackState(true, positionMs = 0L)
        clock.advance(100_000)
        assertTrue(quiet.checkThreshold() is TrackerEvent.ScrobbleReady)
        quiet.onPlaybackState(false, positionMs = 100_000L)

        clock.advance(60_000)
        assertEquals(listOf(TrackerEvent.NowPlaying(song)), quiet.onPlaybackState(true, positionMs = 100_000L))
        clock.advance(10_000)
        assertEquals(emptyList<TrackerEvent>(), quiet.onPlaybackState(true, positionMs = 0L))
    }

    @Test
    fun `a stall while buffering is not a move`() {
        tracker.onMetadata(song)
        tracker.onPlaybackState(true, positionMs = 0L)
        clock.advance(100_000)
        tracker.onPlaybackState(true, positionMs = 100_000L)
        assertTrue(tracker.checkThreshold() is TrackerEvent.ScrobbleReady)

        clock.advance(5_000)
        tracker.onPlaybackState(tracker.isPlaying)
        clock.advance(20_000)
        assertEquals(emptyList<TrackerEvent>(), tracker.onPlaybackState(true, positionMs = 100_000L))
    }

    @Test
    fun `at zero percent every resume after a pause scrobbles again`() {
        val instant = PlaybackTracker(clock) { 0 }
        instant.onMetadata(song)
        instant.onPlaybackState(true)
        assertEquals(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_000)), instant.checkThreshold())
        assertEquals(emptyList<TrackerEvent>(), instant.onPlaybackState(false))

        clock.advance(5_000)
        assertEquals(
            listOf(TrackerEvent.ScrobbleReady(Scrobble(song, 1_700_000_005)), TrackerEvent.NowPlaying(song)),
            instant.onPlaybackState(true),
        )
        assertNull(instant.checkThreshold())
    }
}
