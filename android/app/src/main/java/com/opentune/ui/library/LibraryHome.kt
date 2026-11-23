package com.opentune.ui.library

import com.opentune.ui.components.pinnedToTop
import com.opentune.ui.components.menuClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.opentune.ui.components.glass
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import com.opentune.data.settings.AppSettings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import com.opentune.ui.browse.playlistSearchResults
import com.opentune.ui.browse.coverWash
import com.opentune.ui.browse.dismissPlaylistSearch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.artworkAt
import com.opentune.data.model.HEADER_ART_PX
import com.opentune.ui.ScreenCache
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.pressable
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/** Pinning playlists to the top of Library. */
internal object LibraryPins {
    fun pinned(key: String) = key in AppSettings.ui.value.pinnedPlaylists
    /** As many as the row on Library shows. */
    const val MAX = 5

    fun toggle(key: String) {
        val pins = AppSettings.ui.value.pinnedPlaylists
        if (key !in pins && pins.size >= MAX) {
            com.opentune.data.Pills.info("Up to $MAX playlists can be pinned. Unpin one first")
            return
        }
        AppSettings.updateUi { ui ->
            ui.copy(pinnedPlaylists = if (key in ui.pinnedPlaylists) ui.pinnedPlaylists - key else ui.pinnedPlaylists + key)
        }
    }
}

@Composable
internal fun SourceTag(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { SheetButton("Save", onClick = { onConfirm(name) }) },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}

/**
 * Names a new playlist. Signed in, it can go on YouTube Music too (on unless
 * turned off), with a choice of who can see it there; [onCreate] gets that
 * privacy, or null for a playlist on this phone only.
 */
/** Songs liked in the app. */
@Composable
fun LikedScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val liked by LibraryStore.liked.collectAsState()
    val songs = remember(liked) { liked.map { it.toSong() } }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Liked songs", onBack = onBack) }
        if (songs.isEmpty()) {
            item {
                MessageState(Icons.Rounded.Favorite, "No liked songs", Modifier.padding(top = 32.dp), message = "Tap the heart in the player, or Like in a song's menu.")
            }
            return@LazyColumn
        }
        item {
            PlayButtons(
                onPlay = { actions.playAll(songs, 0, false, "Liked songs") },
                onShuffle = { actions.playAll(songs, 0, true, "Liked songs") },
                onSmartShuffle = { actions.smartShuffle(songs, "Liked songs") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        itemsIndexed(songs, key = { _, s -> s.videoId }) { i, song ->
            SongListItem(
                song = song,
                onClick = { actions.playAll(songs, i, false, "Liked songs") },
                modifier = Modifier.animateItem(),
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
            )
        }
    }
}

/** A playlist made in the app: play, rename, delete, add and remove songs. */
@Composable
fun LocalPlaylistScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by LibraryStore.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    if (adding && playlist != null) AddSongsSheet(id, onDismiss = { adding = false })
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Tagged with the playlist, so smart shuffle can offer to keep a song it mixed in.
    val songs = remember(playlist) { playlist.songs.map { it.toSong().copy(playbackSourceId = playlist.id) } }
    val search = remember(id) { com.opentune.ui.browse.PlaylistSearch() }
    val query = search.query
    val searching = search.open
    var order by remember(id) { mutableStateOf(com.opentune.ui.browse.ListOrder.DEFAULT) }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(searching, query) {
        if (searching) listState.scrollToItem(0)
    }
    val matching = remember(songs, query, order) {
        val found = songs.indices.filter { songs[it].title.contains(query.trim(), true) || songs[it].artist.contains(query.trim(), true) }
        when (order) {
            com.opentune.ui.browse.ListOrder.DEFAULT -> found
            com.opentune.ui.browse.ListOrder.A_TO_Z -> found.sortedBy { songs[it].title.lowercase() }
            com.opentune.ui.browse.ListOrder.Z_TO_A -> found.sortedByDescending { songs[it].title.lowercase() }
        }
    }
    val shown = matching.map(songs::get)
    if (renaming) NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { LibraryStore.renamePlaylist(id, it); renaming = false }
    if (deleting) {
        FloatingDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("The songs stay on your phone; only this list goes.") },
            confirmButton = { SheetButton("Delete", tone = SheetTone.Danger, onClick = { deleting = false; LibraryStore.deletePlaylist(id) }) },
            dismissButton = { SheetButton("Cancel", onClick = { deleting = false }, closes = true) },
        )
    }
    val share: () -> Unit = { com.opentune.ui.share.ListShare.shareLocal(context, playlist.name, songs) }
    val tools: @Composable (Modifier) -> Unit = { modifier ->
        ListTools(
            songs,
            onAddSongs = { adding = true },
            onShare = share,
            modifier = modifier,
        )
    }
    if (moreOpen) LocalListMoreSheet(playlist, songs, actions,
        onAddSongs = { adding = true }, onRename = { renaming = true }, onDelete = { deleting = true },
        onShare = share, onDismiss = { moreOpen = false })
    com.opentune.ui.components.GlassPage(Modifier.fillMaxSize().dismissPlaylistSearch(search), overlay = {
        com.opentune.ui.browse.PlaylistSearchHeader(search) {
        com.opentune.ui.browse.CollapsingBar(playlist.name, listState, onBack, forceCollapsed = searching,
            artworkBehind = !(playlist.coverUrl ?: songs.firstOrNull()?.thumbnailUrl).isNullOrBlank()) {
            Row(Modifier.height(48.dp).glass(androidx.compose.foundation.shape.CircleShape).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (searching) search.close() else search.open = true }) {
                    Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search this list")
                }
                Box {
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    androidx.compose.material3.DropdownMenu(sortOpen, { sortOpen = false }) {
                        com.opentune.ui.browse.ListOrder.entries.forEach { value ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(value.label) }, onClick = { order = value; sortOpen = false })
                        }
                    }
                }
                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Rounded.MoreHoriz, "More") }
            }
        }
        }
    }) {
    val tint = com.opentune.ui.browse.rememberCoverTint((playlist.coverUrl ?: songs.firstOrNull()?.thumbnailUrl).artworkAt(CARD_ART_PX))
    val band = com.opentune.ui.components.rememberRubberBand()
    LazyColumn(state = listState, contentPadding = if (searching) PaddingValues(bottom = 16.dp) else contentPadding,
        modifier = Modifier.fillMaxSize().coverWash(tint).playlistSearchResults(search), overscrollEffect = band) {
        if (!searching) item(key = "header") {
            Box {
                com.opentune.ui.browse.HeaderBackdrop((playlist.coverUrl ?: songs.firstOrNull()?.thumbnailUrl).artworkAt(HEADER_ART_PX), Modifier.matchParentSize().pinnedToTop(band), into = tint)
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    com.opentune.ui.browse.PlaylistHeading(playlist.name,
                        "On this phone",
                        playlist.coverUrl, songs, cover = if (playlist.coverUrl == null) ({
                            com.opentune.ui.components.PlaylistCover(playlist.name, songs.map { it.thumbnailUrl }, Modifier.fillMaxSize(), MaterialTheme.shapes.large)
                        }) else null)
                    com.opentune.ui.browse.ListPlayRow(shown.isNotEmpty(), false,
                        onPlay = { actions.playAll(shown, 0, false, playlist.name) },
                        onShuffle = { actions.playAll(shown, 0, true, playlist.name) },
                        onSmartShuffle = { actions.smartShuffle(shown, playlist.name) },
                        onSave = null,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp))
                    // The same row of tools as a YouTube Music playlist's page.
                    tools(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                }
            }
        }
        if (songs.isEmpty()) {
            item {
                MessageState(Icons.AutoMirrored.Rounded.QueueMusic, "Nothing here yet", Modifier.padding(top = 32.dp), message = "Add songs here, or use Add to playlist in any song's menu.")
            }
            return@LazyColumn
        }
        // Keyed by the song (and which copy of it), not its place, so a row taken out pops
        // alone and the rows below slide up rather than every row after it being redrawn.
        if (shown.isEmpty()) item { Text(com.opentune.ui.theme.tr("No matching songs"), Modifier.padding(24.dp)) }
        val keys = songKeys(songs)
        itemsIndexed(shown, key = { i, _ -> keys[matching[i]] }) { i, song ->
            SongListItem(
                song = song,
                onClick = { actions.playAll(shown, i, false, playlist.name) },
                modifier = Modifier.animateItem(),
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
                onRemoveFromPlaylist = { removeWithUndo(id, matching[i]) },
                // Swipe right to add to the queue, left to take it out of the playlist.
                onSwipeRemove = { removeWithUndo(id, matching[i]) },
            )
        }
    }
}
}

