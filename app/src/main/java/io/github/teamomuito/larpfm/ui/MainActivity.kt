package io.github.teamomuito.larpfm.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        // Only on a fresh start, so rotating the screen doesn't reuse the one-time token.
        if (savedInstanceState == null) handleAuthCallback(intent)
        // The status bar is dark blue, so its icons are light. The bottom bar is light, so its icons are dark.
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        setContent {
            LarpTheme {
                Box(Modifier.fillMaxSize().dsGrid()) {
                    val account by viewModel.account.collectAsStateWithLifecycle()
                    val signedIn = account
                    if (signedIn == null) {
                        LoginScreen(viewModel)
                    } else {
                        HomeScreen(viewModel, signedIn)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // The user may be coming back from granting notification access.
        viewModel.refreshNotificationAccess()
    }

    /** After the user approves the app, Last.fm sends the browser to larpfm://auth?token=… */
    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        val callback = Uri.parse(MainViewModel.AUTH_CALLBACK)
        if (uri.scheme != callback.scheme || uri.host != callback.host) return
        uri.getQueryParameter("token")?.let(viewModel::finishWebSignIn)
    }
}
