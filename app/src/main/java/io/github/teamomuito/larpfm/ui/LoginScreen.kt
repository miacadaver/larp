package io.github.teamomuito.larpfm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.teamomuito.larpfm.lastfm.ScrobbleService

private const val API_ACCOUNT_URL = "https://www.last.fm/api/account/create"

@Composable
fun LoginScreen(viewModel: MainViewModel) {
    val state by viewModel.loginState.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf(viewModel.savedApiKey) }
    var apiSecret by rememberSaveable { mutableStateOf(viewModel.savedApiSecret) }
    var showApiFields by rememberSaveable { mutableStateOf(apiKey.isBlank() || apiSecret.isBlank()) }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var service by rememberSaveable { mutableStateOf(viewModel.signInService) }
    val site = service.title
    val uriHandler = LocalUriHandler.current
    val loading = state == LoginState.Loading
    val needsApiKey = service.fixedApiKey == null
    val hasApiKey = !needsApiKey || (apiKey.isNotBlank() && apiSecret.isNotBlank())
    val canSignInWithPassword = !loading && hasApiKey && username.isNotBlank() && password.isNotEmpty()
    val canSignIn = if (usePassword) canSignInWithPassword else !loading && hasApiKey

    Column(Modifier.fillMaxSize().imePadding()) {
        DsTopBar("LARP.FM")
        DsPrompt("Sign in to scrobble what you play.")

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DsCard {
                DsSegments(
                    options = ScrobbleService.entries,
                    selected = service,
                    label = { it.title },
                    onSelect = { service = it },
                    enabled = !loading,
                )
                DsText(
                    if (usePassword) {
                        "Your password goes straight to $site and isn't stored."
                    } else {
                        "Opens $site in your browser. Tap Allow and you'll land back here, signed in."
                    },
                    size = 13.sp,
                    color = DsColors.Subtle,
                )
                (state as? LoginState.Error)?.let {
                    DsText(it.message, size = 13.sp, color = DsColors.Error)
                }
            }

            if (usePassword) {
                DsCard {
                    DsTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Username or email",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    )
                    DsTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        password = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                }
            }

            // Libre.fm doesn't need an API key of your own.
            if (needsApiKey && showApiFields) {
                DsCard {
                    DsText("Last.fm API account", size = 15.sp)
                    DsText(
                        "Scrobbling needs an API key. Creating one is free and takes a minute; " +
                            "the application name and description can be anything.",
                        size = 13.sp,
                        color = DsColors.Subtle,
                    )
                    DsButton(label = "Create an API account", onClick = { uriHandler.openUri(API_ACCOUNT_URL) })
                    DsTextField(value = apiKey, onValueChange = { apiKey = it }, label = "API key")
                    DsTextField(value = apiSecret, onValueChange = { apiSecret = it }, label = "Shared secret")
                }
            } else if (needsApiKey) {
                DsButton(label = "Use a different API key", onClick = { showApiFields = true })
            }
        }

        DsBottomBar {
            DsButton(
                label = if (usePassword) "Browser" else "Password",
                letter = "B",
                modifier = Modifier.weight(1f),
                enabled = !loading,
                onClick = { usePassword = !usePassword },
            )
            DsButton(
                label = if (loading) "Signing in..." else "Sign in",
                letter = "A",
                modifier = Modifier.weight(1f),
                enabled = canSignIn,
                onClick = {
                    if (usePassword) {
                        viewModel.signIn(service, username.trim(), password, apiKey.trim(), apiSecret.trim())
                    } else {
                        uriHandler.openUri(viewModel.webSignInUrl(service, apiKey.trim(), apiSecret.trim()))
                    }
                },
            )
        }
    }
}
