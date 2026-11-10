package com.opentune.ui.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.library.LibraryStore
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song
import com.opentune.ui.browse.ListPlayRow
import com.opentune.ui.browse.PlaylistHeading
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import java.util.Locale

/** Where the library's pages lead. */
class LocalNav(
    val album: (String) -> Unit,
    val artist: (String) -> Unit,
    val folder: (String) -> Unit,
    val folders: () -> Unit,
    val search: () -> Unit,
    val settings: () -> Unit,
    val liked: () -> Unit,
    val playlist: (String) -> Unit,
    val history: () -> Unit,
    val replay: () -> Unit,
)

/** The library as it is now; asks for the audio permission first when it hasn't been given. */
@Composable
private fun rememberLibrary(): Pair<Boolean, LocalMusic.Library?> {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalMusic.hasPermission(context)) }
    val library by LocalMusic.library.collectAsState()
    LaunchedEffect(granted) { if (granted) LocalMusic.rescan(0) }
    return granted to library
}

/** "Allow access" in place of a page, until the app may read the phone's music. */
@Composable
private fun PermissionPrompt(modifier: Modifier = Modifier) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) LocalMusic.rescan(0) }
    MessageState(
        Icons.Rounded.LibraryMusic,
        "Play the music on your phone",
        modifier.padding(top = 32.dp),
        message = "OpenTune needs permission to read your audio files. Nothing leaves your device.",
        action = { Button(onClick = { launcher.launch(LocalMusic.permission) }) { Text("Allow access") } },
    )
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    MessageState(
        Icons.Rounded.Folder, "No music found", modifier.padding(top = 32.dp),
        message = "Copy songs to your phone's Music folder, or check the folder and filter in Settings › Local music.",
    )
}

/** The buttons every root page shows at its top: Search and Settings. */
@Composable
private fun RootActions(nav: LocalNav) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassIconButton(Icons.Rounded.Search, "Search", nav.search, size = 44.dp)
        GlassIconButton(Icons.Rounded.Settings, "Settings", nav.settings, size = 44.dp)
    }
}

private enum class SongSort(val label: String) { TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), ADDED("Recently added") }

// ---- Songs ------------------------------------------------------------------------

