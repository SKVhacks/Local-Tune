package com.opentune.ui

import com.opentune.ui.tv.tvMediaKeys
import com.opentune.ui.tv.TvMediaAction

import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.EnterExitState
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.toSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.artworkAt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalOverscrollFactory
import com.opentune.ui.components.RubberBandOverscrollFactory
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import com.opentune.data.library.LibraryStore
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.LocalBackdrop
import com.opentune.ui.components.LocalHazeState
import com.opentune.ui.components.LocalSongMenu
import com.opentune.ui.components.SongMenuActions
import com.opentune.ui.library.LikedScreen
import com.opentune.ui.library.LocalPlaylistScreen
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.opentune.ui.library.ReplayScreen
import com.opentune.ui.wrapped.WrappedScreen
import com.opentune.ui.player.MiniPlayerBar
import com.opentune.ui.player.PlayerScreen
import com.opentune.ui.settings.EqualizerScreen
import com.opentune.ui.settings.SettingsScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** A tab tapped again while its root page is showing, for the page to scroll back to its top and refresh. */
object TabEvents {
    private val _reselected = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val reselected: kotlinx.coroutines.flow.SharedFlow<String> = _reselected
    fun reselect(route: String) { _reselected.tryEmit(route) }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("songs", "Songs", Icons.Outlined.MusicNote, Icons.Rounded.MusicNote),
    ALBUMS("albums", "Albums", Icons.Outlined.Album, Icons.Rounded.Album),
    ARTISTS("artists", "Artists", Icons.Outlined.Person, Icons.Rounded.Person),
    LIBRARY("library", "Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
}

private const val SEARCH_ROUTE = "search"


@Composable
fun AppRoot(vm: PlayerViewModel) {
    val nav = rememberNavController()
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val playbackError by vm.playbackError.collectAsState()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // The docked player folded into its strip, on screens wide enough for one.
    var paneCollapsed by rememberSaveable { mutableStateOf(false) }
    var playerCovers by remember { mutableStateOf(false) }
    val haze = rememberHazeState()
    val uiScope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    // Floating glass blurs a recording of the page (and bends it, with Liquid Glass on).
    val sampled = com.opentune.ui.components.backdropBlurOn()
    // Scrolling any page down folds the bottom bar into one row; scrolling up unfolds it.
    val chromeScroll = rememberChromeScroll()
    val chromeUi by com.opentune.data.settings.AppSettings.ui.collectAsState()
    val dockTabs = remember { Tab.entries.toList() }
    val chromeTabs = remember(dockTabs) { dockTabs.map { ChromeTab(it.label, it.icon, it.selectedIcon) } }
    // A page opened from inside the player (the artist, from a tap on their
    // name) folds the player away; back from that page opens the player
    // again, as it was left. Held: the page the player was over, and the page
    // it opened, by back stack entry.
    var playerReturn by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fromPlayer: (go: () -> Unit) -> Unit = remember(nav) {
        { go ->
            val wasOpen = playerOpen
            val below = nav.currentBackStackEntry?.id
            playerOpen = false
            go()
            val opened = nav.currentBackStackEntry?.id
            playerReturn = if (wasOpen && below != null && opened != null && opened != below) below to opened else null
        }
    }
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            val (below, opened) = playerReturn ?: return@collect
            if (opened in nav.stackIds()) return@collect
            playerReturn = null
            // Back to where the player was opened over: the player comes back too.
            if (entry.id == below && vm.currentSong.value != null) {
                if (!chromeUi.reduceAnimation) com.opentune.ui.components.PlayerMorph.openSheet()
                playerOpen = true
            }
        }
    }

    val appContext = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // After an update, once: what's changed.
    var whatsNew by remember { mutableStateOf<List<Pair<String, List<com.opentune.ui.components.WhatsNew.Note>>>>(emptyList()) }
    LaunchedEffect(Unit) { whatsNew = com.opentune.ui.components.WhatsNew.due(appContext, com.opentune.BuildConfig.VERSION_NAME) }
    if (whatsNew.isNotEmpty()) {
        com.opentune.ui.components.WhatsNewSheet(whatsNew, com.opentune.BuildConfig.VERSION_NAME, onDismiss = { whatsNew = emptyList() })
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(route) {
        Tab.entries.firstOrNull { it.route == route }?.let { tab = it }
        chromeScroll.expand()
    }
    LaunchedEffect(playbackError) { playbackError?.let { com.opentune.data.Pills.error(it) } }
    LaunchedEffect(Unit) { com.opentune.cast.Cast.messages.collect { com.opentune.data.Pills.info(it) } }
    // A newer pill cuts the one showing short, so a run of swipes doesn't queue up notes.
    LaunchedEffect(Unit) { com.opentune.ui.components.QueueNotices.messages.collect { com.opentune.data.Pills.done(it) } }
    LaunchedEffect(Unit) { com.opentune.playback.SmartShuffle.notices.collect { com.opentune.data.Pills.info(it) } }

    // Kept across recompositions (opening the player, play/pause): a new
    // instance each time would recompose every screen and list handed one.
    val actions = remember(song?.videoId, isPlaying) {
        SongActions(
            currentVideoId = song?.videoId,
            isPlaying = isPlaying,
            playAll = { songs, index, shuffle, source -> vm.playAll(songs, index, shuffle, source) },
            playNext = vm::playNext,
            addToQueue = vm::addToQueue,
            smartShuffle = { songs, source -> vm.smartShuffle(songs, source) },
        )
    }
    // `song` is read when a menu action runs, so the menu doesn't need rebuilding as songs change.
    val songMenu = remember(nav, vm) { SongMenuActions(
        playNext = vm::playNext,
        addToQueue = vm::addToQueue,
        startRadio = vm::startRadio,
        openAlbum = { id -> fromPlayer { nav.navigate("album/${Uri.encode(id)}") } },
        openArtist = { id -> fromPlayer { nav.navigate("artist/${Uri.encode(id)}") } },
        dislike = { s ->
            // The player skips it, and its later copies in the queue, itself.
            LibraryStore.dislike(s)
            com.opentune.data.Pills.done("You won't hear \"${s.title}\" in autoplay again")
        },
        notInterested = { s ->
            LibraryStore.hideSong(s)
            vm.player.dropHiddenSmartShuffleSongs()
            com.opentune.data.Pills.withAction("\"${s.title}\" won't be suggested", "Undo") { LibraryStore.unhideSong(s.videoId) }
        },
        dontPlayArtist = dontPlay@{ s ->
            val artist = LibraryStore.hideArtist(s) ?: return@dontPlay
            vm.player.dropHiddenSmartShuffleSongs()
            com.opentune.data.Pills.withAction("Songs by ${artist.name} won't be suggested", "Undo") { LibraryStore.unhideArtist(artist.key) }
        },
        keepInPlaylist = { s, id ->
            LibraryStore.addToPlaylist(id, s)
            com.opentune.playback.SmartShuffle.keep(s.videoId)
            LibraryStore.playlist(id)?.let { p -> com.opentune.data.Pills.done("Added to ${p.name}") }
        },
        progressOf = { s -> if (s.videoId == vm.currentSong.value?.videoId) vm.positionMs() to vm.durationMs.value else null },
        viewArtist = { s ->
            val id = s.artistId ?: com.opentune.data.local.LocalMusic.artistId(com.opentune.data.local.LocalMusic.splitArtists(s.artist).first())
            fromPlayer { nav.navigate("artist/${Uri.encode(id)}") }
        },
        viewAlbum = { s ->
            val id = s.albumId ?: com.opentune.data.local.LocalMusic.library.value?.track(s.videoId)?.song?.albumId
            if (id == null) com.opentune.data.Pills.error("Couldn't find the album for \"${s.title}\"")
            else fromPlayer { nav.navigate("album/${Uri.encode(id)}") }
        },
        viewArtistNamed = { _, name -> fromPlayer { nav.navigate("artist/${Uri.encode(com.opentune.data.local.LocalMusic.artistId(name))}") } },
    ) }
    val localNav = remember(nav) { com.opentune.ui.local.LocalNav(
        album = { id -> nav.navigate("album/${Uri.encode(id)}") },
        artist = { id -> nav.navigate("artist/${Uri.encode(id)}") },
        folder = { path -> nav.navigate("folder/${Uri.encode(path)}") },
        folders = { nav.navigate("folders") },
        search = { nav.navigate(SEARCH_ROUTE) { launchSingleTop = true } },
        settings = { nav.navigate("settings") { launchSingleTop = true } },
        liked = { nav.navigate("liked") },
        playlist = { id -> nav.navigate("playlist/${Uri.encode(id)}") },
        history = { nav.navigate("history") },
        replay = { nav.navigate("replay") },
    ) }
    // Handed in from outside: the assistant's "play …", an audio file opened with the app, the notification.
    val incoming by Links.incoming.collectAsState()
    LaunchedEffect(incoming) {
        when (val link = incoming) {
            is LinkTarget.PlaySearch -> {
                vm.player.connected.first { it }
                val lib = com.opentune.data.local.LocalMusic.current(appContext)
                val q = link.query.trim().lowercase()
                val songs = when {
                    q.isEmpty() -> lib.songs.shuffled()
                    else -> lib.artists.firstOrNull { it.name.lowercase() == q }?.songs?.shuffled()
                        ?: lib.albums.firstOrNull { it.title.lowercase() == q }?.songs
                        ?: lib.songs.filter { it.title.lowercase().contains(q) || "${it.title} ${it.artist}".lowercase().contains(q) }
                }
                if (songs.isNotEmpty()) { vm.playAll(songs, 0, false, link.query.ifBlank { "All songs" }); playerOpen = true }
                else com.opentune.data.Pills.error("Nothing on your phone matches \"${link.query}\"")
            }
            is LinkTarget.PlayFile -> {
                vm.player.connected.first { it }
                val lib = com.opentune.data.local.LocalMusic.current(appContext)
                // A file the library knows plays as itself, with its album around it.
                val id = runCatching { android.content.ContentUris.parseId(link.uri) }.getOrNull()
                    ?.takeIf { link.uri.authority == android.provider.MediaStore.AUTHORITY }
                val known = id?.let { lib.track("${com.opentune.data.local.LocalMusic.PREFIX}$it") }
                if (known != null) {
                    val album = known.song.albumId?.let(lib::album)?.songs.orEmpty()
                    val at = album.indexOfFirst { it.videoId == known.song.videoId }
                    if (at >= 0) vm.playAll(album, at, false, known.song.albumName ?: "Album") else vm.play(known.song, "Opened file")
                } else {
                    com.opentune.data.Pills.error("Only songs in your phone's music library can be played")
                }
                playerOpen = true
            }
            is LinkTarget.Player -> playerOpen = true
            null -> return@LaunchedEffect
        }
        Links.consumed()
    }
    // The Wrapped story fills the screen, like the login page.
    val chromeRoute = route != "login" && route != "wrapped"
    val chromeVisible = chromeRoute && !playerCovers
    // The window's size class picks the chrome. A fold or unfold only changes
    // what's around the pages: the NavHost and the player keep their place in
    // the tree, so neither starts over (the activity handles the size change
    // itself rather than being recreated; see the manifest).
    val window = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    val television = com.opentune.ui.tv.LocalIsTv.current
    val layout = appLayout(window.width.value.toInt(), window.height.value.toInt(), song != null, paneCollapsed, chromeUi.wideLayout, chromeUi.sidePlayer, television)
    // Docked beside the page, the player is never also opened over it.
    val sheetOpen = playerOpen && !layout.sidePlayer
    com.opentune.ui.theme.SystemBars(if (sheetOpen) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.background)
    val currentLayout by androidx.compose.runtime.rememberUpdatedState(layout)
    LaunchedEffect(playerOpen) {
        // Asked for the player (a shared song, say) while it's docked: unfold the pane.
        if (playerOpen && currentLayout.sidePlayer) {
            paneCollapsed = false
            playerOpen = false
        }
    }
    // Unfolding with the full player open: the pane shows it. Folding back
    // brings the full player back, as it was left.
    LaunchedEffect(layout.sidePlayer) { if (layout.sidePlayer && playerOpen) paneCollapsed = false }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val content = if (layout.nav == NavChrome.DOCK) {
        PaddingValues(bottom = navInset + CHROME_TAB_HEIGHT + 24.dp + if (song != null) CHROME_MINI_HEIGHT + 8.dp else 0.dp)
    } else {
        PaddingValues(bottom = navInset + 16.dp + if (layout.nowPlaying == NowPlaying.BAR) CHROME_MINI_HEIGHT + 12.dp else 0.dp)
    }
    // Room the rail and the docked player take beside the pages, eased as
    // the pane folds and unfolds.
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val startGutter = if (layout.nav == NavChrome.RAIL && chromeRoute) (if (television) com.opentune.ui.tv.TV_RAIL_GUTTER else RAIL_GUTTER) + safe.calculateStartPadding(direction) else 0.dp
    val endGutterTarget = when {
        !chromeRoute -> 0.dp
        layout.nowPlaying == NowPlaying.PANE -> PANE_GUTTER + safe.calculateEndPadding(direction)
        layout.nowPlaying == NowPlaying.STRIP -> STRIP_GUTTER + safe.calculateEndPadding(direction)
        else -> 0.dp
    }
    val endGutter by androidx.compose.animation.core.animateDpAsState(
        endGutterTarget,
        if (chromeUi.reduceAnimation) androidx.compose.animation.core.snap() else tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "endGutter",
    )
    // Pages in a readable column on wide windows, centred in the room left;
    // the ones that fill the screen still do.
    val pageArea = Modifier.padding(start = startGutter, end = endGutter)
        .then(layout.contentMaxWidthDp?.takeIf { chromeRoute }?.let { Modifier.widthIn(max = it.dp) } ?: Modifier)
    // The pages each tab had open above its root when it was left, as routes,
    // so coming back to the tab brings them back (a playlist opened from Home
    // is still there after a look at the Library).
    val tabPages = remember { mutableMapOf<Tab, List<String>>() }
    // The whole back stack is only public as this restricted, long-stable flow.
    @android.annotation.SuppressLint("RestrictedApi")
    fun pagesAbove(root: Tab): List<String> {
        val stack = nav.currentBackStack.value.filter { it.destination.route != null }
        val at = stack.indexOfLast { it.destination.route == root.route }
        if (at < 0) return emptyList()
        return stack.drop(at + 1).mapNotNull { e ->
            val template = e.destination.route ?: return@mapNotNull null
            template.replace(Regex("""\{([^}]+)\}""")) { m -> Uri.encode(e.arguments?.get(m.groupValues[1])?.toString().orEmpty()) }
        }
    }
    val selectTab: (Int) -> Unit = { i ->
        // A tab chosen is somewhere new, not a way back to the player.
        playerReturn = null
        val t = dockTabs[i]
        when {
            // On the tab's root already: Home scrolls to the top and refreshes.
            t == tab && route == t.route -> TabEvents.reselect(t.route)
            // A sub-page or Search opened from this tab: back to its root.
            t == tab -> { tabPages.remove(t); nav.popBackStack(t.route, inclusive = false) }
            else -> {
                tabPages[tab] = pagesAbove(tab)
                tab = t
                if (t == Tab.HOME) nav.popBackStack(Tab.HOME.route, inclusive = false)
                else nav.navigate(t.route) {
                    popUpTo(Tab.HOME.route)
                    launchSingleTop = true
                }
                // And the pages that were open in it, as they were left.
                tabPages.remove(t).orEmpty().forEach { r -> runCatching { nav.navigate(r) } }
            }
        }
    }
    val openSearch = { if (route != SEARCH_ROUTE) nav.navigate(SEARCH_ROUTE) { launchSingleTop = true } }

    // The opening animation, once per launch: the app comes forward out of
    // the mark as the screen dives into it.
    var opening by remember { mutableStateOf(!com.opentune.ui.components.Opening.played && chromeUi.openingAnimation && !chromeUi.reduceAnimation) }
    var dive by remember { androidx.compose.runtime.mutableFloatStateOf(if (opening) 0f else 1f) }
    LaunchedEffect(Unit) { com.opentune.ui.components.Opening.played = true }

    // Back on the first page while music plays: the app shrinks up into a pill at the
    // top, towards the notification's player, and goes to the background. Coming
    // back finds it full size again.
    val leave = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { leave.snapTo(0f) } }
    val atRoot = entry != null && nav.previousBackStackEntry == null
    BackHandler(enabled = isPlaying && atRoot && !sheetOpen && !chromeUi.reduceAnimation && leave.targetValue == 0f) {
        val activity = appContext as? android.app.Activity
        uiScope.launch {
            // Long enough to read as one gesture, on a curve that starts briskly and
            // glides into place, the way iOS sends an app home.
            leave.animateTo(1f, androidx.compose.animation.core.tween(560, easing = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0f, 1f)))
            if (activity == null || !activity.moveTaskToBack(true)) { leave.snapTo(0f); return@launch }
            // The shrink was the closing; skip the system's own on top of it where it allows.
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
        }
    }

    val handleTvMedia: (TvMediaAction) -> Unit = { command ->
                when (command) {
                    TvMediaAction.TOGGLE -> vm.togglePlayPause()
                    TvMediaAction.PLAY -> if (!vm.isPlaying.value) vm.togglePlayPause()
                    TvMediaAction.PAUSE, TvMediaAction.STOP -> if (vm.isPlaying.value) vm.togglePlayPause()
                    TvMediaAction.NEXT -> vm.skipNext()
                    TvMediaAction.PREVIOUS -> vm.skipPrevious()
                    TvMediaAction.FORWARD, TvMediaAction.REWIND -> {
                        val duration = vm.durationMs.value
                        if (duration > 0) vm.seekTo((vm.positionMs() + if (command == TvMediaAction.FORWARD) 10_000 else -10_000).coerceIn(0, duration))
                    }
                }
                }

    CompositionLocalProvider(
        LocalHazeState provides haze,
        LocalSongMenu provides songMenu,
        LocalChromeScroll provides chromeScroll,
        // iOS-style rubber band at the ends of every list.
        LocalOverscrollFactory provides RubberBandOverscrollFactory,
    ) {
        com.opentune.ui.components.ConnectionBanner(
            Modifier.fillMaxSize().tvMediaKeys(handleTvMedia).graphicsLayer {
                if (dive < 1f) {
                    val s = 1.14f - 0.14f * (1f - (1f - dive) * (1f - dive))
                    scaleX = s
                    scaleY = s
                }
                val p = leave.value
                if (p > 0f) {
                    // Shrinking toward the top centre, cropped down to a pill as it goes.
                    // The crop runs a little ahead of the shrink, so the rounded pill
                    // shape is there early and the rest is it settling into place.
                    val s = 1f - 0.68f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    translationY = 30.dp.toPx() * p
                    val crop = 1f - (1f - p) * (1f - p)
                    shape = com.opentune.ui.components.pillCrop(crop, 375.dp.toPx(), 100.dp.toPx())
                    clip = true
                    // Lifted off the screen as it goes, and softening into the pill.
                    shadowElevation = 28.dp.toPx() * p
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && p > 0.35f) {
                        val b = 10.dp.toPx() * ((p - 0.35f) / 0.65f)
                        renderEffect = androidx.compose.ui.graphics.BlurEffect(b, b)
                    }
                    alpha = if (p < 0.8f) 1f else 1f - (p - 0.8f) / 0.2f
                }
            },
            reduceMotion = chromeUi.reduceAnimation,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (sampled) Modifier.layerBackdrop(backdrop) else Modifier)
                    // Painted inside the recorded layer: a see-through backdrop would let
                    // the sharp page show through the glass instead of the blurred copy.
                    .background(MaterialTheme.colorScheme.background)
                    .hazeSource(haze)
                    .nestedScroll(chromeScroll),
            ) {
                // Glass inside the pages is part of the layer the chrome blurs, so it
                // can't blur that layer itself: it's filled, unless a page records
                // its own content for an overlay (GlassPage).
                CompositionLocalProvider(LocalHazeState provides null) {
                NavHost(
                    navController = nav,
                    startDestination = Tab.HOME.route,
                    modifier = Modifier.align(Alignment.TopCenter).then(pageArea).fillMaxSize(),
                    enterTransition = { PageMotion.enter(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                    exitTransition = { PageMotion.exit(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                    popEnterTransition = { PageMotion.popEnter(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                    popExitTransition = { PageMotion.popExit(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                ) {
                    page(Tab.HOME.route) { com.opentune.ui.local.LocalSongsScreen(content, actions, localNav) }
                    page(Tab.ALBUMS.route) { com.opentune.ui.local.LocalAlbumsScreen(content, localNav) }
                    page(Tab.ARTISTS.route) { com.opentune.ui.local.LocalArtistsScreen(content, localNav) }
                    page(Tab.LIBRARY.route) { com.opentune.ui.local.LibraryHub(content, actions, localNav) }
                    page(SEARCH_ROUTE) { com.opentune.ui.local.LocalSearchScreen(content, actions, localNav, onBack = { nav.popBackStack() }) }
                    page("album/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        com.opentune.ui.local.LocalAlbumScreen(e.arguments?.getString("id").orEmpty(), content, actions, localNav, onBack = { nav.popBackStack() })
                    }
                    page("artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        com.opentune.ui.local.LocalArtistScreen(e.arguments?.getString("id").orEmpty(), content, actions, localNav, onBack = { nav.popBackStack() })
                    }
                    page("folders") { com.opentune.ui.local.LocalFoldersScreen(content, localNav, onBack = { nav.popBackStack() }) }
                    page("folder/{path}", listOf(navArgument("path") { type = NavType.StringType })) { e ->
                        com.opentune.ui.local.LocalFolderScreen(e.arguments?.getString("path").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                    }
                    page("settings") {
                        SettingsScreen(
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            onOpenEqualizer = { nav.navigate("equalizer") },
                            onOpenReplay = { nav.navigate("replay") },
                            onOpenWrapped = { nav.navigate("wrapped") },
                        )
                    }
                    page("liked") { LikedScreen(content, actions, onBack = { nav.popBackStack() }) }
                    page("playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        LocalPlaylistScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                    }
                    page("equalizer") { EqualizerScreen(content, onBack = { nav.popBackStack() }) }
                    page("history") { com.opentune.ui.library.ListeningHistoryScreen(content, actions, onBack = { nav.popBackStack() }) }
                    page("replay") { ReplayScreen(content, actions, onBack = { nav.popBackStack() }, onOpenWrapped = { nav.navigate("wrapped") }) }
                    page("wrapped") { WrappedScreen(actions, onBack = { nav.popBackStack() }, player = vm.player) }
                }
                }
            }

            // Pages blur and fade as they pass under the status bar.
            if (route != "login") com.opentune.ui.components.TopEdgeFrost(Modifier.align(Alignment.TopCenter))

            // Floating chrome: the mini player above the nav pill and search button.
            // It sits outside the recorded layer, so its Liquid Glass can sample it.
            if (chromeVisible) CompositionLocalProvider(LocalBackdrop provides backdrop.takeIf { sampled }) {
            val openPlayer: (Boolean) -> Unit = { _ ->
                // The whole player grows out of the mini player, from wherever a pull left it.
                if (!chromeUi.reduceAnimation) com.opentune.ui.components.PlayerMorph.openSheet()
                playerOpen = true
            }
            if (layout.nav == NavChrome.DOCK) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                BottomChrome(
                    inline = chromeScroll.inline,
                    tabs = chromeTabs,
                    selected = if (route == SEARCH_ROUTE) null else dockTabs.indexOf(tab).takeIf { it >= 0 },
                    onSelect = selectTab,
                    onExpand = chromeScroll::expand,
                    searchSelected = route == SEARCH_ROUTE,
                    onSearch = openSearch,
                    mini = if (song == null) null else { folded, m ->
                        MiniPlayerBar(vm, onExpand = { openPlayer(folded) }, modifier = m, inline = folded)
                    },
                    motion = chromeUi.dockMotion,
                    lens = chromeUi.dockLens,
                    reduceMotion = chromeUi.reduceAnimation,
                )
            }
            } else {
                // Wide windows: the tabs in a rail down the start edge, and the mini
                // player along the bottom of the page unless the player is docked.
                if (television) com.opentune.ui.tv.TvNavRail(
                    tabs = chromeTabs,
                    selected = if (route == SEARCH_ROUTE) null else dockTabs.indexOf(tab).takeIf { it >= 0 },
                    onSelect = selectTab, onSearch = openSearch,
                    onSettings = { nav.navigate("settings") { launchSingleTop = true } },
                    onPlayer = if (song == null) null else ({ openPlayer(false) }),
                    modifier = Modifier.align(Alignment.CenterStart),
                ) else NavRail(
                    tabs = chromeTabs,
                    selected = if (route == SEARCH_ROUTE) null else dockTabs.indexOf(tab).takeIf { it >= 0 },
                    onSelect = selectTab,
                    searchSelected = route == SEARCH_ROUTE,
                    onSearch = openSearch,
                    modifier = Modifier.align(Alignment.CenterStart),
                    lens = chromeUi.dockLens,
                    reduceMotion = chromeUi.reduceAnimation,
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).then(pageArea).fillMaxWidth()
                        .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                        if (layout.nowPlaying == NowPlaying.BAR) {
                        MiniPlayerBar(vm, onExpand = { openPlayer(false) }, modifier = Modifier.fillMaxWidth().height(CHROME_MINI_HEIGHT))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = layout.nowPlaying == NowPlaying.STRIP,
                enter = fadeIn(tween(if (chromeUi.reduceAnimation) 120 else 260, delayMillis = if (chromeUi.reduceAnimation) 0 else 160)),
                exit = fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                PlayerStrip(vm, onExpand = { paneCollapsed = false })
            }
            }

            // Expanded windows: the player itself, docked on the end edge while a
            // song is loaded. It folds into the strip above, and back.
            androidx.compose.animation.AnimatedVisibility(
                visible = layout.nowPlaying == NowPlaying.PANE && chromeRoute,
                enter = if (chromeUi.reduceAnimation) fadeIn(tween(160)) else slideInHorizontally(tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))) { it } + fadeIn(tween(220)),
                exit = if (chromeUi.reduceAnimation) fadeOut(tween(120)) else slideOutHorizontally(tween(320, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { it } + fadeOut(tween(260)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                PlayerPaneFrame {
                    PlayerScreen(
                        vm,
                        onCollapse = { paneCollapsed = true },
                        onEqualizer = { nav.navigate("equalizer") },
                        docked = true,
                    )
                }
            }

            val sheetMorph = !chromeUi.reduceAnimation && !television
            androidx.compose.runtime.SideEffect { com.opentune.ui.components.PlayerMorph.sheetMorph = sheetMorph }
            // The player itself, whichever way it comes and goes.
            val playerSheet: @Composable (settled: Boolean) -> Unit = { settled ->
                // Registered after the NavHost's own handler, so back closes the
                // player first instead of popping the page behind it.
                BackHandler(enabled = sheetOpen) { playerOpen = false }
                // Once the player is fully over the page, the chrome under it is
                // taken out: no second position loop or glass redrawn behind it.
                var still by remember { mutableStateOf(true) }
                LaunchedEffect(settled, still) { playerCovers = settled && still }
                DisposableEffect(Unit) { onDispose { playerCovers = false } }
                PlayerScreen(
                    vm,
                    onCollapse = { playerOpen = false },
                    onCovering = { still = it },
                    onEqualizer = { fromPlayer { nav.navigate("equalizer") } },
                )
            }
            if (television) {
                if (sheetOpen && song != null) androidx.compose.ui.window.Dialog(
                    onDismissRequest = { playerOpen = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                ) {
                    // A separate focus window keeps D-pad movement inside the player and restores
                    // the launcher button when Back closes it. Playback remains in the same service.
                    Box(Modifier.fillMaxSize().tvMediaKeys(handleTvMedia)) { playerSheet(true) }
                }
            } else if (sheetMorph) {
                // Out of the mini player as one card, and back into it.
                var sheetSettled by remember { mutableStateOf(false) }
                com.opentune.ui.components.PlayerSheet(
                    open = sheetOpen && song != null,
                    onSettled = { sheetSettled = it },
                    face = { round -> MiniPlayerBar(vm, onExpand = {}, modifier = Modifier.fillMaxSize(), inline = round, replica = true) },
                ) {
                    playerSheet(sheetSettled)
                }
            } else {
                AnimatedVisibility(
                    visible = sheetOpen && song != null,
                    enter = PageMotion.playerIn(chromeUi.playerMotion, chromeUi.reduceAnimation),
                    exit = PageMotion.playerOut(chromeUi.playerMotion, chromeUi.reduceAnimation),
                ) {
                    playerSheet(transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible)
                }
            }
            // Notes about what was just done float in over everything, the player included.
            val notificationTheme by com.opentune.data.settings.AppSettings.theme.collectAsState()
            // Keep one host alive across player transitions, but use the visible screen's palette.
            com.opentune.ui.theme.PlayerTheme(
                seed = com.opentune.ui.theme.rememberArtworkSeed(song?.thumbnailUrl),
                settings = notificationTheme,
                neutral = chromeUi.playerStyle == com.opentune.data.settings.PlayerStyle.APPLE_MUSIC,
                active = sheetOpen && song != null,
            ) {
                com.opentune.ui.components.PillHost(chromeUi.reduceAnimation, Modifier.align(Alignment.TopCenter))
            }
        }
        if (opening) {
            com.opentune.ui.components.OpeningReveal(onDive = { dive = it }, onDone = { opening = false; dive = 1f })
        }
    }
}

/** [NowPlayingStrip] following the player. */
@Composable
private fun PlayerStrip(vm: PlayerViewModel, onExpand: () -> Unit) {
    val isPlaying by vm.isPlaying.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    NowPlayingStrip(
        isPlaying = isPlaying,
        hasNext = hasNext,
        onTogglePlay = vm::togglePlayPause,
        onNext = vm::skipNext,
        onExpand = onExpand,
    ) { m -> MiniPlayerBar(vm, onExpand = onExpand, modifier = m, inline = true) }
}

/** The ids of the entries on the back stack, top last. The whole stack is only public as this restricted, long-stable flow. */
@android.annotation.SuppressLint("RestrictedApi")
private fun NavHostController.stackIds(): List<String> = currentBackStack.value.map { it.id }

/** A destination of the NavHost; on a TV it keeps the remote's place when it's come back to (see TvPage). */
private fun androidx.navigation.NavGraphBuilder.page(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry -> com.opentune.ui.tv.TvPage { content(entry) } }
