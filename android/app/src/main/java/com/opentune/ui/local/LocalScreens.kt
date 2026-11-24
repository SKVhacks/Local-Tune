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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
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
import androidx.compose.material.icons.rounded.AutoAwesome
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
    /** Every artist, and the full library page (playlists and all): the Home page's "See all". */
    val artists: () -> Unit = {},
    val library: () -> Unit = {},
    val albums: () -> Unit = {},
)

/** The library as it is now; asks for the audio permission first when it hasn't been given. */
@Composable
private fun rememberLibrary(): Pair<Boolean, LocalMusic.Library?> {
    val context = LocalContext.current
    val library by LocalMusic.library.collectAsState()
    // Read again on every change: granting access from the prompt fills the library, which redraws this.
    val granted = library != null || LocalMusic.hasPermission(context)
    LaunchedEffect(granted) { if (granted && library == null) LocalMusic.rescan(0) }
    return granted to library
}

/** "Allow access" in place of a page, until the app may read the phone's music. */
@Composable
private fun PermissionPrompt(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    // Refused for good (Android stops showing its dialog): open the app's settings page instead.
    val openSettings = {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) LocalMusic.rescan(0)
        else if (activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, LocalMusic.permission)) openSettings()
    }
    // Comes back from the settings page with access given: read the music.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (LocalMusic.hasPermission(context)) LocalMusic.rescan(0)
        onPauseOrDispose { }
    }
    MessageState(
        Icons.Rounded.LibraryMusic,
        "Play the music on your phone",
        modifier.padding(top = 32.dp),
        message = "LocalTune needs permission to read your audio files. Nothing leaves your device.",
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

/** Liked songs as a playlist tile: a white heart on a warm gradient, not a song's cover. */
@Composable
private fun LikedTile(count: Int, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clip12().clickable(onClick = onClick).padding(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFFFF5E7E), androidx.compose.ui.graphics.Color(0xFFFF9A3C)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Favorite, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.fillMaxSize(0.42f))
        }
        Text("Liked songs", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (count == 1) "1 song" else "$count songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
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
        // Below the songs, as Apple Music does: "More by" each of the album's artists,
        // with their other albums on this phone. Left out when there are none.
        val names = if (album.artist == "Various artists") emptyList() else LocalMusic.splitArtists(album.artist)
        names.forEach { name ->
            val artist = library.artist(LocalMusic.artistId(name)) ?: return@forEach
            val others = artist.albums.filter { it.id != album.id }
            if (others.isEmpty()) return@forEach
            item(key = "more-${artist.id}") {
                Shelf("More by ${artist.name}", onSeeAll = { nav.artist(artist.id) }) {
                    items(others, key = { it.id }) { a ->
                        CoverTile(a.title, a.year.takeIf { it > 0 }?.toString() ?: a.artist, a.art, Icons.Rounded.Album, width = SHELF_TILE) { nav.album(a.id) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
                    art = rememberArtistArt(artist),
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
    // The picture runs from the very top of the screen, under the status bar.
    val padding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
    LazyColumn(contentPadding = if (artist != null) padding else contentPadding, modifier = Modifier.fillMaxSize()) {
        item { if (artist != null) ArtistHero(artist, onBack) else PageHeader("", onBack = onBack) }
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
                        LinkRow(a.name, "${a.tracks.size} songs", rememberArtistArt(a), Icons.Rounded.Person, round = true) { nav.artist(a.id) }
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

// ---- Home -------------------------------------------------------------------------

/**
 * The Home page: a greeting with the listener's name, then a row of each part
 * of the library, side-scrolling, each with a "See all": recently played,
 * artists, playlists, liked songs, albums and folders; and a way into Replay.
 * Rows with nothing in them are left out (Playlists keeps its "New playlist").
 */
@Composable
fun LocalHomeScreen(contentPadding: PaddingValues, actions: SongActions, nav: LocalNav) {
    val (allowed, library) = rememberLibrary()
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    val records by com.opentune.data.history.History.records.collectAsState()
    val likedRefs by LibraryStore.liked.collectAsState()
    // Saved songs as they're on the phone now: relinked if Android renumbered them, left out if gone.
    val liked = remember(likedRefs, library) { likedRefs.mapNotNull { com.opentune.data.local.LocalMusic.resolve(it.toSong()) } }
    val playlists by LibraryStore.playlists.collectAsState()
    val recents = remember(records, library) {
        com.opentune.data.history.History.recents(records, limit = 40).mapNotNull { com.opentune.data.local.LocalMusic.resolve(it) }.distinctBy { it.videoId }.take(20)
    }
    val artists = remember(library) { library?.artists.orEmpty().sortedByDescending { it.tracks.size }.take(20) }
    // Both picked once each time the app starts, and kept until it's closed: see HomePicks.
    val albums = remember(library) { library?.let(HomePicks::albums).orEmpty() }
    val suggested = remember(library) { library?.let(HomePicks::songs).orEmpty() }
    val folders = remember(library) { library?.folders.orEmpty().sortedByDescending { it.tracks.size }.take(20) }
    var naming by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    if (naming) com.opentune.ui.library.NameDialog("Your name", ui.yourName, onDismiss = { naming = false }) { name ->
        naming = false
        com.opentune.data.settings.AppSettings.updateUi { it.copy(yourName = name.trim()) }
    }
    if (creating) com.opentune.ui.library.NameDialog("New playlist", "", onDismiss = { creating = false }) { name ->
        creating = false
        nav.playlist(LibraryStore.createPlaylist(name))
    }
    if (!allowed) { PermissionPrompt(Modifier.padding(contentPadding)); return }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { Greeting(ui.yourName) { naming = true } }
        if (recents.isNotEmpty()) item {
            Shelf("Recently played", onSeeAll = nav.history) {
                itemsIndexed(recents, key = { i, s -> "r$i${s.videoId}" }) { i, song ->
                    CoverTile(song.title, song.artist, song.thumbnailUrl, Icons.Rounded.History, width = SHELF_TILE) {
                        // The song, then songs that go with it, rather than the rest of the history.
                        val next = library?.let { HomePicks.after(it, song) }.orEmpty()
                        actions.playAll(listOf(song) + next, 0, false, song.title)
                    }
                }
            }
        }
        if (suggested.isNotEmpty()) item {
            Shelf("Recommended songs for you", onSeeAll = null) {
                itemsIndexed(suggested, key = { _, s -> "s${s.videoId}" }) { i, song ->
                    CoverTile(song.title, song.artist, song.thumbnailUrl, Icons.Rounded.AutoAwesome, width = SHELF_TILE) {
                        actions.playAll(suggested, i, false, "Recommended songs for you")
                    }
                }
            }
        }
        if (albums.isNotEmpty()) item {
            Shelf("Suggested albums", onSeeAll = nav.albums) {
                items(albums, key = { it.id }) { a ->
                    CoverTile(a.title, a.artist, a.art, Icons.Rounded.Album, width = SHELF_TILE) { nav.album(a.id) }
                }
            }
        }
        if (artists.isNotEmpty()) item {
            Shelf("Artists", onSeeAll = nav.artists) {
                items(artists, key = { it.id }) { a ->
                    CoverTile(a.name, "${a.tracks.size} songs", rememberArtistArt(a), Icons.Rounded.Person, round = true, width = SHELF_TILE) { nav.artist(a.id) }
                }
            }
        }
        item {
            Shelf("Playlists", onSeeAll = nav.library.takeIf { playlists.isNotEmpty() || liked.isNotEmpty() }) {
                // Liked songs is the first playlist, as in Apple Music.
                item(key = "liked") {
                    LikedTile(liked.size, width = SHELF_TILE) { nav.liked() }
                }
                items(playlists, key = { it.id }) { p ->
                    CoverTile(p.name, "${p.songs.size} songs", p.songs.firstOrNull()?.thumbnailUrl, Icons.AutoMirrored.Rounded.QueueMusic, width = SHELF_TILE) { nav.playlist(p.id) }
                }
                item(key = "new") {
                    CoverTile("New playlist", "", null, Icons.Rounded.Add, width = SHELF_TILE) { creating = true }
                }
            }
        }
        if (folders.isNotEmpty()) item {
            Shelf("Folders", onSeeAll = nav.folders) {
                items(folders, key = { it.path }) { f ->
                    CoverTile(f.name, "${f.tracks.size} songs", f.tracks.firstOrNull()?.song?.thumbnailUrl, Icons.Rounded.Folder, width = SHELF_TILE) { nav.folder(f.path) }
                }
            }
        }
        item { ReplayCard(nav.replay) }
    }
}

/** "Good evening" and the listener's name, or a nudge to add it; a tap changes it. */
@Composable
private fun Greeting(name: String, onName: () -> Unit) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val hello = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
            .clip12()
            .clickable(onClick = onName),
    ) {
        Text(hello, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            name.ifBlank { "Tap to add your name" },
            style = MaterialTheme.typography.headlineSmall,
            color = if (name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A titled row of tiles that scrolls sideways, with "See all" when there's a page of them. */
@Composable
private fun Shelf(title: String, onSeeAll: (() -> Unit)?, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onSeeAll != null) androidx.compose.material3.TextButton(onClick = onSeeAll) { Text("See all") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/** The way into Replay: what's been played most. */
@Composable
private fun ReplayCard(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(20.dp))
            .then(Modifier.background(MaterialTheme.colorScheme.secondaryContainer))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.BarChart, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("Replay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("Your most played songs, artists and albums", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * Home's picks, made once each time the app starts and kept until it's closed,
 * so they don't change while it's being used: 15 albums at random, and the
 * songs Recommender suggests from what's been played. A library that changes
 * (songs added or removed) gets new picks.
 */
private object HomePicks {
    private val seed = System.currentTimeMillis()
    private var albumsFor: Int? = null
    private var albumPick: List<com.opentune.data.local.LocalMusic.Album> = emptyList()
    private var songsFor: Int? = null
    private var songPick: List<com.opentune.data.model.Song> = emptyList()

    @Synchronized
    fun albums(library: com.opentune.data.local.LocalMusic.Library): List<com.opentune.data.local.LocalMusic.Album> {
        val ids = library.albums.map { it.id }.toSet()
        if (albumsFor != ids.hashCode()) {
            albumsFor = ids.hashCode()
            albumPick = library.albums.shuffled(kotlin.random.Random(seed)).take(15)
        }
        return albumPick
    }

    @Synchronized
    fun songs(library: com.opentune.data.local.LocalMusic.Library): List<com.opentune.data.model.Song> {
        val key = library.tracks.size
        if (songsFor != key) {
            songsFor = key
            songPick = pick(library, null, 20)
        }
        return songPick
    }

    /** What plays after [song] when it's started from Recently played: songs that go with it. */
    fun after(library: com.opentune.data.local.LocalMusic.Library, song: com.opentune.data.model.Song): List<com.opentune.data.model.Song> =
        pick(library, song.videoId, 30, seed = System.nanoTime())

    private fun pick(library: com.opentune.data.local.LocalMusic.Library, after: String?, count: Int, seed: Long = this.seed): List<com.opentune.data.model.Song> {
        val candidates = library.tracks.filter { it.isMusic || it.song.videoId == after }.map { t ->
            com.opentune.data.recommend.Recommender.Candidate(
                t.song.videoId, com.opentune.data.local.LocalMusic.splitArtists(t.song.artist), t.song.albumName, t.folder, t.durationMs,
            )
        }
        val listens = com.opentune.data.history.History.records.value.map { r ->
            val track = library.track(r.videoId)
            com.opentune.data.recommend.Recommender.Listen(
                r.videoId, com.opentune.data.local.LocalMusic.splitArtists(r.artist), r.albumName, r.playedAt, r.listenedMs, track?.durationMs ?: 0L,
            )
        }
        val liked = LibraryStore.liked.value.map { it.videoId }.toSet()
        val ids = com.opentune.data.recommend.Recommender.recommend(candidates, listens, liked, System.currentTimeMillis(), seed, count = count, after = after)
        return ids.mapNotNull { library.track(it)?.song }
    }
}

/** The width of a tile in a Home row. */
private val SHELF_TILE = 148.dp

/**
 * The artist's own picture from inside their songs' tags (ArtistImages), or
 * their cover art until one's found, and when there's none.
 */
@Composable
private fun rememberArtistArt(artist: com.opentune.data.local.LocalMusic.Artist): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val known = remember(artist.id, artist.tracks.size) { com.opentune.data.local.ArtistImages.known(context, artist) }
    val art by androidx.compose.runtime.produceState(known?.ifEmpty { null } ?: artist.art, artist.id, artist.tracks.size) {
        if (known == null) com.opentune.data.local.ArtistImages.imageFor(context, artist)?.let { value = it }
    }
    return art
}

/**
 * The top of an artist's page, as Apple Music has it: their picture across the
 * top 40% of the screen, its lower part blurred and fading into the page, with
 * the name over that part and the back button on the picture.
 */
@Composable
private fun ArtistHero(artist: com.opentune.data.local.LocalMusic.Artist, onBack: () -> Unit) {
    val art = rememberArtistArt(artist)
    val height = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.4f).dp
    val page = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(height)) {
        Artwork(art, Modifier.matchParentSize(), shape = androidx.compose.ui.graphics.RectangleShape, placeholder = Icons.Rounded.Person)
        // The same picture blurred, showing only toward the bottom: the name sits on soft colour.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) Artwork(
            art,
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(0.45f to androidx.compose.ui.graphics.Color.Transparent, 0.75f to androidx.compose.ui.graphics.Color.Black),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
            shape = androidx.compose.ui.graphics.RectangleShape,
            placeholder = Icons.Rounded.Person,
            imageModifier = Modifier.blur(30.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle),
        )
        // Into the page's own colour at the very bottom, and a light shade at the top for the status bar.
        Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.55f to androidx.compose.ui.graphics.Color.Transparent, 1f to page)))
        Box(Modifier.fillMaxWidth().height(120.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), 1f to androidx.compose.ui.graphics.Color.Transparent)))
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 8.dp)) {
            com.opentune.ui.components.GlassBackButton(onBack)
        }
        Text(
            artist.name,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
    }
}
