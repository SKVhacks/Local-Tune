package com.opentune.ui.settings

import android.content.Context
import com.opentune.ui.components.launchSafely
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lens
import androidx.compose.material.icons.rounded.LineWeight
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.SingletonImageLoader
import com.opentune.BuildConfig
import com.opentune.data.LogExport
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.local.LocalMusic
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.settings.ControlStyle
import com.opentune.data.settings.CoverChange
import com.opentune.data.settings.DockLens
import com.opentune.data.settings.DockMotion
import com.opentune.data.settings.GlassStyle
import com.opentune.data.settings.LyricsAlign
import com.opentune.data.settings.LyricsAnimation
import com.opentune.data.settings.PageTransition
import com.opentune.data.settings.PaletteStyleOption
import com.opentune.data.settings.PlayerBackground
import com.opentune.data.settings.PlayerMotion
import com.opentune.data.settings.PlayerStyle
import com.opentune.data.settings.Recommender
import com.opentune.data.settings.SEED_COLORS
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.VolumeLevel
import com.opentune.playback.BitPerfectUsb
import com.opentune.ui.components.AppSwitch
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ChoiceSheet
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.NavRow
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.PillSegmented
import com.opentune.ui.components.RowDivider
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.components.liquidGlassSupported
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One searchable row: what it's called and the words it can be found by. */
private class Entry(val title: String, val summary: String = "", val content: @Composable () -> Unit)

private class Section(val title: String, val entries: List<Entry>)

/** A setting placed on its page: the category it's on and the subsection under it. */
private class Placed(val entry: Entry, val category: SettingsCategory, val group: String)

