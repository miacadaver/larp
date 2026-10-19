package io.github.teamomuito.larpfm.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.teamomuito.larpfm.core.ScrobbleRules
import io.github.teamomuito.larpfm.data.Account
import io.github.teamomuito.larpfm.data.ApiLogEntry
import io.github.teamomuito.larpfm.data.AppSetting
import io.github.teamomuito.larpfm.data.NowPlaying
import io.github.teamomuito.larpfm.data.ScrobbleEntry
import io.github.teamomuito.larpfm.data.ScrobbleStatus
import io.github.teamomuito.larpfm.service.ScrobbleListenerService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun HomeScreen(viewModel: MainViewModel, account: Account) {
    val hasAccess by viewModel.hasNotificationAccess.collectAsStateWithLifecycle()
    val enabled by viewModel.scrobblingEnabled.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val thresholdPercent by viewModel.thresholdPercent.collectAsStateWithLifecycle()
    val sendAlbum by viewModel.sendAlbum.collectAsStateWithLifecycle()
    val cleanAlbumTitles by viewModel.cleanAlbumTitles.collectAsStateWithLifecycle()
    val firstArtistOnly by viewModel.firstArtistOnly.collectAsStateWithLifecycle()
    val scrobbleAgain by viewModel.scrobbleAgain.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val lastFmCheck by viewModel.lastFmCheck.collectAsStateWithLifecycle()
    val apiLog by viewModel.apiLog.collectAsStateWithLifecycle()
    val site = account.service.title

    // The prompt flashes a short message, like Pictoclip's "Saved!", then says what's going on again.
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(1_500)
            flash = null
        }
    }
    var askingToSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        DsTopBar("LARP.FM")
        DsPrompt(
            message = flash ?: when {
                !hasAccess -> "Allow notification access first."
                enabled -> "Scrobbling is on."
                else -> "Scrobbling is off. Press A."
            },
            count = if (pendingCount > 0) "$pendingCount waiting" else null,
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AccountRow(account) }

            if (!hasAccess) {
                item { NotificationAccessCard() }
            }

            item { NowPlayingCard(nowPlaying) }

            if (pendingCount > 0 || lastError != null) {
                item { QueueCard(site, pendingCount, lastError, enabled, onSendNow = viewModel::sendNow) }
            }

            item { SectionLabel("Settings") }
            item {
                SettingsCard(
                    thresholdPercent = thresholdPercent,
                    onThresholdChange = viewModel::setThresholdPercent,
                    sendAlbum = sendAlbum,
                    onSendAlbumChange = viewModel::setSendAlbum,
                    cleanAlbumTitles = cleanAlbumTitles,
                    onCleanAlbumTitlesChange = viewModel::setCleanAlbumTitles,
                    firstArtistOnly = firstArtistOnly,
                    onFirstArtistOnlyChange = viewModel::setFirstArtistOnly,
                    scrobbleAgain = scrobbleAgain,
                    onScrobbleAgainChange = viewModel::setScrobbleAgain,
                )
            }

            item { SectionLabel("Apps") }
            item { AppsCard(apps, onEnabledChange = viewModel::setAppEnabled) }

            item { SectionLabel("Check $site") }
            item { CheckCard(site, account.username, lastFmCheck, apiLog, onCheck = viewModel::checkLastFm) }

            item { SectionLabel("Recent scrobbles") }
            if (recent.isEmpty()) {
                item { EmptyScrobbles() }
            }
            items(recent, key = { "scrobble:" + it.id }) { entry -> ScrobbleRow(entry) }
        }

        DsBottomBar {
            DsButton(
                label = "Sign out",
                letter = "B",
                modifier = Modifier.weight(1f),
                onClick = { askingToSignOut = true },
            )
            DsButton(
                // This switches scrobbling, not the music, so it doesn't say "Pause".
                label = if (enabled) "Turn off" else "Turn on",
                letter = "A",
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.setScrobblingEnabled(!enabled)
                    flash = if (enabled) "Scrobbling off!" else "Scrobbling on!"
                },
            )
        }
    }

    if (askingToSignOut) {
        ConfirmDialog(
            message = "Sign out of $site?",
            onYes = viewModel::signOut,
            onNo = { askingToSignOut = false },
        )
    }
}

/** Like a clip in the list: the signed-in account, with the site on the right. */
@Composable
private fun AccountRow(account: Account) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .dsPanel()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageIcon()
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
            DsText(account.username, size = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DsText("Signed in", size = 13.sp, color = DsColors.Subtle)
        }
        DsTimePill(account.service.title)
    }
}

