package io.github.teamomuito.larpfm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AlbumOverridesScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val rules by viewModel.albumOverrides.collectAsStateWithLifecycle()
    val sendAlbum by viewModel.sendAlbum.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var originalArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var artist by rememberSaveable { mutableStateOf("") }
    var album by rememberSaveable { mutableStateOf("") }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingArtist by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        DsTopBar("ALBUM OVERRIDES")
        DsPrompt("Choose an artist's album.")
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DsCard {
                    DsText(
                        "Artist names match exactly, ignoring capitalization and surrounding spaces. " +
                            "Use the artist name reported by your player, including featured artists.",
                        size = 13.sp, color = DsColors.Subtle,
                    )
                    DsText("Applies to new plays and now-playing updates. Queued plays keep their saved album.",
                        size = 13.sp, color = DsColors.Subtle)
                    if (!sendAlbum) {
                        DsText("Turn on Send album in Settings to use these rules.", size = 13.sp, color = DsColors.Error)
                    }
                }
            }
            if (rules.isEmpty()) {
                item { DsCard { DsText("No album overrides yet.") } }
            }
            items(rules, key = { it.artist }) { rule ->
                DsCard {
                    DsText(rule.artist, size = 16.sp)
                    DsText("→ " + rule.album, size = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DsButton("Edit", modifier = Modifier.weight(1f), onClick = {
                            originalArtist = rule.artist
                            artist = rule.artist
                            album = rule.album
                            saveError = null
                            editing = true
                        })
                        DsButton("Delete", modifier = Modifier.weight(1f), onClick = { deletingArtist = rule.artist })
                    }
                }
            }
            item {
                DsButton("+ Add rule", modifier = Modifier.fillMaxWidth(), onClick = {
                    originalArtist = null
                    artist = ""
                    album = ""
                    saveError = null
                    editing = true
                })
            }
        }
        DsBottomBar {
            DsButton("Back", letter = "B", modifier = Modifier.weight(1f), onClick = onBack)
        }
    }

    if (editing) {
        val duplicate = rules.any { it.artist != originalArtist && it.matches(artist) }
        Dialog(onDismissRequest = { editing = false }) {
            DsCard(Modifier.imePadding().verticalScroll(rememberScrollState())) {
                DsText(if (originalArtist == null) "ADD ALBUM OVERRIDE" else "EDIT ALBUM OVERRIDE")
                DsTextField(artist, onValueChange = { artist = it; saveError = null }, label = "When artist is")
                DsTextField(album, onValueChange = { album = it; saveError = null }, label = "Set album to")
                DsText("Applies to new plays.", size = 13.sp, color = DsColors.Subtle)
                if (duplicate) {
                    DsText("A rule already exists for this artist.", size = 13.sp, color = DsColors.Error)
                }
                saveError?.let { DsText(it, size = 13.sp, color = DsColors.Error) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DsButton("Cancel", modifier = Modifier.weight(1f), onClick = { editing = false })
                    DsButton("Save", modifier = Modifier.weight(1f),
                        enabled = artist.isNotBlank() && album.isNotBlank() && !duplicate,
                        onClick = {
                            if (viewModel.saveAlbumOverride(originalArtist, artist, album)) {
                                editing = false
                            } else {
                                saveError = "Enter an artist and album with no duplicate artist."
                            }
                        },
                    )
                }
            }
        }
    }

    deletingArtist?.let { name ->
        Dialog(onDismissRequest = { deletingArtist = null }) {
            DsCard {
                DsText("Delete the album override for $name?")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DsButton("Cancel", modifier = Modifier.weight(1f), onClick = { deletingArtist = null })
                    DsButton("Delete", modifier = Modifier.weight(1f), onClick = {
                        viewModel.deleteAlbumOverride(name)
                        deletingArtist = null
                    })
                }
            }
        }
    }
}
