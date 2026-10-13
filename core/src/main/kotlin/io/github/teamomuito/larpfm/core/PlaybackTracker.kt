package io.github.teamomuito.larpfm.core

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
 * across pauses. Once it has been scrobbled, every new play of it counts from zero: stopping and
 * continuing it, or skipping back to its start, is scrobbled again when it has played long enough.
 * [thresholdPercent] is read on every check, so a changed setting applies to the current track.
 */
class PlaybackTracker(
    private val clock: Clock,
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

    /** Where the player last said the track was, and when, so a jump back to the start can be spotted. */
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
     * [positionMs] is where the player says playback is now, or null if it doesn't say. Playing,
     * a position that jumps back to the start of the track means the track was skipped back to.
     */
    fun onPlaybackState(playing: Boolean, positionMs: Long? = null): List<TrackerEvent> {
        val now = clock.elapsedMs()
        val skippedToStart = positionMs != null && isPlaying && jumpedToStart(positionMs, now)
        if (positionMs != null) {
            reportedPositionMs = positionMs
            reportedAtMs = now
        }

        if (playing == isPlaying) {
            val current = track
            return if (skippedToStart && current != null && restartIfScrobbled()) {
                listOf(TrackerEvent.NowPlaying(current))
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
        // Continuing after a stop is another play of the track, so a scrobbled one starts over.
        restartIfScrobbled()
        if (startedAtEpochMs == null) startedAtEpochMs = clock.epochMs()
        return listOf(TrackerEvent.NowPlaying(current))
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

    /** Only called while playing. True when the player is now near the start, well back from where it was. */
    private fun jumpedToStart(positionMs: Long, now: Long): Boolean {
        val last = reportedPositionMs ?: return false
        val expectedMs = last + (now - reportedAtMs)
        return positionMs <= RESTART_WINDOW_MS && expectedMs - positionMs >= RESTART_JUMP_MS
    }

    /** Only called while playing. Starts counting again from zero if the track was already scrobbled. */
    private fun restartIfScrobbled(): Boolean {
        if (!scrobbled) return false
        scrobbled = false
        playedMs = 0
        playingSinceMs = clock.elapsedMs()
        startedAtEpochMs = clock.epochMs()
        return true
    }

    private companion object {
        /** A position this close to the start counts as the track starting over... */
        const val RESTART_WINDOW_MS = 5_000L

        /** ...when the player was at least this far on before it jumped there. */
        const val RESTART_JUMP_MS = 10_000L
    }
}
