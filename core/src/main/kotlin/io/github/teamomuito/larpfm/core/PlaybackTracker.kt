package io.github.teamomuito.larpfm.core

import kotlin.math.abs

interface Clock {
    /** Monotonic time, used to measure how long a track has played. */
    fun elapsedMs(): Long

    /** Wall-clock time, used for scrobble timestamps. */
    fun epochMs(): Long
}

sealed interface TrackerEvent {
    data class NowPlaying(val track: Track) : TrackerEvent
    data class ScrobbleReady(val scrobble: Scrobble) : TrackerEvent
}

/**
 * Follows one media player's metadata and play/pause state, and decides when the current track
 * should be sent as "now playing" and when it has played long enough to be scrobbled.
 *
 * Only time spent playing counts; pauses don't. A track is scrobbled once it has played past
 * [thresholdPercent] of its length. With [scrobbleAgain] on, it's also scrobbled straight away every
 * time it's continued after a pause or stop, and every time playback jumps to another point in it
 * (fast-forward or rewind). Both settings are read on every check, so a change applies at once.
 */
class PlaybackTracker(
    private val clock: Clock,
    private val scrobbleAgain: () -> Boolean = { true },
    private val thresholdPercent: () -> Int = { ScrobbleRules.DEFAULT_PERCENT },
) {
    var track: Track? = null
        private set
    var isPlaying: Boolean = false
        private set

    private var playedMs = 0L
    private var playingSinceMs = 0L
    private var startedAtEpochMs: Long? = null
    private var scrobbled = false

    /** Where the player last said the track was, when, and whether it was moving, so a jump can be spotted. */
    private var reportedPositionMs: Long? = null
    private var reportedAtMs = 0L
    private var reportedAdvancing = false

    /** The newest timestamp scrobbled, so two scrobbles in the same second still count as two. */
    private var lastTimestampSec = 0L

    fun onMetadata(newTrack: Track?): List<TrackerEvent> {
        val current = track
        if (newTrack != null && current != null && newTrack.isSameSongAs(current)) {
            val durationMs = if (newTrack.durationMs > 0) newTrack.durationMs else current.durationMs
            track = newTrack.copy(durationMs = durationMs)
            // A newly reported duration can lower the threshold below what has already played.
            return listOfNotNull(checkThreshold())
        }

        val events = mutableListOf<TrackerEvent>()
        checkThreshold()?.let(events::add)
        track = newTrack
        playedMs = 0
        scrobbled = false
        startedAtEpochMs = null
        reportedPositionMs = null
        if (isPlaying) {
            playingSinceMs = clock.elapsedMs()
            if (newTrack != null) {
                startedAtEpochMs = clock.epochMs()
                events += TrackerEvent.NowPlaying(newTrack)
            }
        }
        return events
    }

    /**
     * [positionMs] is where the player says playback is, or null if it doesn't say. [advancing] is
     * false while that position is stuck, e.g. while buffering, even though the track still counts as
     * playing.
     */
    fun onPlaybackState(playing: Boolean, positionMs: Long? = null, advancing: Boolean = playing): List<TrackerEvent> {
        val now = clock.elapsedMs()
        val jumped = positionMs != null && isPlaying && jumpedFromExpected(positionMs, now)
        reportedPositionMs = positionMs
        reportedAtMs = now
        reportedAdvancing = advancing

        if (playing == isPlaying) {
            val current = track
            return if (jumped && current != null) listOfNotNull(scrobbleAgainNow(current)) else emptyList()
        }
        if (!playing) {
            playedMs += now - playingSinceMs
            isPlaying = false
            return listOfNotNull(checkThreshold())
        }

        val playedBefore = playedMs
        isPlaying = true
        playingSinceMs = now
        val current = track ?: return emptyList()
        if (startedAtEpochMs == null) startedAtEpochMs = clock.epochMs()
        // Continuing after a pause or stop plays the track again. A blip in the first moments, which
        // some players make when they change tracks, isn't a pause anyone made.
        val again = if (playedBefore >= MIN_PLAYED_BEFORE_RESUME_MS) scrobbleAgainNow(current) else null
        return listOfNotNull(again, TrackerEvent.NowPlaying(current))
    }

    /** Scrobbles the current track if it has played long enough and hasn't been scrobbled yet. */
    fun checkThreshold(): TrackerEvent.ScrobbleReady? {
        val current = track ?: return null
        val startedAt = startedAtEpochMs ?: return null
        if (scrobbled) return null
        val threshold = ScrobbleRules.thresholdMs(current.durationMs, thresholdPercent()) ?: return null
        if (totalPlayedMs() < threshold) return null
        scrobbled = true
        val timestampSec = startedAt / 1000
        lastTimestampSec = maxOf(lastTimestampSec, timestampSec)
        return TrackerEvent.ScrobbleReady(Scrobble(current, timestampSec))
    }

    /** How much longer the current track must play to qualify, or null if there's nothing to wait for. */
    fun msUntilScrobble(): Long? {
        val current = track ?: return null
        if (!isPlaying || scrobbled) return null
        val threshold = ScrobbleRules.thresholdMs(current.durationMs, thresholdPercent()) ?: return null
        return (threshold - totalPlayedMs()).coerceAtLeast(0)
    }

    /** The player went away: scrobble what qualifies and stop tracking. */
    fun finish(): TrackerEvent.ScrobbleReady? {
        val event = checkThreshold()
        if (isPlaying) playedMs += clock.elapsedMs() - playingSinceMs
        isPlaying = false
        track = null
        return event
    }

    fun totalPlayedMs(): Long =
        playedMs + if (isPlaying && track != null) clock.elapsedMs() - playingSinceMs else 0

    /**
     * Only called while playing. Whether the player is somewhere other than where the track would
     * have got to since it last reported, which is what a fast-forward or rewind looks like.
     */
    private fun jumpedFromExpected(positionMs: Long, now: Long): Boolean {
        val last = reportedPositionMs ?: return false
        val expectedMs = last + (if (reportedAdvancing) now - reportedAtMs else 0L)
        // Playing off the end into the next track isn't a jump; the next track's metadata follows.
        val durationMs = track?.durationMs ?: 0L
        if (durationMs > 0 && expectedMs >= durationMs - SEEK_TOLERANCE_MS) return false
        return abs(positionMs - expectedMs) > SEEK_TOLERANCE_MS
    }

    /** Scrobbles the current track again right now, if that's switched on and the track can be scrobbled at all. */
    private fun scrobbleAgainNow(current: Track): TrackerEvent.ScrobbleReady? {
        if (!scrobbleAgain()) return null
        if (ScrobbleRules.thresholdMs(current.durationMs, thresholdPercent()) == null) return null
        val timestampSec = maxOf(clock.epochMs() / 1000, lastTimestampSec + 1)
        lastTimestampSec = timestampSec
        return TrackerEvent.ScrobbleReady(Scrobble(current, timestampSec))
    }

    private companion object {
        /** A position this far from where the track would be counts as a fast-forward or rewind. */
        const val SEEK_TOLERANCE_MS = 3_000L

        /** How long a track must have played before stopping and continuing it counts as playing it again. */
        const val MIN_PLAYED_BEFORE_RESUME_MS = 2_000L
    }
}