/** A playlist page's own menu: the list's cover and name, then what can be done with it. */
@Composable
private fun LocalListMoreSheet(
    playlist: com.opentune.data.library.LocalPlaylist,
    songs: List<com.opentune.data.model.Song>,
    actions: SongActions,
    onAddSongs: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pinned = AppSettings.ui.collectAsState().value.pinnedPlaylists.contains(playlist.id)
    com.opentune.ui.components.FloatingCard(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(0.dp),
        startExpanded = true,
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (playlist.coverUrl != null) Artwork(playlist.coverUrl.artworkAt(com.opentune.data.model.ROW_ART_PX), Modifier.size(56.dp))
                else com.opentune.ui.components.PlaylistCover(playlist.name, songs.map { it.thumbnailUrl }, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.opentune.ui.share.ListShare.countLine(songs.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        },
    ) {
        val card = com.opentune.ui.components.LocalFloatingCard.current
        val close: () -> Unit = { card?.close() ?: onDismiss() }
        Column {
            com.opentune.ui.components.MenuRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { close(); songs.asReversed().forEach(actions.playNext) }
            com.opentune.ui.components.MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { close(); songs.forEach(actions.addToQueue) }
            com.opentune.ui.components.MenuRow(Icons.Rounded.Add, "Add songs") { close(); onAddSongs() }
            com.opentune.ui.components.MenuRow(Icons.Rounded.Edit, "Rename") { close(); onRename() }
            com.opentune.ui.components.MenuRow(Icons.Rounded.Share, "Share") { close(); onShare() }
            com.opentune.ui.components.MenuRow(Icons.Rounded.PushPin, if (pinned) "Unpin from Library" else "Pin to the top of Library") { close(); LibraryPins.toggle(playlist.id) }
            com.opentune.ui.components.MenuRow(Icons.Rounded.DeleteOutline, "Delete") { close(); onDelete() }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A key for each of [songs] that stays with the song when others are taken out or put back. */
internal fun songKeys(songs: List<com.opentune.data.model.Song>): List<String> {
    val seen = HashMap<String, Int>()
    return songs.map { s ->
        val copy = seen.merge(s.videoId, 1, Int::plus)!!
        "${s.videoId}#$copy"
    }
}

/** Takes the song at [index] out of playlist [id], with a few seconds to Undo it. */
private fun removeWithUndo(id: String, index: Int) {
    val removed = LibraryStore.removeUndoably(id, index) ?: return
    com.opentune.data.Pills.withAction("Removed \"${removed.song.title}\"", "Undo") { LibraryStore.undoRemove(removed) }
}
