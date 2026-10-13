package io.github.teamomuito.larpfm.service

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import io.github.teamomuito.larpfm.core.ArtistNames
import io.github.teamomuito.larpfm.core.Clock
import io.github.teamomuito.larpfm.core.PlaybackTracker
import io.github.teamomuito.larpfm.core.Scrobble
import io.github.teamomuito.larpfm.core.TitleCleaner
import io.github.teamomuito.larpfm.core.Track
import io.github.teamomuito.larpfm.core.TrackerEvent
import io.github.teamomuito.larpfm.data.NowPlaying
import io.github.teamomuito.larpfm.graph
import io.github.teamomuito.larpfm.work.FlushWorker
import kotlinx.coroutines.launch

/**
 * Watches every app's media session. Android only hands those out to apps with notification
 * access, which is why this is a notification listener; the notifications themselves are ignored.
 */
class ScrobbleListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val players = mutableMapOf<MediaSession.Token, Player>()
    private var sessionManager: MediaSessionManager? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updatePlayers(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, ScrobbleListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            sessionManager = manager
            updatePlayers(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access was revoked", e)
        }
    }

    override fun onListenerDisconnected() {
        releaseAll()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun releaseAll() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        players.values.forEach { it.release() }
        players.clear()
    }

    private fun updatePlayers(controllers: List<MediaController>) {
        val active = controllers.associateBy { it.sessionToken }
        for (token in players.keys - active.keys) {
            players.remove(token)?.release()
        }
        for ((token, controller) in active) {
            if (token !in players) {
                graph.settings.markAppSeen(controller.packageName)
                players[token] = Player(controller)
            }
        }
    }

    /** One media session, e.g. a music app, and the progress of whatever it's playing. */
    private inner class Player(private val controller: MediaController) : MediaController.Callback() {
        private val appPackage: String = controller.packageName
        private val tracker = PlaybackTracker(
            SystemClocks,
            scrobbleAgain = { graph.settings.scrobbleAgain.value },
        ) { graph.settings.thresholdPercent.value }
        private val checkThreshold = Runnable { handle(listOfNotNull(tracker.checkThreshold())) }

        init {
            controller.registerCallback(this, handler)
            handle(tracker.onMetadata(controller.metadata?.toTrack()))
            reportState(controller.playbackState)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handle(tracker.onMetadata(metadata?.toTrack()))
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            reportState(state)
        }

        private fun reportState(state: PlaybackState?) {
            // Buffering isn't a stop, so a brief stall doesn't count as a pause and play again. Its
            // position still counts: players often buffer right after a fast-forward or rewind.
            if (state.isTransient()) {
                handle(tracker.onPlaybackState(tracker.isPlaying, state.positionNowMs(), advancing = false))
                return
            }
            handle(tracker.onPlaybackState(state.isPlaying(), state.positionNowMs()))
        }

        override fun onSessionDestroyed() {
            players.remove(controller.sessionToken)
            release()
        }

        fun release() {
            controller.unregisterCallback(this)
            handle(listOfNotNull(tracker.finish()))
        }

        private fun handle(events: List<TrackerEvent>) {
            for (event in events) {
                when (event) {
                    is TrackerEvent.NowPlaying -> nowPlaying(event.track)
                    is TrackerEvent.ScrobbleReady -> scrobble(event.scrobble)
                }
            }
            if (!tracker.isPlaying || tracker.track == null) graph.repository.clearNowPlaying(appPackage)

            // Check back when the track should have played long enough to count.
            handler.removeCallbacks(checkThreshold)
            tracker.msUntilScrobble()?.let { handler.postDelayed(checkThreshold, it + 1_000) }
        }

        private fun nowPlaying(track: Track) {
            if (!shouldScrobble()) return
            graph.repository.setNowPlaying(NowPlaying(track, appPackage))
            val sent = withTagSettings(track)
            graph.scope.launch { graph.submitter.sendNowPlaying(sent) }
        }

        private fun scrobble(scrobble: Scrobble) {
            if (!shouldScrobble()) return
            val sent = scrobble.copy(track = withTagSettings(scrobble.track))
            graph.scope.launch {
                graph.repository.enqueue(sent, appPackage)
                FlushWorker.enqueue(applicationContext)
            }
        }

        /** Applies the artist and album settings to what's sent to Last.fm. */
        private fun withTagSettings(track: Track): Track {
            val settings = graph.settings
            val artist = if (settings.firstArtistOnly.value) track.copy(artist = ArtistNames.first(track.artist)) else track
            return when {
                !settings.sendAlbum.value -> artist.withoutAlbum()
                settings.cleanAlbumTitles.value -> artist.copy(album = artist.album?.let(TitleCleaner::stripBrackets))
                else -> artist
            }
        }

        private fun shouldScrobble(): Boolean {
            val settings = graph.settings
            return settings.account.value != null &&
                settings.scrobblingEnabled.value &&
                settings.isAppEnabled(appPackage)
        }
    }

    private object SystemClocks : Clock {
        override fun elapsedMs() = SystemClock.elapsedRealtime()
        override fun epochMs() = System.currentTimeMillis()
    }

    private companion object {
        const val TAG = "ScrobbleListener"
    }
}

private fun PlaybackState?.isPlaying() = this?.state == PlaybackState.STATE_PLAYING

private fun PlaybackState?.isTransient(): Boolean {
    val state = this?.state ?: return false
    return state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING
}

/** Where playback is now: the position the player reported, moved on by the time since it reported it. */
private fun PlaybackState?.positionNowMs(): Long? {
    val state = this ?: return null
    if (state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return null
    if (state.state != PlaybackState.STATE_PLAYING) return state.position
    val sinceUpdateMs = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
    return state.position + (sinceUpdateMs * state.playbackSpeed).toLong()
}

private fun MediaMetadata.toTrack(): Track? {
    fun text(key: String) = getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    val title = text(MediaMetadata.METADATA_KEY_TITLE) ?: text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
    val albumArtist = text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    val artist = text(MediaMetadata.METADATA_KEY_ARTIST) ?: albumArtist ?: return null
    return Track(
        artist = artist,
        title = title,
        album = text(MediaMetadata.METADATA_KEY_ALBUM),
        albumArtist = albumArtist,
        durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
    )
}