@Composable
fun LocalSongsScreen(contentPadding: PaddingValues, actions: SongActions, nav: LocalNav) {
    val (granted, library) = rememberLibrary()
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    val songs = remember(library, sort) {
        val tracks = library?.tracks.orEmpty()
        when (sort) {
            SongSort.TITLE -> tracks
            SongSort.ARTIST -> tracks.sortedWith(compareBy<LocalMusic.Track>({ it.song.artist.lowercase(Locale.ROOT) }, { it.song.albumName?.lowercase(Locale.ROOT) }, { it.trackNumber }))
            SongSort.ALBUM -> tracks.sortedWith(compareBy<LocalMusic.Track>({ it.song.albumName?.lowercase(Locale.ROOT) }, { it.trackNumber }))
            SongSort.ADDED -> tracks.sortedByDescending { it.dateAdded }
        }.map { it.song }
    }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Songs", actions = { RootActions(nav) }) }
        when {
            !granted -> item { PermissionPrompt() }
            library == null -> items(8) { SongRowPlaceholder() }
            songs.isEmpty() -> item { EmptyLibrary() }
            else -> {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        items(SongSort.entries.toList()) { s ->
                            FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
                        }
                    }
                    ListPlayRow(
                        enabled = true, saved = false,
                        onPlay = { actions.playAll(songs, 0, false, "Songs") },
                        onShuffle = { actions.playAll(songs, 0, true, "Songs") },
                        onSmartShuffle = { actions.smartShuffle(songs, "Songs") },
                        onSave = null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    Text(
                        "${songs.size} songs",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                itemsIndexed(songs, key = { _, s -> s.videoId }) { i, song ->
                    SongRow(song, actions) { actions.playAll(songs, i, false, "Songs") }
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, actions: SongActions, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    SongListItem(
        song = song,
        onClick = onClick,
        isCurrent = song.videoId == actions.currentVideoId,
        isPlaying = actions.isPlaying,
        leading = leading,
        onPlayNext = { actions.playNext(song) },
        onAddToQueue = { actions.addToQueue(song) },
    )
}

// ---- Albums -----------------------------------------------------------------------

@Composable
fun LocalAlbumsScreen(contentPadding: PaddingValues, nav: LocalNav) {
    val (granted, library) = rememberLibrary()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp,
            top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageHeader("Albums", actions = { RootActions(nav) }) }
        when {
            !granted -> item(span = { GridItemSpan(maxLineSpan) }) { PermissionPrompt() }
            library == null -> {}
            library!!.albums.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary() }
            else -> items(library!!.albums, key = { it.id }) { album ->
                CoverTile(album.title, album.artist, album.art, Icons.Rounded.Album, onClick = { nav.album(album.id) })
            }
        }
    }
}

@Composable
private fun CoverTile(title: String, subtitle: String, art: String?, placeholder: ImageVector, round: Boolean = false, width: androidx.compose.ui.unit.Dp? = null, onClick: () -> Unit) {
    Column(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip12()
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Artwork(
            art,
            Modifier.fillMaxWidth().aspectRatio(1f),
            shape = if (round) CircleShape else MaterialTheme.shapes.medium,
            placeholder = placeholder,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle.isNotBlank()) Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.clip12() = this.then(Modifier.clip(RoundedCornerShape(12.dp)))

@Composable
fun LocalAlbumScreen(id: String, contentPadding: PaddingValues, actions: SongActions, nav: LocalNav, onBack: () -> Unit) {
    val (_, library) = rememberLibrary()
    val album = library?.album(id)
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("", onBack = onBack) }
        if (album == null) {
            if (library != null) item { MessageState(Icons.Rounded.Album, "This album isn't on the phone any more", Modifier.padding(top = 32.dp)) }
            return@LazyColumn
        }
        val songs = album.songs
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlaylistHeading(album.title, "", album.art, songs, isAlbum = true)
                Text(
                    album.artist + (album.year.takeIf { it > 0 }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp).clip12().clickable { nav.artist(LocalMusic.artistId(LocalMusic.splitArtists(album.artist).first())) }.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            ListPlayRow(
                enabled = true, saved = false,
                onPlay = { actions.playAll(songs, 0, false, album.title) },
                onShuffle = { actions.playAll(songs, 0, true, album.title) },
                onSmartShuffle = { actions.smartShuffle(songs, album.title) },
                onSave = null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        itemsIndexed(album.tracks, key = { _, t -> t.song.videoId }) { i, track ->
            val number = track.trackNumber % 1000
            SongRow(track.song, actions, leading = {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (number > 0) number.toString() else "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }) { actions.playAll(songs, i, false, album.title) }
        }
    }
}

// ---- Artists ----------------------------------------------------------------------

@Composable
fun LocalArtistsScreen(contentPadding: PaddingValues, nav: LocalNav) {
    val (granted, library) = rememberLibrary()
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Artists", actions = { RootActions(nav) }) }
        when {
            !granted -> item { PermissionPrompt() }
            library == null -> items(8) { SongRowPlaceholder() }
            library.artists.isEmpty() -> item { EmptyLibrary() }
            else -> items(library.artists, key = { it.id }) { artist ->
                LinkRow(
                    title = artist.name,
                    subtitle = listOfNotNull(
                        "${artist.tracks.size} songs",
                        artist.albums.size.takeIf { it > 0 }?.let { "$it albums" },
                    ).joinToString(" · "),
                    art = artist.art,
                    icon = Icons.Rounded.Person,
                    round = true,
                    onClick = { nav.artist(artist.id) },
                )
            }
        }
    }
}

@Composable
fun LocalArtistScreen(id: String, contentPadding: PaddingValues, actions: SongActions, nav: LocalNav, onBack: () -> Unit) {
    val (_, library) = rememberLibrary()
    val artist = library?.artist(id)
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(artist?.name.orEmpty(), onBack = onBack) }
        if (artist == null) {
            if (library != null) item { MessageState(Icons.Rounded.Person, "No songs by this artist on the phone", Modifier.padding(top = 32.dp)) }
            return@LazyColumn
        }
        val songs = artist.songs
        item {
            ListPlayRow(
                enabled = true, saved = false,
                onPlay = { actions.playAll(songs, 0, false, artist.name) },
                onShuffle = { actions.playAll(songs, 0, true, artist.name) },
                onSmartShuffle = { actions.smartShuffle(songs, artist.name) },
                onSave = null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        if (artist.albums.isNotEmpty()) {
            item {
                SectionHeader("Albums")
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    items(artist.albums, key = { it.id }) { a ->
                        CoverTile(a.title, a.year.takeIf { it > 0 }?.toString() ?: a.artist, a.art, Icons.Rounded.Album, width = 156.dp) { nav.album(a.id) }
                    }
                }
            }
        }
        item { SectionHeader("Songs", subtitle = "${songs.size} songs") }
        itemsIndexed(songs, key = { _, s -> s.videoId }) { i, song ->
            SongRow(song, actions) { actions.playAll(songs, i, false, artist.name) }
        }
    }
}

// ---- Folders ----------------------------------------------------------------------

@Composable
fun LocalFoldersScreen(contentPadding: PaddingValues, nav: LocalNav, onBack: () -> Unit) {
    val (granted, library) = rememberLibrary()
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Folders", onBack = onBack) }
        when {
            !granted -> item { PermissionPrompt() }
            library == null -> items(6) { SongRowPlaceholder() }
            library.folders.isEmpty() -> item { EmptyLibrary() }
            else -> items(library.folders, key = { it.path }) { f ->
                LinkRow(f.name, "${f.tracks.size} songs · ${f.path.trimEnd('/')}", null, Icons.Rounded.Folder) { nav.folder(f.path) }
            }
        }
    }
}

@Composable
fun LocalFolderScreen(path: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val (_, library) = rememberLibrary()
    val folder = library?.folder(path)
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(folder?.name ?: path.trimEnd('/').substringAfterLast('/'), onBack = onBack) }
        val songs = folder?.songs.orEmpty()
        if (songs.isEmpty()) {
            if (library != null) item { EmptyLibrary() }
            return@LazyColumn
        }
        item {
            ListPlayRow(
                enabled = true, saved = false,
                onPlay = { actions.playAll(songs, 0, false, folder!!.name) },
                onShuffle = { actions.playAll(songs, 0, true, folder!!.name) },
                onSmartShuffle = { actions.smartShuffle(songs, folder!!.name) },
                onSave = null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        itemsIndexed(songs, key = { _, s -> s.videoId }) { i, song ->
            SongRow(song, actions) { actions.playAll(songs, i, false, folder!!.name) }
        }
    }
}

// ---- Search -----------------------------------------------------------------------

@Composable
fun LocalSearchScreen(contentPadding: PaddingValues, actions: SongActions, nav: LocalNav, onBack: () -> Unit) {
    val (granted, library) = rememberLibrary()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = query.trim().lowercase(Locale.ROOT)
    val found = remember(library, q) {
        if (library == null || q.isEmpty()) null else {
            fun String?.has() = this?.lowercase(Locale.ROOT)?.contains(q) == true
            Triple(
                library.artists.filter { it.name.has() }.take(12),
                library.albums.filter { it.title.has() || it.artist.has() }.take(20),
                library.songs.filter { it.title.has() || it.artist.has() || it.albumName.has() },
            )
        }
    }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 8.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.opentune.ui.components.GlassBackButton(onBack)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Songs, albums, artists") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Clear") } },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        }
        when {
            !granted -> item { PermissionPrompt() }
            found == null -> {}
            found.first.isEmpty() && found.second.isEmpty() && found.third.isEmpty() -> item {
                MessageState(Icons.Rounded.Search, "Nothing matches \"$query\"", Modifier.padding(top = 32.dp))
            }
            else -> {
                val (artists, albums, songs) = found
                if (artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(artists, key = { "a:" + it.id }) { a ->
                        LinkRow(a.name, "${a.tracks.size} songs", a.art, Icons.Rounded.Person, round = true) { nav.artist(a.id) }
                    }
                }
                if (albums.isNotEmpty()) {
                    item {
                        SectionHeader("Albums")
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                            items(albums, key = { it.id }) { a ->
                                CoverTile(a.title, a.artist, a.art, Icons.Rounded.Album, width = 140.dp) { nav.album(a.id) }
                            }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { SectionHeader("Songs", subtitle = "${songs.size} songs") }
                    itemsIndexed(songs.take(200), key = { _, s -> "s:" + s.videoId }) { i, song ->
                        SongRow(song, actions) { actions.playAll(songs, i, false, "Search") }
                    }
                }
            }
        }
    }
}

