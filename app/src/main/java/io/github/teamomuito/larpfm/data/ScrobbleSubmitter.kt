package io.github.teamomuito.larpfm.data

import android.util.Log
import io.github.teamomuito.larpfm.core.Track
import io.github.teamomuito.larpfm.lastfm.LastFmClient
import io.github.teamomuito.larpfm.lastfm.LastFmException
import io.github.teamomuito.larpfm.lastfm.RecentTracks
import io.github.teamomuito.larpfm.lastfm.ScrobbleService
import io.github.teamomuito.larpfm.lastfm.UrlConnectionTransport
import java.io.IOException

/** Talks to Last.fm or Libre.fm on behalf of the signed-in account. Every method blocks; call off the main thread. */
class ScrobbleSubmitter(
    private val settings: Settings,
    private val repository: ScrobbleRepository,
    userAgent: String,
) {
    enum class Outcome {
        /** Nothing left to send. */
        DONE,

        /** Try again later: offline, the site is down, or the daily limit was hit. */
        RETRY,

        /** Can't send until the user signs in again. */
        STOP,
    }

    private val transport = UrlConnectionTransport(userAgent)

    /** After the site says it's getting too many requests, now playing updates pause until then. */
    @Volatile
    private var quietUntilMs = 0L

    fun client(service: ScrobbleService, apiKey: String, apiSecret: String) =
        LastFmClient(apiKey, apiSecret, transport, service) { method, response ->
            repository.logResponse(method, response.code, response.body)
        }

    private fun client(account: Account) = client(account.service, account.apiKey, account.apiSecret)

    fun sendNowPlaying(track: Track) {
        if (!settings.scrobblingEnabled.value) return
        val account = settings.account.value ?: return
        if (System.currentTimeMillis() < quietUntilMs) return
        try {
            client(account).updateNowPlaying(account.sessionKey, track)
        } catch (e: IOException) {
            Log.i(TAG, "Couldn't send now playing", e)
        } catch (e: LastFmException) {
            Log.i(TAG, "Couldn't send now playing", e)
            noteRateLimit(e)
            repository.setLastError("Now playing: ${e.message}")
        }
    }

    /** Asks the site what it has actually recorded for the signed-in account. */
    @Throws(IOException::class, LastFmException::class)
    fun checkLastFm(): RecentTracks? {
        val account = settings.account.value ?: return null
        return client(account).getRecentTracks(account.username, limit = 5)
    }

    /** Sends every queued scrobble, in batches. */
    fun flush(): Outcome {
        if (!settings.scrobblingEnabled.value) {
            repository.refresh()
            return Outcome.DONE
        }
        val account = settings.account.value ?: return Outcome.STOP
        val client = client(account)
        try {
            while (true) {
                if (!settings.scrobblingEnabled.value) return Outcome.DONE
                val batch = repository.pending(LastFmClient.MAX_BATCH_SIZE)
                if (batch.isEmpty()) break
                val outcome = submit(client, account.sessionKey, batch)
                if (outcome != null) return outcome
            }
            repository.setLastError(null)
            return Outcome.DONE
        } finally {
            repository.refresh()
        }
    }

    /** Returns null to keep going, or the outcome that should end this flush. */
    private fun submit(client: LastFmClient, sessionKey: String, batch: List<ScrobbleEntry>): Outcome? {
        val results = try {
            client.scrobble(sessionKey, batch.map { it.scrobble })
        } catch (e: IOException) {
            repository.setLastError("Network error: ${e.message}")
            return Outcome.RETRY
        } catch (e: LastFmException) {
            noteRateLimit(e)
            repository.setLastError(e.message)
            return when {
                e.isRetryable -> Outcome.RETRY
                e.isAuthError -> Outcome.STOP
                // One bad scrobble fails the whole batch; send them one at a time to find it.
                batch.size > 1 -> batch.firstNotNullOfOrNull { submit(client, sessionKey, listOf(it)) }
                else -> {
                    repository.setStatus(batch.single().id, ScrobbleStatus.REJECTED, e.message)
                    null
                }
            }
        }

        var hitDailyLimit = false
        for ((entry, result) in batch.zip(results)) {
            when {
                result.accepted -> repository.setStatus(entry.id, ScrobbleStatus.SENT)
                result.isDailyLimit -> hitDailyLimit = true
                else -> repository.setStatus(entry.id, ScrobbleStatus.IGNORED, result.reason)
            }
        }
        if (hitDailyLimit) {
            repository.setLastError("Daily scrobble limit reached, will retry later")
            return Outcome.RETRY
        }
        return null
    }

    private fun noteRateLimit(e: LastFmException) {
        if (e.code == LastFmException.RATE_LIMITED) quietUntilMs = System.currentTimeMillis() + QUIET_MS
    }

    private companion object {
        const val TAG = "ScrobbleSubmitter"
        const val QUIET_MS = 5 * 60 * 1000L
    }
}
