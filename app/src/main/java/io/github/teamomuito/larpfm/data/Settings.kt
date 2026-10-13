package io.github.teamomuito.larpfm.data

import android.content.Context
import androidx.core.content.edit
import io.github.teamomuito.larpfm.BuildConfig
import io.github.teamomuito.larpfm.core.ScrobbleRules
import io.github.teamomuito.larpfm.lastfm.ScrobbleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A signed-in Last.fm account. The session key only works with the API account it was created with. */
data class Account(
    val username: String,
    val sessionKey: String,
    val apiKey: String,
    val apiSecret: String,
    val service: ScrobbleService = ScrobbleService.LASTFM,
)

data class AppSetting(val packageName: String, val enabled: Boolean)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _scrobblingEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val scrobblingEnabled: StateFlow<Boolean> = _scrobblingEnabled.asStateFlow()

    private val _thresholdPercent = MutableStateFlow(
        prefs.getInt(KEY_THRESHOLD_PERCENT, ScrobbleRules.DEFAULT_PERCENT)
            .coerceIn(ScrobbleRules.MIN_PERCENT, ScrobbleRules.MAX_PERCENT),
    )

    /** How much of a track has to play before it's scrobbled. */
    val thresholdPercent: StateFlow<Int> = _thresholdPercent.asStateFlow()

    private val _sendAlbum = MutableStateFlow(prefs.getBoolean(KEY_SEND_ALBUM, true))

    /** When off, scrobbles carry only the artist and track, with no album. */
    val sendAlbum: StateFlow<Boolean> = _sendAlbum.asStateFlow()

    private val _cleanAlbumTitles = MutableStateFlow(prefs.getBoolean(KEY_CLEAN_ALBUM_TITLES, false))

    /** Strip anything in (parentheses) or [brackets] from album titles, e.g. "(2011 Remaster)". */
    val cleanAlbumTitles: StateFlow<Boolean> = _cleanAlbumTitles.asStateFlow()

    private val _firstArtistOnly = MutableStateFlow(prefs.getBoolean(KEY_FIRST_ARTIST_ONLY, false))

    /** Scrobble "Artist A, Artist B" or "Artist A feat. Artist B" as just "Artist A". */
    val firstArtistOnly: StateFlow<Boolean> = _firstArtistOnly.asStateFlow()

    private val _scrobbleAgain = MutableStateFlow(prefs.getBoolean(KEY_SCROBBLE_AGAIN, true))

    /** Resuming a track after a pause, or jumping to another point in it, scrobbles it again right away. */
    val scrobbleAgain: StateFlow<Boolean> = _scrobbleAgain.asStateFlow()

    private val _apps = MutableStateFlow(loadApps())

    /** Every app that has played media since the app was installed, and whether it gets scrobbled. */
    val apps: StateFlow<List<AppSetting>> = _apps.asStateFlow()

    /** The API account used last, falling back to the one built into the APK. */
    val apiKey: String get() = prefs.getString(KEY_API_KEY, null) ?: BuildConfig.LASTFM_API_KEY
    val apiSecret: String get() = prefs.getString(KEY_API_SECRET, null) ?: BuildConfig.LASTFM_API_SECRET

    /** The site signed in to, or the one picked for a sign-in that's under way. */
    val signInService: ScrobbleService get() = ScrobbleService.of(prefs.getString(KEY_SERVICE, null))

    fun apiKeyFor(service: ScrobbleService): String = service.fixedApiKey ?: apiKey
    fun apiSecretFor(service: ScrobbleService): String = service.fixedApiSecret ?: apiSecret

    fun signIn(account: Account) {
        prefs.edit {
            putString(KEY_USERNAME, account.username)
            putString(KEY_SESSION_KEY, account.sessionKey)
            putString(KEY_SERVICE, account.service.name)
            // A site's fixed API account shouldn't replace the user's own Last.fm one.
            if (account.service.fixedApiKey == null) {
                putString(KEY_API_KEY, account.apiKey)
                putString(KEY_API_SECRET, account.apiSecret)
            }
        }
        _account.value = account
    }

    /** Remembers which site a browser sign-in was started with, in case the app is closed meanwhile. */
    fun saveSignInService(service: ScrobbleService) {
        prefs.edit { putString(KEY_SERVICE, service.name) }
    }

    /** Remembers the API account a browser sign-in was started with, in case the app is closed meanwhile. */
    fun saveApiCredentials(apiKey: String, apiSecret: String) {
        prefs.edit {
            putString(KEY_API_KEY, apiKey)
            putString(KEY_API_SECRET, apiSecret)
        }
    }

    fun signOut() {
        prefs.edit {
            remove(KEY_USERNAME)
            remove(KEY_SESSION_KEY)
        }
        _account.value = null
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        _scrobblingEnabled.value = enabled
    }

    fun setThresholdPercent(percent: Int) {
        val value = percent.coerceIn(ScrobbleRules.MIN_PERCENT, ScrobbleRules.MAX_PERCENT)
        prefs.edit { putInt(KEY_THRESHOLD_PERCENT, value) }
        _thresholdPercent.value = value
    }

    fun setSendAlbum(send: Boolean) {
        prefs.edit { putBoolean(KEY_SEND_ALBUM, send) }
        _sendAlbum.value = send
    }

    fun setCleanAlbumTitles(clean: Boolean) {
        prefs.edit { putBoolean(KEY_CLEAN_ALBUM_TITLES, clean) }
        _cleanAlbumTitles.value = clean
    }

    fun setFirstArtistOnly(firstOnly: Boolean) {
        prefs.edit { putBoolean(KEY_FIRST_ARTIST_ONLY, firstOnly) }
        _firstArtistOnly.value = firstOnly
    }

    fun setScrobbleAgain(again: Boolean) {
        prefs.edit { putBoolean(KEY_SCROBBLE_AGAIN, again) }
        _scrobbleAgain.value = again
    }

    fun isAppEnabled(packageName: String): Boolean =
        _apps.value.firstOrNull { it.packageName == packageName }?.enabled ?: (packageName !in DEFAULT_DISABLED)

    @Synchronized
    fun markAppSeen(packageName: String) {
        val seen = prefs.getStringSet(KEY_SEEN_APPS, null).orEmpty()
        if (packageName in seen) return
        prefs.edit {
            putStringSet(KEY_SEEN_APPS, seen + packageName)
            if (packageName in DEFAULT_DISABLED) {
                putStringSet(KEY_DISABLED_APPS, prefs.getStringSet(KEY_DISABLED_APPS, null).orEmpty() + packageName)
            }
        }
        _apps.value = loadApps()
    }

    @Synchronized
    fun setAppEnabled(packageName: String, enabled: Boolean) {
        val disabled = prefs.getStringSet(KEY_DISABLED_APPS, null).orEmpty()
        prefs.edit { putStringSet(KEY_DISABLED_APPS, if (enabled) disabled - packageName else disabled + packageName) }
        _apps.value = loadApps()
    }

    private fun loadAccount(): Account? {
        val sessionKey = prefs.getString(KEY_SESSION_KEY, null) ?: return null
        val service = signInService
        return Account(
            username = prefs.getString(KEY_USERNAME, null).orEmpty(),
            sessionKey = sessionKey,
            apiKey = apiKeyFor(service),
            apiSecret = apiSecretFor(service),
            service = service,
        )
    }

    private fun loadApps(): List<AppSetting> {
        val disabled = prefs.getStringSet(KEY_DISABLED_APPS, null).orEmpty()
        return prefs.getStringSet(KEY_SEEN_APPS, null).orEmpty()
            .sorted()
            .map { AppSetting(it, it !in disabled) }
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_SESSION_KEY = "session_key"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_SECRET = "api_secret"
        const val KEY_SERVICE = "service"
        const val KEY_ENABLED = "scrobbling_enabled"
        const val KEY_THRESHOLD_PERCENT = "threshold_percent"
        const val KEY_SEND_ALBUM = "send_album"
        const val KEY_CLEAN_ALBUM_TITLES = "clean_album_titles"
        const val KEY_FIRST_ARTIST_ONLY = "first_artist_only"
        const val KEY_SCROBBLE_AGAIN = "scrobble_again"
        const val KEY_SEEN_APPS = "seen_apps"
        const val KEY_DISABLED_APPS = "disabled_apps"

        /** Apps that mostly play things that aren't music. They can still be switched on. */
        val DEFAULT_DISABLED = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.netflix.mediaclient",
        )
    }
}