@Composable
private fun NotificationAccessCard() {
    val context = LocalContext.current
    DsCard(fill = DsColors.Background) {
        DsText("Allow notification access", size = 15.sp)
        DsText(
            "Android only lets apps with notification access see what other apps are playing. " +
                "larp.fm doesn't read or store your notifications.",
            size = 13.sp,
            color = DsColors.Subtle,
        )
        DsButton(label = "Open settings", onClick = { context.openNotificationAccessSettings() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DsText(
                "If Android says the setting is restricted: open App info, tap the three-dot menu, " +
                    "choose \"Allow restricted settings\", then try again.",
                size = 13.sp,
                color = DsColors.Subtle,
            )
            DsButton(label = "App info", onClick = { context.openAppInfo() })
        }
    }
}

/** The clip that's selected, so it gets the blue brackets. */
@Composable
private fun NowPlayingCard(nowPlaying: NowPlaying?) {
    val context = LocalContext.current
    Box(Modifier.dsCursor(nowPlaying != null).padding(horizontal = 7.dp, vertical = 3.dp)) {
        DsCard {
            DsText("Now playing", size = 12.sp, color = DsColors.Subtle)
            if (nowPlaying == null) {
                DsText("Nothing right now", size = 18.sp)
            } else {
                val appName = remember(nowPlaying.packageName) { context.appLabel(nowPlaying.packageName) }
                DsText(nowPlaying.track.title, size = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                DsText(nowPlaying.track.artist, size = 15.sp, color = DsColors.Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DsText("in $appName", size = 13.sp, color = DsColors.Subtle)
            }
        }
    }
}

@Composable
private fun QueueCard(
    site: String,
    pendingCount: Int,
    lastError: String?,
    scrobblingEnabled: Boolean,
    onSendNow: () -> Unit,
) {
    DsCard {
        if (pendingCount > 0) {
            DsText(
                if (pendingCount == 1) "1 scrobble waiting to be sent" else "$pendingCount scrobbles waiting to be sent",
                size = 15.sp,
            )
        }
        if (lastError != null) {
            DsText("$site: $lastError", size = 13.sp, color = DsColors.Error)
        }
        if (pendingCount > 0 && scrobblingEnabled) {
            DsButton(label = "Send now", onClick = onSendNow)
        }
    }
}

@Composable
private fun CheckCard(
    site: String,
    username: String,
    check: LastFmCheck,
    apiLog: List<ApiLogEntry>,
    onCheck: () -> Unit,
) {
    val context = LocalContext.current
    DsCard {
        DsText("See what $site has actually recorded for $username.", size = 13.sp, color = DsColors.Subtle)
        DsButton(
            label = if (check == LastFmCheck.Loading) "Checking..." else "Check now",
            onClick = onCheck,
            enabled = check != LastFmCheck.Loading,
        )
        when (check) {
            LastFmCheck.Idle, LastFmCheck.Loading -> Unit
            is LastFmCheck.Error -> DsText(check.message, size = 13.sp, color = DsColors.Error)
            is LastFmCheck.Done -> {
                check.recent.total?.let { DsText("$it scrobbles on $site", size = 15.sp) }
                if (check.recent.tracks.isEmpty()) {
                    DsText("$site has no recent tracks", size = 13.sp, color = DsColors.Subtle)
                }
                for (track in check.recent.tracks) {
                    val time = if (track.nowPlaying) {
                        "now playing"
                    } else {
                        track.timestampSec?.let {
                            DateUtils.getRelativeTimeSpanString(
                                it * 1000,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString()
                        }.orEmpty()
                    }
                    DsText(
                        "${track.title} - ${track.artist} - $time",
                        size = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (apiLog.isNotEmpty()) {
            DsText("Latest replies from $site", size = 12.sp, color = DsColors.Subtle)
            for (entry in apiLog) {
                val time = DateUtils.formatDateTime(context, entry.timeMs, DateUtils.FORMAT_SHOW_TIME)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dsPanel(fill = DsColors.Background, border = 1.dp)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DsText("$time - ${entry.method} - HTTP ${entry.httpCode}", size = 12.sp, color = DsColors.Subtle)
                    DsText(entry.body, size = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    thresholdPercent: Int,
    onThresholdChange: (Int) -> Unit,
    sendAlbum: Boolean,
    onSendAlbumChange: (Boolean) -> Unit,
    cleanAlbumTitles: Boolean,
    onCleanAlbumTitlesChange: (Boolean) -> Unit,
    firstArtistOnly: Boolean,
    onFirstArtistOnlyChange: (Boolean) -> Unit,
    scrobbleAgain: Boolean,
    onScrobbleAgainChange: (Boolean) -> Unit,
) {
    DsCard {
        DsText(
            if (thresholdPercent == 0) "Scrobble threshold: as soon as it starts" else "Scrobble threshold: $thresholdPercent%",
            size = 15.sp,
        )
        DsText(
            if (thresholdPercent == 0) {
                "A track counts as soon as it starts playing, even if you skip it right away."
            } else {
                "A track counts once $thresholdPercent% of it has played, or 4 minutes, whichever comes first."
            },
            size = 13.sp,
            color = DsColors.Subtle,
        )
        DsSlider(
            value = thresholdPercent.toFloat(),
            onValueChange = { onThresholdChange(it.roundToInt()) },
            range = ScrobbleRules.MIN_PERCENT.toFloat()..ScrobbleRules.MAX_PERCENT.toFloat(),
        )
        SettingSwitch(
            title = "Scrobble again",
            description = "Resuming a song after a pause, jumping to another point in it, or it repeating scrobbles it again",
            checked = scrobbleAgain,
            onCheckedChange = onScrobbleAgainChange,
        )
        SettingSwitch(
            title = "First artist only",
            description = "\"Artist A, Artist B\" or \"Artist A feat. Artist B\" is scrobbled as \"Artist A\"",
            checked = firstArtistOnly,
            onCheckedChange = onFirstArtistOnlyChange,
        )
        SettingSwitch(
            title = "Scrobble album",
            description = if (sendAlbum) "Album info is sent with each scrobble" else "Only artist and track are sent",
            checked = sendAlbum,
            onCheckedChange = onSendAlbumChange,
        )
        SettingSwitch(
            title = "Clean album titles",
            description = "Removes anything in ( ) or [ ], e.g. \"Iron Maiden (Remaster) [Special]\" becomes \"Iron Maiden\"",
            checked = cleanAlbumTitles,
            onCheckedChange = onCleanAlbumTitlesChange,
            enabled = sendAlbum,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            DsText(title, size = 15.sp, color = if (enabled) DsColors.Ink else DsColors.Subtle)
            DsText(description, size = 13.sp, color = DsColors.Subtle)
        }
        Spacer(Modifier.width(12.dp))
        DsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun AppsCard(apps: List<AppSetting>, onEnabledChange: (String, Boolean) -> Unit) {
    DsCard {
        if (apps.isEmpty()) {
            DsText(
                "Play something in a music app and it will show up here, so you can choose which apps get scrobbled.",
                size = 15.sp,
            )
        }
        for (app in apps) {
            AppRow(app, onEnabledChange = { onEnabledChange(app.packageName, it) })
        }
    }
}

@Composable
private fun AppRow(app: AppSetting, onEnabledChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val label = remember(app.packageName) { context.appLabel(app.packageName) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            DsText(label, size = 15.sp)
            if (label != app.packageName) {
                DsText(
                    app.packageName,
                    size = 12.sp,
                    color = DsColors.Subtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        DsSwitch(checked = app.enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun EmptyScrobbles() {
    DsText(
        "There are no scrobbles yet.\nPlay something in a music app.",
        size = 15.sp,
        color = DsColors.Subtle,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .dsPanel(fill = DsColors.Background)
            .padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

@Composable
private fun ScrobbleRow(entry: ScrobbleEntry) {
    val track = entry.scrobble.track
    val (status, color) = when (entry.status) {
        ScrobbleStatus.SENT -> "sent" to DsColors.Cursor
        ScrobbleStatus.PENDING -> "waiting to send" to DsColors.Subtle
        ScrobbleStatus.IGNORED, ScrobbleStatus.REJECTED -> (entry.message ?: "not scrobbled") to DsColors.Error
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .dsPanel()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageIcon()
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
            DsText(track.title, size = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DsText(track.artist, size = 13.sp, color = DsColors.Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DsText(status, size = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DsTimePill(formatClipTime(entry.scrobble.timestampSec * 1000))
    }
}

@Composable
private fun ConfirmDialog(message: String, onYes: () -> Unit, onNo: () -> Unit) {
    Dialog(onDismissRequest = onNo) {
        DsCard {
            DsText(message, size = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DsButton(label = "No", letter = "B", modifier = Modifier.weight(1f), onClick = onNo)
                DsButton(label = "Yes", letter = "A", modifier = Modifier.weight(1f), onClick = onYes)
            }
        }
    }
}

private fun Context.openNotificationAccessSettings() {
    // Android 11+ can jump straight to this app's switch; some devices don't support it.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val component = ComponentName(this, ScrobbleListenerService::class.java)
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // Fall through to the list of all listeners.
        }
    }
    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

private fun Context.openAppInfo() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private fun Context.appLabel(packageName: String): String = try {
    @Suppress("DEPRECATION")
    val info = packageManager.getApplicationInfo(packageName, 0)
    packageManager.getApplicationLabel(info).toString()
} catch (e: PackageManager.NameNotFoundException) {
    packageName
}