/**
 * Settings as a short list of categories, each opening its own page of
 * subsections (see [SETTINGS_CATEGORIES]). Searching from the main page looks
 * through every setting and shows where each one lives.
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    /** Null when Settings is a tab of its own: no way back to show. */
    onBack: (() -> Unit)?,
    onOpenEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onOpenWrapped: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = settingsSections(onOpenEqualizer, onOpenReplay, onOpenWrapped)
    val placed = remember(sections) { place(sections) }
    val open = SETTINGS_CATEGORIES.firstOrNull { it.key == openKey }
    androidx.activity.compose.BackHandler(enabled = open != null) { openKey = null }
    // Back from a page on a TV: the remote's focus goes back to the row that opened it,
    // not to wherever it fell when the page went (the rail's first button).
    val tv = com.opentune.ui.tv.LocalIsTv.current
    var cameFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val returnTo = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(open) {
        if (!tv || open != null || cameFrom == null) return@LaunchedEffect
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        runCatching { returnTo.requestFocus() }
    }
    val q = query.trim()
    val languageContext = LocalContext.current
    // Kept out here, so coming back from a page finds the main list where it was left.
    val mainList = androidx.compose.foundation.lazy.rememberLazyListState()
    // Which folded subsections have been opened with "See more".
    var unfolded by rememberSaveable { mutableStateOf(emptySet<String>()) }

    androidx.compose.animation.AnimatedContent(
        open,
        transitionSpec = {
            val forward = targetState != null
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { if (forward) it / 8 else -it / 8 }) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
        },
        label = "settingsPage",
    ) { page ->
        LazyColumn(
            state = if (page == null) mainList else androidx.compose.foundation.lazy.rememberLazyListState(),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (page != null) {
                item { PageHeader(com.opentune.ui.theme.tr(page.title), onBack = { openKey = null }) }
                placed.filter { it.category == page }.groupBy { it.group }.forEach { (group, rows) ->
                    item(key = "label:$group") { GroupLabel(group) }
                    item(key = "card:$group") {
                        val shown = FOLDED_GROUPS[group]?.takeIf { group !in unfolded && it < rows.size }
                        GroupCard(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))) {
                            rows.take(shown ?: rows.size).forEachIndexed { i, row ->
                                if (i > 0) RowDivider()
                                row.entry.content()
                            }
                            if (FOLDED_GROUPS[group]?.let { it < rows.size } == true) {
                                RowDivider()
                                SeeMoreRow(open = shown == null, hidden = rows.size - (FOLDED_GROUPS[group] ?: 0)) {
                                    unfolded = if (group in unfolded) unfolded - group else unfolded + group
                                }
                            }
                        }
                    }
                }
            } else {
                item { PageHeader(com.opentune.ui.theme.tr("Settings"), onBack = onBack) }
                item {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(com.opentune.ui.theme.tr("Search settings")) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                        shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (q.isNotEmpty()) {
                    val hits = placed.filter {
                        it.entry.title.contains(q, true) || com.opentune.ui.theme.translated(languageContext, it.entry.title).contains(q, true) || it.entry.summary.contains(q, true) ||
                            it.group.contains(q, true) || it.category.title.contains(q, true)
                    }
                    if (hits.isEmpty()) {
                        item { Text("No settings match \"$q\".", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                    }
                    hits.groupBy { "${it.category.title} · ${it.group}" }.forEach { (where, rows) ->
                        item(key = "hit:$where") { GroupLabel(where) }
                        item(key = "hitcard:$where") {
                            GroupCard {
                                rows.forEachIndexed { i, row ->
                                    if (i > 0) RowDivider()
                                    row.entry.content()
                                }
                            }
                        }
                    }
                } else {
                    SETTINGS_HOME.forEach { (label, keys) ->
                        val cats = keys.mapNotNull { k -> SETTINGS_CATEGORIES.firstOrNull { it.key == k } }
                        item(key = "home:$label") { GroupLabel(label) }
                        item(key = "homecard:$label") {
                            GroupCard {
                                cats.forEachIndexed { i, c ->
                                    if (i > 0) RowDivider()
                                    val summary = c.summary
                                    androidx.compose.foundation.layout.Box(if (c.key == cameFrom) Modifier.focusRequester(returnTo) else Modifier) {
                                    NavRow(
                                        c.title,
                                        { cameFrom = c.key; openKey = c.key },
                                        summary = summary,
                                        icon = c.icon,
                                    )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "LocalTune ${BuildConfig.VERSION_NAME} by $DEVELOPER_NAME • Based on OpenTune by justshivv • GNU GPL v3",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(28.dp),
                    )
                }
            }
        }
    }
}

/** The last row of a folded subsection: "See more" with how many are tucked away, or "See less". */
@Composable
private fun SeeMoreRow(open: Boolean, hidden: Int, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "seeMore")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (open) "See less" else "See $hidden more",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Puts every setting on its category page, in the order [SETTINGS_CATEGORIES]
 * lists them; one it doesn't name goes under "More" on its old section's page.
 */
private fun place(sections: List<Section>): List<Placed> {
    val byTitle = sections.flatMap { s -> s.entries.map { it to s.title } }.associateBy { it.first.title }
    val used = mutableSetOf<String>()
    val out = mutableListOf<Placed>()
    // Every named setting first, so a page's "More" only gets what no page claims.
    SETTINGS_CATEGORIES.forEach { c ->
        c.groups.forEach { (group, titles) ->
            titles.forEach { t -> byTitle[t]?.let { (e, _) -> out += Placed(e, c, group); used += t } }
        }
    }
    SETTINGS_CATEGORIES.forEach { c ->
        byTitle.values.filter { (e, section) -> e.title !in used && SECTION_HOME[section] == c.key }
            .forEach { (e, _) -> out += Placed(e, c, "More"); used += e.title }
    }
    // Anything left (a section with no page) still shows, on About.
    val about = SETTINGS_CATEGORIES.last()
    byTitle.values.filter { (e, _) -> e.title !in used }.forEach { (e, _) -> out += Placed(e, about, "More") }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun settingsSections(
    onOpenEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onOpenWrapped: () -> Unit,
): List<Section> {
    var showWhatsNew by remember { mutableStateOf(false) }
    var playerStyleDialog by remember { mutableStateOf(false) }
    var appIconDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var hiddenDialog by remember { mutableStateOf(false) }
    val hidden by LibraryStore.hidden.collectAsState()
    var lyricsAnimationDialog by remember { mutableStateOf(false) }
    var dockMotionDialog by remember { mutableStateOf(false) }
    var dockLensDialog by remember { mutableStateOf(false) }
    var glassStyleDialog by remember { mutableStateOf(false) }
    var pageTransitionDialog by remember { mutableStateOf(false) }
    var playerMotionDialog by remember { mutableStateOf(false) }
    var coverChangeDialog by remember { mutableStateOf(false) }
    var controlStyleDialog by remember { mutableStateOf(false) }
    val lyricsSettings by AppSettings.lyrics.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by AppSettings.theme.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val lib by AppSettings.library.collectAsState()
    val metered = remember { AppSettings.onMeteredNetwork() }
    var folderDialog by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    val doc = buildJsonObject {
                        // Only the library, nothing about this phone's setup (sound, look, equalizer),
                        // so the same file can be read by LocalTune on another phone or on Windows.
                        // Songs are matched there by title and artist; see LocalMusic.relinkSaved.
                        put("app", "LocalTune")
                        put("format", "localtune-library")
                        put("version", 2)
                        put("exportedAt", System.currentTimeMillis())
                        put("history", History.exportJson())
                        put("library", LibraryStore.exportJson())
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(doc.toString().toByteArray()) }
                }
                "Exported your liked songs, playlists and listening history."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val doc = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { AppSettings.json.parseToJsonElement(it.readBytes().decodeToString()) }
                } as? JsonObject ?: error("this isn't a LocalTune backup")
                // Only this app's backups (LocalTune's, or OpenTune's from before the rename).
                val app = (doc["app"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (app !in setOf("LocalTune", "OpenTune") || listOf("history", "library").none { it in doc }) error("this isn't a LocalTune backup")
                // Settings in older backups are left alone: a backup carries only the library.
                // Every part is read first: a damaged file changes nothing, rather than half of it.
                withContext(Dispatchers.Default) {
                    doc["history"]?.let(History::checkJson)
                    doc["library"]?.let(LibraryStore::checkJson)
                }
                val (likes, lists) = doc["library"]?.let(LibraryStore::countJson) ?: (0 to 0)
                doc["history"]?.let(History::importJson)
                doc["library"]?.let(LibraryStore::importJson)
                // Songs moved onto this phone's own files (they're numbered differently here).
                withContext(Dispatchers.IO) { runCatching { LocalMusic.current(context) } }
                LocalMusic.relinkSaved()
                "Restored $likes liked songs, $lists playlists and your listening history."
            }.getOrElse { "Import failed: ${it.message}" }
        }
    }

    if (showWhatsNew) com.opentune.ui.components.WhatsNewSheet(
        com.opentune.ui.components.WhatsNew.since(null, BuildConfig.VERSION_NAME, BuildConfig.CHANGELOG),
        BuildConfig.VERSION_NAME, onDismiss = { showWhatsNew = false },
    )
    if (hiddenDialog) HiddenDialog(onDismiss = { hiddenDialog = false })
    if (appIconDialog) AppIconDialog { appIconDialog = false }
    if (fontDialog) com.opentune.ui.theme.FontSettingsSheet { fontDialog = false }
    if (playerStyleDialog) {
        ChoiceSheet(
            title = "Player layout",
            options = PlayerStyle.entries,
            selected = ui.playerStyle,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { v -> AppSettings.updateUi { it.copy(playerStyle = v) } },
            onDismiss = { playerStyleDialog = false },
        )
    }
    if (languageDialog) {
        ChoiceSheet(
            title = com.opentune.ui.theme.tr("App language"),
            options = com.opentune.ui.theme.AppLanguage.entries,
            selected = com.opentune.ui.theme.AppLanguages.selected(context),
            label = { it.label },
            onSelect = { language -> languageDialog = false; com.opentune.ui.theme.AppLanguages.select(context, language) },
            onDismiss = { languageDialog = false },
        )
    }
    if (controlStyleDialog) {
        ChoiceSheet(
            title = "Player buttons",
            options = ControlStyle.entries,
            selected = ui.controlStyle,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { c -> AppSettings.updateUi { it.copy(controlStyle = c) } },
            onDismiss = { controlStyleDialog = false },
        )
    }
    if (dockMotionDialog) {
        ChoiceSheet(
            title = "Dock animation",
            options = DockMotion.entries,
            selected = ui.dockMotion,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { m -> AppSettings.updateUi { it.copy(dockMotion = m) } },
            onDismiss = { dockMotionDialog = false },
        )
    }
    if (dockLensDialog) {
        ChoiceSheet(
            title = "Dock lens",
            options = DockLens.entries,
            selected = ui.dockLens,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { l -> AppSettings.updateUi { it.copy(dockLens = l) } },
            onDismiss = { dockLensDialog = false },
        )
    }
    if (glassStyleDialog) {
        ChoiceSheet(
            title = "Glass style",
            options = GlassStyle.entries,
            selected = ui.glassStyle,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { g -> AppSettings.updateUi { it.copy(glassStyle = g) } },
            onDismiss = { glassStyleDialog = false },
        )
    }
    if (pageTransitionDialog) {
        ChoiceSheet(
            title = "Page transitions",
            options = PageTransition.entries,
            selected = ui.pageTransition,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { t -> AppSettings.updateUi { it.copy(pageTransition = t) } },
            onDismiss = { pageTransitionDialog = false },
        )
    }
    if (coverChangeDialog) {
        ChoiceSheet(
            title = "Song change",
            options = CoverChange.entries,
            selected = ui.coverChange,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { c -> AppSettings.updateUi { it.copy(coverChange = c) } },
            onDismiss = { coverChangeDialog = false },
        )
    }
    if (playerMotionDialog) {
        ChoiceSheet(
            title = "Player opening",
            options = PlayerMotion.entries,
            selected = ui.playerMotion,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { m -> AppSettings.updateUi { it.copy(playerMotion = m) } },
            onDismiss = { playerMotionDialog = false },
        )
    }
    if (lyricsAnimationDialog) {
        ChoiceSheet(
            title = "Lyrics animation",
            options = LyricsAnimation.entries,
            selected = ui.lyricsAnimation,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { a -> AppSettings.updateUi { it.copy(lyricsAnimation = a) } },
            onDismiss = { lyricsAnimationDialog = false },
        )
    }
    if (folderDialog) {
        var folders by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
        LaunchedEffect(Unit) { folders = runCatching { LocalMusic.folders(context) }.getOrDefault(emptyList()) }
        ChoiceSheet(
            title = "Local music folder",
            options = listOf<String?>(null) + folders.map { it.first },
            selected = lib.localFolder,
            label = { f ->
                f?.let { name ->
                    val n = folders.firstOrNull { it.first == name }?.second ?: 0
                    "$name · $n ${if (n == 1) "song" else "songs"}"
                } ?: "All audio folders · Every song on the phone"
            },
            onSelect = { f -> AppSettings.updateLibrary { it.copy(localFolder = f) } },
            onDismiss = { folderDialog = false },
        )
    }
    message?.let { m ->
        FloatingDialog(onDismissRequest = { message = null }, confirmButton = { SheetButton("OK", onClick = { message = null }, closes = true) }, text = { Text(m) })
    }

    return listOf(
        Section(
            "Audio quality",
            listOfNotNull(
                // The two look alike; the one for the network in use now says so.
                Entry("Output precision", "16-bit 32-bit float sample rate") {
                    SettingRow("Output precision", summary = outputSummary(context, pb.floatOutput), icon = Icons.Rounded.GraphicEq, beta = true, below = {
                        PillSegmented(listOf(false, true), pb.floatOutput, { if (it) "32-bit float" else "16-bit PCM" }, { v -> AppSettings.updatePlayback { it.copy(floatOutput = v) } })
                        Text(
                            "Float keeps hi-res files in 32-bit all the way to the output, effects included. 16-bit is dithered once, after the effects. Takes effect after a restart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    })
                },
                Entry("Bit-perfect USB output", "dac exclusive mode usb audio player pro hiby passthrough hi-fi audiophile mixer bypass sample rate switching") {
                    val usb by BitPerfectUsb.status.collectAsState()
                    ToggleRow(
                        "Exclusive USB output",
                        pb.bitPerfectUsb,
                        { v -> AppSettings.updatePlayback { it.copy(bitPerfectUsb = v) } },
                        summary = if (!BitPerfectUsb.supported) "Needs Android 14 or newer"
                        else when (val s = usb) {
                            is BitPerfectUsb.Status.Active -> "Exclusive on ${s.device}: ${s.format.ifEmpty { "${s.bits}-bit" }} at ${s.sampleRate / 1000.0} kHz, bit for bit"
                            is BitPerfectUsb.Status.NoMatch -> "On. The DAC lists no bit-perfect mode for ${s.sampleRate / 1000.0} kHz, so this song goes through Android's mixer"
                            BitPerfectUsb.Status.NoDac -> "On. Plug in a USB DAC and the music moves to it"
                            else -> "The DAC plays only LocalTune, switched to each song's own sample rate and bit depth: no mixing, resampling or effects. Volume is applied by the app; at full volume the signal is bit-exact"
                        },
                        icon = Icons.Rounded.Usb,
                        enabled = BitPerfectUsb.supported,
                        beta = true,
                    )
                },
                if (pb.pureOutput) null else Entry("System audio effects", "dolby atmos soundalive equalizer device effects") {
                    ToggleRow("System audio effects", pb.systemEffects, { v -> AppSettings.updatePlayback { it.copy(systemEffects = v) } }, summary = "Let the phone's own effects (Dolby, SoundAlive, system equalizer) process playback", icon = Icons.Rounded.SurroundSound)
                },
                Entry("Background playback", "battery optimization doze killed stops samsung") {
                    val ignoring = remember { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }
                    NavRow(
                        "Background playback",
                        { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                        summary = if (ignoring) "Unrestricted: music keeps playing with the screen off" else "Restricted: if playback stops in the background, set LocalTune to Unrestricted",
                        icon = Icons.Rounded.BatteryChargingFull,
                    )
                },
                Entry("Prefer USB DAC", "external dac usb audio") {
                    ToggleRow("Prefer USB DAC", pb.preferUsbDac, { v -> AppSettings.updatePlayback { it.copy(preferUsbDac = v) } }, summary = "Send audio to a USB DAC whenever one is plugged in", icon = Icons.Rounded.Usb, beta = true)
                },
            ),
        ),
        Section(
            "Playback",
            listOfNotNull(
                Entry("Pure output", "raw untouched bit perfect original audio no processing dsp equalizer effects bypass") {
                    ToggleRow(
                        "Pure output", pb.pureOutput, { v -> AppSettings.updatePlayback { it.copy(pureOutput = v) } },
                        summary = "Plays each file's own audio untouched: no equalizer or effects, no loudness levelling, and the phone's sound effects closed. Turn off to see the equalizer and effects again; their settings are kept.",
                        icon = Icons.Rounded.GraphicEq,
                    )
                },
                if (pb.pureOutput) null else Entry("Loudness normalization", "volume level") {
                    ToggleRow("Loudness normalization", pb.loudnessNormalization, { v -> AppSettings.updatePlayback { it.copy(loudnessNormalization = v) } }, summary = "Uses each file's ReplayGain tag to set one steady volume per song", icon = Icons.AutoMirrored.Rounded.VolumeUp)
                },
                if (pb.loudnessNormalization && !pb.pureOutput) Entry("Volume level", "loud louder quiet normal volume level loudness target boost") {
                    SettingRow("Volume level", summary = pb.volumeLevel.summary, icon = Icons.AutoMirrored.Rounded.VolumeUp, below = {
                        PillSegmented(VolumeLevel.entries, pb.volumeLevel, { it.label }, { v -> AppSettings.updatePlayback { it.copy(volumeLevel = v) } })
                    })
                } else null,
                if (pb.loudnessNormalization && !pb.pureOutput) Entry("Normalize on the phone speaker", "loudness volume speaker louder quiet normalization level") {
                    ToggleRow(
                        "Normalize on the phone speaker",
                        pb.normalizeOnSpeaker,
                        { v -> AppSettings.updatePlayback { it.copy(normalizeOnSpeaker = v) } },
                        summary = if (pb.normalizeOnSpeaker) "Songs are levelled on the speaker too, which turns loud ones down"
                        else "Off: the speaker never turns a song down, only lifts quiet ones; headphones and Bluetooth are fully levelled",
                        icon = Icons.Rounded.Speaker,
                    )
                } else null,
                Entry("Crossfade", "fade blend transition overlap") {
                    SettingRow(
                        "Crossfade",
                        summary = if (pb.crossfadeSeconds == 0) "Off: songs play back to back" else "Blend each song into the next over ${pb.crossfadeSeconds} s",
                        icon = Icons.Rounded.Animation,
                        below = {
                            Slider(
                                value = pb.crossfadeSeconds.toFloat(),
                                onValueChange = { v -> AppSettings.updatePlayback { it.copy(crossfadeSeconds = v.toInt()) } },
                                valueRange = 0f..12f,
                                steps = 11,
                            )
                        },
                    )
                },
                Entry("Skip silence", "gaps") {
                    ToggleRow("Skip silence", pb.skipSilence, { v -> AppSettings.updatePlayback { it.copy(skipSilence = v) } }, summary = "Trim gaps longer than a second", icon = Icons.Rounded.SpaceBar)
                },
                if (pb.pureOutput) null else Entry("Clarity", "studio master clarity presence air bass clear enhance") {
                    ToggleRow("Clarity", pb.clarity, { v -> AppSettings.updatePlayback { it.copy(clarity = v) } }, summary = "Firmer bass, less mud, more presence and air, at the same loudness. Off plays the file untouched.", icon = Icons.Rounded.AutoAwesome, beta = true)
                },
                if (pb.pureOutput) null else Entry("Spatial audio", "stereo widen immersive") {
                    ToggleRow("Spatial audio", pb.spatialAudio, { v -> AppSettings.updatePlayback { it.copy(spatialAudio = v) } }, summary = "Widens stereo tracks for a more open sound", icon = Icons.Rounded.SurroundSound, beta = true)
                },
                if (pb.pureOutput) null else Entry("Equalizer", "eq bands tone balance bass treble") {
                    NavRow("Equalizer", onOpenEqualizer, summary = "Fifteen bands, tone and balance", icon = Icons.Rounded.Equalizer)
                },
                Entry("Autoplay", "radio continue") {
                    ToggleRow("Autoplay", pb.autoplay, AppSettings::setAutoplay, summary = "Keep playing similar songs when the queue ends", icon = Icons.AutoMirrored.Rounded.PlaylistPlay)
                },
                Entry("Don't repeat songs in current session", "autoplay duplicates") {
                    ToggleRow("Don't repeat songs in current session", pb.noRepeatInSession, { v -> AppSettings.updatePlayback { it.copy(noRepeatInSession = v) } }, summary = "Autoplay won't add a song already played or queued this session", icon = Icons.Rounded.History)
                },
                Entry("Smart shuffle", "shuffle similar songs mix recommendations playlist album sparkle") {
                    ToggleRow("Smart shuffle", pb.smartShuffle, { v -> AppSettings.updatePlayback { it.copy(smartShuffle = v) } }, summary = "Mixes a similar song in after every four. Tap the player's shuffle button twice, or long-press Shuffle on a playlist or album", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Resume when headphones connect", "bluetooth headset wired auto play resume connect car") {
                    ToggleRow("Resume when headphones connect", pb.resumeOnConnect, { v -> AppSettings.updatePlayback { it.copy(resumeOnConnect = v) } }, summary = "Carries on playing when headphones are plugged in or a Bluetooth device connects, while LocalTune is open or in the notification", icon = com.opentune.ui.player.PlayerIcons.Headphones)
                },
                Entry("Pause at zero volume", "mute volume down silent") {
                    ToggleRow("Pause at zero volume", pb.pauseAtZeroVolume, { v -> AppSettings.updatePlayback { it.copy(pauseAtZeroVolume = v) } }, summary = "Pauses when the volume is turned all the way down and plays again when it comes back up", icon = Icons.AutoMirrored.Rounded.VolumeOff)
                },
                Entry("Stop music on close from recents", "swipe away") {
                    ToggleRow("Stop music on close from recents", pb.stopOnTaskRemoved, { v -> AppSettings.updatePlayback { it.copy(stopOnTaskRemoved = v) } }, summary = "Stops playback when swiped away from recent apps", icon = Icons.Rounded.MusicOff)
                },
            ),
        ),
        Section(
            "Appearance",
            listOf(
                Entry("Theme", "dark light system") {
                    SettingRow("Theme", icon = Icons.Rounded.Palette, below = {
                        PillSegmented(ThemeMode.entries, theme.mode, { it.label }, { m -> AppSettings.updateTheme { it.copy(mode = m) } })
                    })
                },
                Entry("Color from artwork", "tint album cover") {
                    ToggleRow("Color from artwork", theme.colorFromArtwork, { v -> AppSettings.updateTheme { it.copy(colorFromArtwork = v) } }, summary = "Tint the app with the playing song's cover", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Mini player takes the cover's colour", "mini player pill tint colour artwork plain") {
                    ToggleRow("Mini player takes the cover's colour", ui.miniPlayerTint, { v -> AppSettings.updateUi { it.copy(miniPlayerTint = v) } }, summary = "Off keeps the small player's glass plain, whatever is playing", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Material You", "wallpaper dynamic color") {
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ToggleRow(
                        "Material You",
                        theme.dynamicColor,
                        { v -> AppSettings.updateTheme { it.copy(dynamicColor = v) } },
                        summary = if (supported) "Use your wallpaper's colors when nothing is playing" else "Needs Android 12",
                        icon = Icons.Rounded.Wallpaper,
                        enabled = supported,
                    )
                },
                Entry("Accent color", "color") {
                    SettingRow("Accent color", icon = Icons.Rounded.Palette, below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SEED_COLORS.forEach { argb ->
                                val selected = theme.seedColor == argb
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(Color(argb))
                                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { AppSettings.updateTheme { it.copy(seedColor = argb) } },
                                    contentAlignment = Alignment.Center,
                                ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            }
                        }
                    })
                },
                Entry("Palette style", "tonal vibrant expressive") {
                    SettingRow("Palette style", icon = Icons.Rounded.Palette, below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaletteStyleOption.entries.forEach { s ->
                                FilterChip(theme.paletteStyle == s, { AppSettings.updateTheme { it.copy(paletteStyle = s) } }, label = { Text(s.label) })
                            }
                        }
                    })
                },
                Entry("App language", "language locale hindi punjabi english भाषा ਭਾਸ਼ਾ") {
                    NavRow("App language", { languageDialog = true }, icon = Icons.Rounded.Language,
                        value = com.opentune.ui.theme.AppLanguages.selected(context).label)
                },
                Entry("App icon", "launcher icon design glass pearl midnight rose ocean") {
                    NavRow("App icon", { appIconDialog = true }, summary = "Choose the LocalTune icon on your home screen", icon = Icons.Rounded.Palette)
                },
                Entry("Fonts & readability", "font typeface text weight bold thin ttf otf import custom") {
                    NavRow("Fonts & readability", { fontDialog = true }, summary = "Built-in fonts, text weight and your own font file", icon = Icons.Rounded.TextFields, value = theme.font.label)
                },
                Entry("Pure black", "oled amoled") {
                    ToggleRow("Pure black", theme.pureBlack, { v -> AppSettings.updateTheme { it.copy(pureBlack = v) } }, summary = "Black backgrounds with neutral gray cards in dark theme", icon = Icons.Rounded.Wallpaper)
                },
                Entry("Player layout", "player style layout vinyl record lyrics minimal classic screen") {
                    NavRow("Player layout", { playerStyleDialog = true }, summary = ui.playerStyle.summary, icon = Icons.Rounded.Album, value = ui.playerStyle.label)
                },
                Entry("Player beside the page", "tablet foldable large screen side pane docked player split landscape") {
                    ToggleRow(
                        "Player beside the page",
                        ui.sidePlayer,
                        { v -> AppSettings.updateUi { it.copy(sidePlayer = v) } },
                        summary = "On large screens, the player stays open on the right while music plays",
                        icon = Icons.Rounded.VerticalSplit,
                        enabled = ui.wideLayout,
                    )
                },
                Entry("Player buttons", "play pause next previous skip controls buttons design morph disc squircle glass") {
                    NavRow("Player buttons", { controlStyleDialog = true }, summary = ui.controlStyle.summary, icon = Icons.Rounded.PlayCircle, value = ui.controlStyle.label)
                },
                Entry("Dock animation", "navigation bar dock fold hide scroll animation motion squash pop") {
                    NavRow("Dock animation", { dockMotionDialog = true }, summary = ui.dockMotion.summary, icon = Icons.Rounded.Dock, value = ui.dockMotion.label)
                },
                Entry("Dock lens", "navigation bar dock tab highlight selection pill indicator jelly stretch glide") {
                    NavRow("Dock lens", { dockLensDialog = true }, summary = ui.dockLens.summary, icon = Icons.Rounded.Lens, value = ui.dockLens.label)
                },
                Entry("Opening animation", "splash start launch intro logo reveal dive") {
                    ToggleRow(
                        "Opening animation",
                        ui.openingAnimation,
                        { v -> AppSettings.updateUi { it.copy(openingAnimation = v) } },
                        summary = "When LocalTune starts, the screen dives into the logo and the app comes through it",
                        icon = Icons.Rounded.AutoAwesome,
                    )
                },
                Entry("Page transitions", "navigation animation screen open back slide fade zoom rise") {
                    NavRow("Page transitions", { pageTransitionDialog = true }, summary = ui.pageTransition.summary, icon = Icons.Rounded.SwapHoriz, value = ui.pageTransition.label)
                },
                Entry("Double-tap to skip 10 seconds", "double tap cover seek forward back rewind ten seconds gesture") {
                    ToggleRow("Double-tap to skip 10 seconds", ui.doubleTapSeek, { v -> AppSettings.updateUi { it.copy(doubleTapSeek = v) } }, summary = "Double-tap the left of the cover to go back 10 seconds, the right to go on", icon = Icons.Rounded.TouchApp)
                },
                Entry("Glow under the cover", "glow shadow light cover colour bass pulse") {
                    ToggleRow("Glow under the cover", ui.coverGlow, { v -> AppSettings.updateUi { it.copy(coverGlow = v) } }, summary = "A soft light in the cover's colours under it, swelling a little with the bass", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Song change", "cover change next song skip animation flip carousel deck fade") {
                    NavRow("Song change", { coverChangeDialog = true }, summary = ui.coverChange.summary, icon = Icons.Rounded.SwapHoriz, value = ui.coverChange.label)
                },
                Entry("Player opening", "now playing sheet open close animation spring smooth bouncy snappy") {
                    NavRow("Player opening", { playerMotionDialog = true }, summary = ui.playerMotion.summary, icon = Icons.Rounded.UnfoldMore, value = ui.playerMotion.label)
                },
                Entry("Player background", "flow apple music animated cover blur blurred mesh gradient echo glow colours pure black oled amoled") {
                    SettingRow("Player background", icon = Icons.Rounded.Wallpaper, below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayerBackground.entries.forEach { b ->
                                FilterChip(theme.playerBackground == b, { AppSettings.updateTheme { it.copy(playerBackground = b) } }, label = { Text(b.label) })
                            }
                        }
                    })
                },
                Entry("Keep the phone cool", "battery heat warm hot power saver cool performance smooth") {
                    ToggleRow(
                        "Keep the phone cool", ui.coolPlayer, { v -> AppSettings.updateUi { it.copy(coolPlayer = v) } },
                        summary = "The cover holds still, the Flow background moves more slowly, other backgrounds hold still and lyric lines dim instead of blurring, so the phone stays cooler and the battery lasts longer",
                        icon = Icons.Rounded.BatterySaver,
                    )
                },
                Entry("Artist pictures from the internet", "artist image photo picture online deezer download") {
                    ToggleRow(
                        "Artist pictures from the internet", ui.onlineArtistPictures, { v -> AppSettings.updateUi { it.copy(onlineArtistPictures = v) } },
                        summary = "When a song file has no artist picture, look the artist up on Deezer (only the name is sent). Each is fetched once and kept.",
                        icon = Icons.Rounded.Person,
                    )
                },
                Entry("Animated artwork", "apple tall animated artwork video motion mp4 canvas") {
                    ToggleRow(
                        "Animated artwork", ui.animatedArtwork, { v -> AppSettings.updateUi { it.copy(animatedArtwork = v) } },
                        summary = "When a song's folder has a tall_animated_artwork video, it plays behind the player like Apple Music. Needs access to videos.",
                        icon = Icons.Rounded.Animation,
                    )
                },
                Entry("Moving cover art", "motion artwork canvas animated cover drift zoom") {
                    ToggleRow("Moving cover art", ui.movingCover, { v -> AppSettings.updateUi { it.copy(movingCover = v) } }, summary = if (ui.coolPlayer) "Off while Keep the phone cool is on" else "The cover drifts and zooms slowly in the player while a song plays", icon = Icons.Rounded.Animation)
                },
                Entry("Full-screen cover art", "artwork edge") {
                    ToggleRow("Full-screen cover art", ui.fullScreenCover, { v -> AppSettings.updateUi { it.copy(fullScreenCover = v) } }, summary = "Runs the cover to the edges of the player instead of a square sleeve", icon = Icons.Rounded.Fullscreen)
                },
                Entry("Wavy seek bar", "wave progress slider") {
                    ToggleRow("Wavy seek bar", ui.wavySeekbar, { v -> AppSettings.updateUi { it.copy(wavySeekbar = v) } }, summary = "The played part of the bar ripples while music plays", icon = Icons.Rounded.Waves)
                },
                Entry("Lyric preview while seeking", "scrub seek bar lyric line preview bubble drag") {
                    ToggleRow("Lyric preview while seeking", ui.lyricScrubPreview, { v -> AppSettings.updateUi { it.copy(lyricScrubPreview = v) } }, summary = "Dragging the seek bar shows the line sung at that point above your finger", icon = Icons.Rounded.Lyrics)
                },
                Entry("Seek bar thickness", "progress bar seek slider thin thick height") {
                    SettingRow("Seek bar thickness", icon = Icons.Rounded.LineWeight, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarThickness.entries, ui.seekbarThickness, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarThickness = v) } })
                    })
                },
                Entry("Seek bar handle", "progress bar thumb knob dot line handle") {
                    SettingRow("Seek bar handle", icon = Icons.Rounded.RadioButtonChecked, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarThumb.entries, ui.seekbarThumb, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarThumb = v) } })
                    })
                },
                Entry("Seek bar colour", "progress bar color accent gradient") {
                    SettingRow("Seek bar colour", summary = "Accent and Gradient follow the cover when the player takes its colours", icon = Icons.Rounded.Palette, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarColor.entries, ui.seekbarColor, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarColor = v) } })
                    })
                },
                Entry("Seek bar time", "progress bar remaining total length duration time left") {
                    SettingRow("Time on the right", icon = Icons.Rounded.Schedule, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarTime.entries, ui.seekbarTime, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarTime = v) } })
                    })
                },
                Entry("Audio details page", "signal path pipeline format details bit perfect output routing codec resolution") {
                    SettingRow("Audio details page", summary = "What the quality label opens: cards of format, output and source, or the live pipeline", icon = Icons.Rounded.GraphicEq, below = {
                        PillSegmented(com.opentune.data.settings.SignalPathStyle.entries, ui.signalPathStyle, { it.label }, { v -> AppSettings.updateUi { it.copy(signalPathStyle = v) } })
                    })
                },
                Entry("Song part marks", "seek bar chorus verse dots marks parts") {
                    ToggleRow("Song part marks", ui.seekbarMarks, { v -> AppSettings.updateUi { it.copy(seekbarMarks = v) } }, summary = "Small dots on the seek bar where the verse and chorus begin, when the lyrics mark them", icon = Icons.Rounded.MoreHoriz)
                },
                Entry("Skip to chorus", "chorus jump skip button song parts") {
                    ToggleRow("Skip to chorus button", ui.skipToChorus, { v -> AppSettings.updateUi { it.copy(skipToChorus = v) } }, summary = "A button under the title that jumps to the next chorus, when the lyrics mark one", icon = Icons.Rounded.FastForward)
                },
                Entry("Reduce animation", "motion") {
                    ToggleRow("Reduce animation", ui.reduceAnimation, { v -> AppSettings.updateUi { it.copy(reduceAnimation = v) } }, summary = "Freezes decorative motion and simplifies player and lyric transitions", icon = Icons.Rounded.Animation)
                },
                Entry("Liquid Glass", "glass refraction apple lens") {
                    ToggleRow(
                        "Liquid Glass",
                        ui.liquidGlass && liquidGlassSupported,
                        { v -> AppSettings.updateUi { it.copy(liquidGlass = v) } },
                        summary = if (liquidGlassSupported) "Bends and lifts what's behind the floating bars, like Apple's glass"
                        else "Needs Android 13 or newer; frosted glass is used instead",
                        icon = Icons.Rounded.AutoAwesome,
                        enabled = liquidGlassSupported && !ui.reduceBlur,
                    )
                },
                Entry("Glass style", "glass frosted blur clear heavy tinted smoke theme material") {
                    NavRow("Glass style", { glassStyleDialog = true }, summary = ui.glassStyle.summary, icon = Icons.Rounded.BlurOn, value = ui.glassStyle.label)
                },
                Entry("Frosted top edge", "status bar blur fade top scroll glass") {
                    ToggleRow("Frosted top edge", ui.frostedTopEdge, { v -> AppSettings.updateUi { it.copy(frostedTopEdge = v) } }, summary = "Pages blur and fade as they scroll under the status bar", icon = Icons.Rounded.Gradient)
                },
                Entry("High performance mode", "120hz 90hz smooth fps refresh rate frame rate scrolling performance battery unrestricted lag") {
                    HighPerformanceRows(ui)
                },
                Entry("Wide-screen layout", "tablet foldable large screen landscape rail side navigation dock columns") {
                    ToggleRow(
                        "Wide-screen layout",
                        ui.wideLayout,
                        { v -> AppSettings.updateUi { it.copy(wideLayout = v) } },
                        summary = "On tablets, unfolded phones and in landscape, the tabs move to a rail on the side and pages keep a readable width",
                        icon = Icons.Rounded.TabletAndroid,
                    )
                },
                Entry("Reduce dynamic blur", "glass frosted performance") {
                    ToggleRow("Reduce dynamic blur", ui.reduceBlur, { v -> AppSettings.updateUi { it.copy(reduceBlur = v) } }, summary = "Swaps frosted glass for solid fills across the app", icon = Icons.Rounded.BlurOff)
                },
                Entry("Haptic feedback", "vibration haptics strength tap buzz intensity") {
                    val haptics = com.opentune.ui.components.rememberHaptics()
                    SettingRow(
                        "Haptic feedback",
                        summary = com.opentune.ui.components.Haptics.label(ui.hapticStrength) +
                            if (ui.hapticStrength > 0f) " · ${(ui.hapticStrength * 100).roundToInt()}%" else " · No taps or buzzes",
                        icon = Icons.Rounded.Vibration,
                        below = {
                            Slider(
                                value = ui.hapticStrength,
                                onValueChange = { v -> AppSettings.updateUi { it.copy(hapticStrength = (v * 10).roundToInt() / 10f) } },
                                // Let go and feel the new strength.
                                onValueChangeFinished = { haptics.press() },
                                valueRange = 0f..1f,
                                steps = 9,
                            )
                        },
                    )
                },
            ),
        ),
        Section(
            "Lyrics",
            listOf(
                Entry("Synced lyrics", "karaoke words") {
                    ToggleRow("Synced lyrics", ui.syncedLyrics, { v -> AppSettings.updateUi { it.copy(syncedLyrics = v) } }, summary = "Lights up the words as they're sung", icon = Icons.Rounded.Lyrics)
                },
                Entry("Lyrics animation", "style karaoke slide zoom fluid motion") {
                    NavRow("Lyrics animation", { lyricsAnimationDialog = true }, summary = ui.lyricsAnimation.summary, icon = Icons.Rounded.Animation, value = ui.lyricsAnimation.label)
                },
                Entry("Lyrics text size", "font bigger smaller") {
                    SettingRow(
                        "Lyrics text size",
                        summary = "${(ui.lyricsTextScale * 100).roundToInt()}%" + if (ui.lyricsTextScale == 1f) " · Standard" else "",
                        icon = Icons.Rounded.FormatSize,
                        below = {
                            Slider(
                                value = ui.lyricsTextScale,
                                onValueChange = { v -> AppSettings.updateUi { it.copy(lyricsTextScale = (v * 20).roundToInt() / 20f) } },
                                valueRange = 0.7f..1.5f,
                                steps = 15,
                            )
                        },
                    )
                },
                Entry("Lyrics alignment", "lyrics center centre left align") {
                    SettingRow("Lyrics alignment", icon = Icons.Rounded.FormatAlignCenter, below = {
                        PillSegmented(LyricsAlign.entries, ui.lyricsAlign, { it.label }, { a -> AppSettings.updateUi { it.copy(lyricsAlign = a) } })
                    })
                },
                Entry("Glow on the sung line", "lyrics glow shine highlight") {
                    ToggleRow("Glow on the sung line", ui.lyricsGlow, { v -> AppSettings.updateUi { it.copy(lyricsGlow = v) } }, summary = "A soft halo around the line being sung, with any animation", icon = Icons.Rounded.Flare)
                },
                Entry("Blur unfocused lyrics", "spotlight") {
                    ToggleRow("Blur unfocused lyrics", ui.blurLyrics, { v -> AppSettings.updateUi { it.copy(blurLyrics = v) } }, summary = "Keeps the spotlight on the current line", icon = Icons.Rounded.BlurOn)
                },
                Entry("Lyrics folders", "lrc ttml elrc txt lyrics files folder sidecar apple music word synced karaoke") {
                    val lyricFolders by com.opentune.data.lyrics.LocalLyrics.folders.collectAsState()
                    val found by com.opentune.data.lyrics.LocalLyrics.indexed.collectAsState()
                    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                        tree?.let(com.opentune.data.lyrics.LocalLyrics::addFolder)
                    }
                    SettingRow(
                        "Lyrics folders",
                        summary = if (lyricFolders.isEmpty()) "Choose the folder with your music and its .lrc and .ttml files. Android only lets the app read them from folders you pick."
                        else "${lyricFolders.size} ${if (lyricFolders.size == 1) "folder" else "folders"}" + (if (found > 0) " · $found lyric files" else ""),
                        icon = Icons.Rounded.Folder,
                        below = {
                            Column {
                                lyricFolders.forEach { tree ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(com.opentune.data.lyrics.LocalLyrics.label(tree), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        androidx.compose.material3.TextButton(onClick = { com.opentune.data.lyrics.LocalLyrics.removeFolder(tree) }) { Text("Remove") }
                                    }
                                }
                                Row {
                                    androidx.compose.material3.TextButton(onClick = { pick.launch(null) }) { Text("Add folder") }
                                    if (lyricFolders.isNotEmpty()) androidx.compose.material3.TextButton(onClick = {
                                        com.opentune.data.lyrics.LocalLyrics.refresh()
                                        message = "Lyric files will be looked for again as songs play."
                                    }) { Text("Look again") }
                                }
                            }
                        },
                    )
                },
                Entry("Lyrics take the screen", "hide controls idle focus fullscreen lyrics auto") {
                    ToggleRow("Lyrics take the screen", ui.lyricsFocus, { v -> AppSettings.updateUi { it.copy(lyricsFocus = v) } }, summary = "On the lyrics view, the controls slide away after a few seconds untouched. Touch or swipe to bring them back.", icon = Icons.Rounded.Lyrics)
                },
            ),
        ),
        Section(
            "Local music",
            listOf(
                Entry("Local music folder", "device files") {
                    NavRow("Local music folder", { folderDialog = true }, summary = lib.localFolder ?: "All audio folders", icon = Icons.Rounded.Folder)
                },
                Entry("Filter non-music audio", "recordings voice notes") {
                    ToggleRow("Filter non-music audio", lib.filterNonMusic, { v -> AppSettings.updateLibrary { it.copy(filterNonMusic = v) } }, summary = "Hides clips under 30 seconds, voice notes, recordings and system sounds", icon = Icons.Rounded.FilterAlt)
                },
            ),
        ),
        Section(
            "Storage",
            listOf(
                Entry("Clear image cache", "artwork") {
                    NavRow("Clear image cache", {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        scope.launch { withContext(Dispatchers.IO) { loader.diskCache?.clear() } }
                        message = "Image cache cleared."
                    }, summary = "Frees space used by album artwork", icon = Icons.Rounded.DeleteSweep)
                },
            ),
        ),
        Section(
            "Your data",
            listOf(
                Entry("Replay", "stats top songs artists") {
                    NavRow("Replay", onOpenReplay, summary = "Your top songs, artists and albums", icon = Icons.Rounded.BarChart)
                },
                Entry("Wrapped", "wrapped story year review stats recap top artists") {
                    NavRow("Wrapped", onOpenWrapped, summary = "Your listening as an animated story", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Export data", "backup json") {
                    NavRow("Export data", { exporter.launchSafely(context, "localtune-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json") }, summary = "Liked songs, playlists and listening history, as one file. Settings aren't included", icon = Icons.Rounded.Upload)
                },
                Entry("Import data", "restore json") {
                    NavRow("Import data", { importer.launchSafely(context, arrayOf("application/json")) }, summary = "Replaces the liked songs, playlists and history here. Your settings stay as they are", icon = Icons.Rounded.Download)
                },
                Entry("Clear listening history", "delete history") {
                    NavRow("Clear listening history", { History.clear(); com.opentune.data.history.ListenedCollections.clear(); message = "Listening history cleared." }, summary = "Removes Recents and Replay data", icon = Icons.Rounded.DeleteSweep)
                },
            ),
        ),
        Section(
            "Miscellaneous",
            listOf(
                Entry("Swipe songs to queue them", "gesture swipe queue play next add to queue") {
                    ToggleRow("Swipe songs to queue them", pb.swipeToQueue, { v -> AppSettings.updatePlayback { it.copy(swipeToQueue = v) } }, summary = "Swipe a song right to play it next, or left to add it to the end of the queue", icon = Icons.AutoMirrored.Rounded.PlaylistPlay)
                },
                Entry("Lyrics preview", "player lyrics line apple music classic theme") {
                    ToggleRow("Lyrics preview", ui.lyricsPreview, { v -> AppSettings.updateUi { it.copy(lyricsPreview = v) } },
                        summary = "Show the current lyric on the player in every theme", icon = Icons.Rounded.Lyrics)
                },
            ),
        ),
        Section(
            "Advanced",
            listOf(
                Entry("Made by", "credits developer author portfolio about me skvhacks") {
                    NavRow("Made by $DEVELOPER_NAME", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, DEVELOPER_PORTFOLIO.toUri())) }
                    }, summary = "Designed and built by $DEVELOPER_NAME. Tap to see my portfolio", icon = Icons.Rounded.Person)
                },
                Entry("Source code", "github open source license gpl") {
                    NavRow("Source code", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())) }
                    }, summary = "Based on OpenTune by justshivv · GPL v3", icon = Icons.Rounded.Code)
                },
                Entry("Export diagnostics", "log share troubleshooting bug report") {
                    NavRow("Export diagnostics", {
                        scope.launch {
                            val log = LogExport.recent()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "LocalTune ${BuildConfig.VERSION_NAME} log")
                                .putExtra(Intent.EXTRA_TEXT, log)
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        }
                    }, summary = "Share this app's recent log for troubleshooting", icon = Icons.Rounded.BugReport)
                },
                Entry("Show stats for nerds", "codec bitrate sample rate debug") {
                    ToggleRow("Show stats for nerds", ui.statsForNerds, { v -> AppSettings.updateUi { it.copy(statsForNerds = v) } }, summary = "Codec, bitrate and sample rate under the seek bar", icon = Icons.Rounded.Info)
                },
            ),
        ),
    )
}

private fun outputSummary(context: Context, float: Boolean): String {
    val am = context.getSystemService(AudioManager::class.java)
    val rate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    val khz = rate?.let { "%.1f kHz".format(it / 1000f) } ?: "unknown rate"
    return "AudioTrack · ${Build.MODEL} · $khz · ${if (float) "32-bit float" else "16-bit PCM"}"
}

private fun formatMb(mb: Int): String = if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"

/** Spotify's green disc, for its rows in Account. */
@Composable
private fun SpotifyMark() {
    // Spotify's own mark, in its own colours.
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.opentune.R.drawable.ic_spotify), "Spotify", Modifier.size(40.dp))
}

/** Who made LocalTune, and where to see their work: shown in Settings › About. */
internal const val DEVELOPER_NAME = "SKVhacks"
internal const val DEVELOPER_PORTFOLIO = "https://github.com/SKVhacks"
/** This app's own source, as the GPL asks; the original it's built on is credited beside it. */
private const val SOURCE_URL = "https://github.com/SKVhacks/Local-Tune"
