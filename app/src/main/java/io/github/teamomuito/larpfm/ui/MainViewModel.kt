package io.github.teamomuito.larpfm.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.teamomuito.larpfm.data.Account
import io.github.teamomuito.larpfm.graph
import io.github.teamomuito.larpfm.lastfm.LastFmClient
import io.github.teamomuito.larpfm.lastfm.LastFmException
import io.github.teamomuito.larpfm.lastfm.RecentTracks
import io.github.teamomuito.larpfm.lastfm.ScrobbleService
import io.github.teamomuito.larpfm.lastfm.Session
import io.github.teamomuito.larpfm.work.FlushWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data class Error(val message: String) : LoginState
}

/** The result of asking Last.fm (or Libre.fm) what it has recorded for the account. */
sealed interface LastFmCheck {
    data object Idle : LastFmCheck
    data object Loading : LastFmCheck
    data class Done(val recent: RecentTracks) : LastFmCheck
    data class Error(val message: String) : LastFmCheck
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val settings = graph.settings
    private val repository = graph.repository

    val account = settings.account
    val scrobblingEnabled = settings.scrobblingEnabled
    val thresholdPercent = settings.thresholdPercent
    val sendAlbum = settings.sendAlbum
    val cleanAlbumTitles = settings.cleanAlbumTitles
    val firstArtistOnly = settings.firstArtistOnly
    val scrobbleAgain = settings.scrobbleAgain
    val apps = settings.apps
    val nowPlaying = repository.nowPlaying
    val recent = repository.recent
    val pendingCount = repository.pendingCount
    val lastError = repository.lastError
    val apiLog = repository.apiLog

    private val _lastFmCheck = MutableStateFlow<LastFmCheck>(LastFmCheck.Idle)
    val lastFmCheck = _lastFmCheck.asStateFlow()

    val savedApiKey: String get() = settings.apiKey

    /** The site the login screen starts on: the one used last. */
    val signInService: ScrobbleService get() = settings.signInService
    val savedApiSecret: String get() = settings.apiSecret

    private val _hasNotificationAccess = MutableStateFlow(false)
    val hasNotificationAccess = _hasNotificationAccess.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState = _loginState.asStateFlow()

    init {
        refreshNotificationAccess()
    }

    fun checkLastFm() {
        if (_lastFmCheck.value == LastFmCheck.Loading) return
        _lastFmCheck.value = LastFmCheck.Loading
        viewModelScope.launch {
            _lastFmCheck.value = try {
                val recent = withContext(Dispatchers.IO) { graph.submitter.checkLastFm() }
                if (recent == null) LastFmCheck.Error("Not signed in") else LastFmCheck.Done(recent)
            } catch (e: LastFmException) {
                LastFmCheck.Error(e.message ?: "Error ${e.code}")
            } catch (e: IOException) {
                val site = settings.account.value?.service?.title ?: "the site"
                LastFmCheck.Error("Couldn't reach $site. Check your connection.")
            }
        }
    }

    fun refreshNotificationAccess() {
        val context = getApplication<Application>()
        _hasNotificationAccess.value =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    /** The page on [service] to open for signing in through the browser. [apiKey] is only used for Last.fm. */
    fun webSignInUrl(service: ScrobbleService, apiKey: String, apiSecret: String): String {
        settings.saveSignInService(service)
        if (service.fixedApiKey == null) settings.saveApiCredentials(apiKey, apiSecret)
        _loginState.value = LoginState.Idle
        return graph.submitter.client(service, settings.apiKeyFor(service), settings.apiSecretFor(service))
            .webAuthUrl(AUTH_CALLBACK)
    }

    /** The site sent the browser back with [token] after the user approved the app. */
    fun finishWebSignIn(token: String) {
        val service = settings.signInService
        completeSignIn(
            service,
            settings.apiKeyFor(service),
            settings.apiSecretFor(service),
            "${service.title} didn't confirm the sign-in. Try again.",
        ) { it.getSession(token) }
    }

    fun signIn(service: ScrobbleService, username: String, password: String, apiKey: String, apiSecret: String) {
        settings.saveSignInService(service)
        completeSignIn(
            service,
            service.fixedApiKey ?: apiKey,
            service.fixedApiSecret ?: apiSecret,
            "Wrong username or password.",
        ) { it.getMobileSession(username, password) }
    }

    private fun completeSignIn(
        service: ScrobbleService,
        apiKey: String,
        apiSecret: String,
        authFailedMessage: String,
        request: (LastFmClient) -> Session,
    ) {
        if (_loginState.value == LoginState.Loading) return
        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            _loginState.value = try {
                val session = withContext(Dispatchers.IO) { request(graph.submitter.client(service, apiKey, apiSecret)) }
                settings.signIn(Account(session.username, session.key, apiKey, apiSecret, service))
                FlushWorker.enqueue(getApplication<Application>())
                LoginState.Idle
            } catch (e: LastFmException) {
                LoginState.Error(
                    when (e.code) {
                        LastFmException.AUTHENTICATION_FAILED,
                        LastFmException.UNAUTHORIZED_TOKEN,
                        LastFmException.TOKEN_EXPIRED,
                        -> authFailedMessage
                        LastFmException.INVALID_API_KEY -> "That API key isn't valid."
                        LastFmException.INVALID_SIGNATURE -> "That shared secret doesn't match the API key."
                        else -> e.message ?: "${service.title} error ${e.code}"
                    },
                )
            } catch (e: IOException) {
                LoginState.Error("Couldn't reach ${service.title}. Check your connection.")
            }
        }
    }

    fun signOut() {
        settings.signOut()
        repository.setLastError(null)
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        settings.setScrobblingEnabled(enabled)
        if (enabled) FlushWorker.enqueue(getApplication<Application>())
    }

    fun setThresholdPercent(percent: Int) = settings.setThresholdPercent(percent)

    fun setSendAlbum(send: Boolean) = settings.setSendAlbum(send)

    fun setCleanAlbumTitles(clean: Boolean) = settings.setCleanAlbumTitles(clean)

    fun setFirstArtistOnly(firstOnly: Boolean) = settings.setFirstArtistOnly(firstOnly)

    fun setScrobbleAgain(again: Boolean) = settings.setScrobbleAgain(again)

    fun setAppEnabled(packageName: String, enabled: Boolean) = settings.setAppEnabled(packageName, enabled)

    fun sendNow() {
        if (settings.scrobblingEnabled.value) FlushWorker.enqueue(getApplication<Application>())
    }

    companion object {
        /** Where Last.fm or Libre.fm sends the browser after the user approves the app; see the manifest. */
        const val AUTH_CALLBACK = "larpfm://auth"
    }
}