// ---- Library ----------------------------------------------------------------------

@Composable
fun LibraryHub(contentPadding: PaddingValues, actions: SongActions, nav: LocalNav) {
    val (_, library) = rememberLibrary()
    val liked by LibraryStore.liked.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) com.opentune.ui.library.NameDialog("New playlist", "", onDismiss = { creating = false }) { name ->
        creating = false
        nav.playlist(LibraryStore.createPlaylist(name))
    }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Library", actions = { RootActions(nav) }) }
        item {
            val all = library?.songs.orEmpty()
            if (all.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Button(onClick = { actions.playAll(all, 0, true, "All songs") }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.Shuffle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle all ${all.size} songs")
                }
            }
        }
        item {
            LinkRow("Liked songs", "${liked.size} songs", liked.firstOrNull()?.thumbnailUrl, Icons.Rounded.Favorite, onClick = nav.liked)
            LinkRow("Folders", "${library?.folders?.size ?: 0} folders", null, Icons.Rounded.Folder, onClick = nav.folders)
            LinkRow("Recently played", "Your listening history", null, Icons.Rounded.History, onClick = nav.history)
            LinkRow("Replay", "What you've played most", null, Icons.Rounded.BarChart, onClick = nav.replay)
        }
        item {
            SectionHeader("Playlists", action = {
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist") }
            })
        }
        if (playlists.isEmpty()) item {
            LinkRow("New playlist", "Make one from songs on your phone", null, Icons.Rounded.Add) { creating = true }
        }
        items(playlists, key = { it.id }) { p ->
            LinkRow(p.name, "${p.songs.size} songs", p.songs.firstOrNull()?.thumbnailUrl, Icons.AutoMirrored.Rounded.QueueMusic) { nav.playlist(p.id) }
        }
    }
}

/** A row that opens a page: a picture (or icon), a title and a line under it. */
@Composable
private fun LinkRow(title: String, subtitle: String, art: String?, icon: ImageVector, round: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, Modifier.size(56.dp), shape = if (round) CircleShape else MaterialTheme.shapes.small, placeholder = icon)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
