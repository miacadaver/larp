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
 * Only time spent playing counts; pauses don't. Until a track is scrobbled, its play time adds up
 * across pauses. Once it has been scrobbled, playing it again scrobbles it again straight away, if
 * [scrobbleAgain] is on: resuming it after a pause, or moving to another point in it.
 * [thresholdPercent] and [scrobbleAgain] are read on every check, so a changed setting applies at once.
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

    /** Where the player last said the track was, and when, so a move to another point can be spotted. */
    private var reportedPositionMs: Long? = null
    private var reportedAtMs = 0L

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
     * [positionMs] is where the player says playback is now, or null if it doesn't say. While playing,
     * a position other than the one the track would have reached means it was moved to another point.
     */
    fun onPlaybackState(playing: Boolean, positionMs: Long? = null): List<TrackerEvent> {
        val now = clock.elapsedMs()
        val movedWhilePlaying = positionMs != null && isPlaying && movedFromExpected(positionMs, now)
        // Without a position, what was reported before can't be compared with what comes next.
        reportedPositionMs = positionMs
        reportedAtMs = now

        if (playing == isPlaying) {
            val current = track
            return if (movedWhilePlaying && current != null) {
                listOfNotNull(replayIfScrobbled(current))
            } else {
                emptyList()
            }
        }
        if (!playing) {
            playedMs += now - playingSinceMs
            isPlaying = false
            return listOfNotNull(checkThreshold())
        }

        isPlaying = true
        playingSinceMs = now
        val current = track ?: return emptyList()
        if (startedAtEpochMs == null) startedAtEpochMs = clock.epochMs()
        // Resuming a scrobbled track after a pause plays it again.
        return listOfNotNull(replayIfScrobbled(current), TrackerEvent.NowPlaying(current))
    }

    /** Scrobbles the current track if it has played long enough and hasn't been scrobbled yet. */
    fun checkThreshold(): TrackerEvent.ScrobbleReady? {
        val current = track ?: return null
        val startedAt = startedAtEpochMs ?: return null
        if (scrobbled) return null
        val threshold = ScrobbleRules.thresholdMs(current.durationMs, thresholdPercent()) ?: return null
        if (totalPlayedMs() < threshold) return null
        scrobbled = true
        return TrackerEvent.ScrobbleReady(Scrobble(current, startedAt / 1000))
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
    private fun movedFromExpected(positionMs: Long, now: Long): Boolean {
        val last = reportedPositionMs ?: return false
        val expectedMs = last + (now - reportedAtMs)
        return abs(positionMs - expectedMs) > SEEK_TOLERANCE_MS
    }

    /** A scrobbled track that's played again is scrobbled again straight away, if it's eligible at all. */
    private fun replayIfScrobbled(current: Track): TrackerEvent.ScrobbleReady? {
        if (!scrobbled || !scrobbleAgain()) return null
        if (ScrobbleRules.thresholdMs(current.durationMs, thresholdPercent()) == null) return null
        return TrackerEvent.ScrobbleReady(Scrobble(current, clock.epochMs() / 1000))
    }

    private companion object {
        /** A position this far from where the track would be counts as a fast-forward or rewind. */
        const val SEEK_TOLERANCE_MS = 3_000L
    }
}
