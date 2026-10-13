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
 * (fast-forward or rewind). A repeat, where the track starts again from the beginning at its end, is
 * scrobbled too, once it has lasted [REPEAT_WAIT_MS] with the same track still playing. Both settings
 * are read on every check, so a change applies at once.
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

    /** When a jump back to the start at the end of the track was first seen, while it waits to see whether the track changed. */
    private var pendingRepeatAtMs: Long? = null

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
        // The track changed, so a jump back at the end of the last one was the start of this one, not a repeat.
        pendingRepeatAtMs = null
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
        val expectedMs = expectedPositionMs(now)
        val current = track
        val jumped = positionMs != null && isPlaying && expectedMs != null &&
            abs(positionMs - expectedMs) > SEEK_TOLERANCE_MS
        // Near the end, a jump back is either a repeat or the start of the next track, so it waits to find out.
        val nearEnd = expectedMs != null && current != null && current.durationMs > 0 &&
            expectedMs >= current.durationMs - SEEK_TOLERANCE_MS
        reportedPositionMs = positionMs
        reportedAtMs = now
        reportedAdvancing = advancing

        if (playing == isPlaying) {
            if (!jumped || current == null) return emptyList()
            if (nearEnd) {
                pendingRepeatAtMs = pendingRepeatAtMs ?: now
                return emptyList()
            }
            return listOfNotNull(scrobbleAgainNow(current))
        }
        if (!playing) {
            playedMs += now - playingSinceMs
            isPlaying = false
            return listOfNotNull(checkThreshold())
        }

        val playedBefore = playedMs
        isPlaying = true
        playingSinceMs = now
        val resumed = track ?: return emptyList()
        if (startedAtEpochMs == null) startedAtEpochMs = clock.epochMs()
        // Continuing after a pause or stop plays the track again. A blip in the first moments, which
        // some players make when they change tracks, isn't a pause anyone made.
        val again = if (playedBefore >= MIN_PLAYED_BEFORE_RESUME_MS) scrobbleAgainNow(resumed) else null
        return listOfNotNull(again, TrackerEvent.NowPlaying(resumed))
    }

    /**
     * Scrobbles the current track if it has played long enough and hasn't been scrobbled yet. Also
     * finishes a repeat that has lasted [REPEAT_WAIT_MS] with the same track still playing.
     */
    fun checkThreshold(): TrackerEvent.ScrobbleReady? {
        val current = track ?: return null
        pendingRepeatAtMs?.let { since ->
            if (clock.elapsedMs() - since < REPEAT_WAIT_MS) return null
            pendingRepeatAtMs = null
            return if (isPlaying) scrobbleAgainNow(current) else null
        }
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
        pendingRepeatAtMs?.let { since -> return (since + REPEAT_WAIT_MS - clock.elapsedMs()).coerceAtLeast(0) }
        if (!isPlaying || scrobbled) return null
        val threshold = ScrobbleRules.thresholdMs(current.durationMs, thresholdPercent()) ?: return null
        return (threshold - totalPlayedMs()).coerceAtLeast(0)
    }

    /** The player went away: scrobble what qualifies and stop tracking. */
    fun finish(): TrackerEvent.ScrobbleReady? {
        val event = checkThreshold()
        if (isPlaying) playedMs += clock.elapsedMs() - playingSinceMs
        isPlaying = false
        pendingRepeatAtMs = null
        track = null
        return event
    }

    fun totalPlayedMs(): Long =
        playedMs + if (isPlaying && track != null) clock.elapsedMs() - playingSinceMs else 0

    /** Where the track should be now, if the player's last position is known. */
    private fun expectedPositionMs(now: Long): Long? {
        val last = reportedPositionMs ?: return null
        return last + if (reportedAdvancing) now - reportedAtMs else 0L
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

        /** How long a jump back at the end must hold, with the same track still playing, to count as a repeat. */
        const val REPEAT_WAIT_MS = 2_000L
    }
}
