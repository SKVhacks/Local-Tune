package com.opentune.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.opentune.data.DebugLog as Log
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto (and any other media browser) can browse and play, all
 * from the music on the phone:
 *
 *  - Recent: this device's listening history, and Shuffle all;
 *  - Albums, Artists and Folders;
 *  - Library: Liked songs, every song, and the playlists made in the app;
 *  - search, typed or spoken.
 *
 * A song carries the list it was shown in (`<parent>|<videoId>`), so tapping
 * it queues that list from there, as tapping a song in the app does;
 * [resolve] turns those back into the plain queue items the rest of the app uses.
 */
@UnstableApi
class CarLibrary(private val context: Context) {
    /** The songs last listed under each node, for queueing a tapped song's list. */
    private val listed = ConcurrentHashMap<String, List<Song>>()

    /** Every item handed out, so a browser can look one up again by id. */
    private val known = ConcurrentHashMap<String, MediaItem>()

    fun root(): MediaItem = folder(ROOT, "OpenTune", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)

    /** Extras for the root: search is supported, folders show as a grid and songs as a list. */
    fun rootExtras(): Bundle = Bundle().apply {
        putBoolean(EXTRA_SEARCH_SUPPORTED, true)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    fun item(mediaId: String): MediaItem? = known[mediaId] ?: if (mediaId == ROOT) root() else null

    private suspend fun lib() = LocalMusic.current(context)

    suspend fun children(parentId: String, browser: MediaSession.ControllerInfo?): List<MediaItem> {
        val items = when {
            // Android Auto shows up to four tabs: these are they.
            parentId == ROOT -> listOf(
                folder(RECENT, "Recent", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
                folder(ALBUMS, "Albums", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
                folder(ARTISTS, "Artists", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
                folder(LIBRARY, "Library", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            )
            parentId == RECENT -> {
                val all = lib().songs
                listOf(shuffleAll(all)) + songs(RECENT, History.recents(History.records.value, MAX_SONGS).filter { LocalMusic.isLocal(it.videoId) })
            }
            parentId == SHUFFLE -> songs(SHUFFLE, lib().songs.shuffled().take(MAX_SONGS))
            parentId == ALBUMS -> lib().albums.map { a ->
                folder("$ALBUM${a.id}", a.title, a.artist, a.id, MediaMetadata.MEDIA_TYPE_ALBUM, group = groupLetter(a.title))
            }
            parentId == ARTISTS -> lib().artists.map { a ->
                folder("$ARTIST${a.id}", a.name, "${a.tracks.size} songs", a.albums.firstOrNull()?.id, MediaMetadata.MEDIA_TYPE_ARTIST, group = groupLetter(a.name))
            }
            parentId == FOLDERS -> lib().folders.map { f ->
                folder("$FOLDER${f.path}", f.name, "${f.tracks.size} songs", f.tracks.firstOrNull()?.song?.albumId, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            }
            parentId == LIBRARY -> libraryItems()
            parentId == LIKED -> songs(LIKED, LibraryStore.liked.value.map { it.toSong() })
            parentId == ALL_SONGS -> songs(ALL_SONGS, lib().songs)
            parentId.startsWith(PLAYLIST) -> {
                val playlist = LibraryStore.playlists.value.firstOrNull { it.id == parentId.removePrefix(PLAYLIST) }
                songs(parentId, playlist?.songs.orEmpty().map { it.toSong() })
            }
            parentId.startsWith(ALBUM) -> songs(parentId, lib().album(parentId.removePrefix(ALBUM))?.songs.orEmpty())
            parentId.startsWith(FOLDER) -> songs(parentId, lib().folder(parentId.removePrefix(FOLDER))?.songs.orEmpty())
            parentId.startsWith(ARTIST) -> artistItems(parentId)
            else -> emptyList()
        }
        grantArtwork(items, browser)
        return items
    }

    suspend fun search(query: String, browser: MediaSession.ControllerInfo): List<MediaItem> {
        val lib = lib()
        val q = query.trim().lowercase(Locale.ROOT)
        val tracks = lib.songs.filter { s -> s.title.lowercase(Locale.ROOT).contains(q) || s.artist.lowercase(Locale.ROOT).contains(q) || s.albumName?.lowercase(Locale.ROOT)?.contains(q) == true }
        val albums = lib.albums.filter { it.title.lowercase(Locale.ROOT).contains(q) }.take(PER_GROUP).map { a ->
            folder("$ALBUM${a.id}", a.title, a.artist, a.id, MediaMetadata.MEDIA_TYPE_ALBUM, group = "Albums")
        }
        val artists = lib.artists.filter { it.name.lowercase(Locale.ROOT).contains(q) }.take(PER_GROUP).map { a ->
            folder("$ARTIST${a.id}", a.name, "${a.tracks.size} songs", a.albums.firstOrNull()?.id, MediaMetadata.MEDIA_TYPE_ARTIST, group = "Artists")
        }
        val items = songs(SEARCH, tracks, group = "Songs") + artists + albums
        grantArtwork(items, browser)
        return items
    }

    /**
     * Items a browser asked to play, as queue items. A single tapped song
     * brings its list with it; a spoken request ("play … on OpenTune")
     * plays what matches best.
     */
    suspend fun resolve(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val single = items.singleOrNull()
        if (single != null) {
            val query = single.requestMetadata.searchQuery
            if (single.mediaId.isEmpty() && query != null) return voice(query)
            val (parent, videoId) = split(single.mediaId) ?: (null to single.mediaId)
            if (parent != null) {
                val songs = listed[parent] ?: run {
                    runCatching { children(parent, null) }
                    listed[parent]
                }
                val at = songs?.indexOfFirst { it.videoId == videoId } ?: -1
                if (songs != null && at >= 0) {
                    return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, at, startPositionMs)
                }
            }
        }
        return MediaSession.MediaItemsWithStartPosition(items.map(::toQueueItem), startIndex, startPositionMs)
    }

    /** A queue item for anything a controller sends: the app's own, or a browsed song. */
    fun toQueueItem(item: MediaItem): MediaItem {
        val videoId = split(item.mediaId)?.second ?: item.mediaId
        if (videoId == item.mediaId) return item.buildUpon().setUri(trackUri(videoId)).build()
        val known = listed.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.videoId == videoId } }
        val song = known ?: Song(
            videoId = videoId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            thumbnailUrl = null,
        )
        return song.toMediaItem()
    }

    /**
     * "Play music" shuffles everything; "play <artist>" plays that artist,
     * "play <album>" the album, and anything else the songs whose title
     * matches, best first.
     */
    private suspend fun voice(query: String): MediaSession.MediaItemsWithStartPosition {
        val lib = lib()
        val q = query.trim().lowercase(Locale.ROOT)
        val songs = when {
            q.isBlank() -> lib.songs.shuffled()
            else -> lib.artists.firstOrNull { it.name.lowercase(Locale.ROOT) == q }?.songs?.shuffled()
                ?: lib.albums.firstOrNull { it.title.lowercase(Locale.ROOT) == q }?.songs
                ?: lib.songs.filter { it.title.lowercase(Locale.ROOT) == q }.ifEmpty { null }
                ?: lib.artists.firstOrNull { it.name.lowercase(Locale.ROOT).contains(q) }?.songs?.shuffled()
                ?: lib.albums.firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) }?.songs
                ?: lib.songs.filter { it.title.lowercase(Locale.ROOT).contains(q) || "${it.title} ${it.artist}".lowercase(Locale.ROOT).contains(q) }
        }
        Log.d(TAG, "voice request \"$query\": ${songs.size} song(s)")
        return MediaSession.MediaItemsWithStartPosition(songs.take(500).map { it.toMediaItem() }, 0, 0L)
    }

    private suspend fun libraryItems(): List<MediaItem> = buildList {
        val all = lib().songs
        add(folder(ALL_SONGS, "All songs", subtitle = "${all.size} songs", albumId = all.firstOrNull()?.albumId))
        val liked = LibraryStore.liked.value
        add(folder(LIKED, "Liked songs", subtitle = "${liked.size} songs", albumId = liked.firstOrNull()?.albumId))
        add(folder(FOLDERS, "Folders", subtitle = "${lib().folders.size} folders"))
        LibraryStore.playlists.value.forEach { p ->
            add(folder("$PLAYLIST${p.id}", p.name, subtitle = "${p.songs.size} songs", albumId = p.songs.firstOrNull()?.albumId, type = MediaMetadata.MEDIA_TYPE_PLAYLIST, group = "Playlists"))
        }
    }

    private suspend fun artistItems(parentId: String): List<MediaItem> {
        val artist = lib().artist(parentId.removePrefix(ARTIST)) ?: return emptyList()
        val albums = artist.albums.map { a ->
            folder("$ALBUM${a.id}", a.title, a.year.takeIf { it > 0 }?.toString() ?: a.artist, a.id, MediaMetadata.MEDIA_TYPE_ALBUM, group = "Albums")
        }
        return songs(parentId, artist.songs, group = "Songs") + albums
    }

    private fun shuffleAll(all: List<Song>): MediaItem {
        listed[SHUFFLE] = all.shuffled()
        val id = "$SHUFFLE$SEP${listed[SHUFFLE]?.firstOrNull()?.videoId.orEmpty()}"
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Shuffle all")
                    .setSubtitle("${all.size} songs")
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build(),
            )
            .build()
            .also { known[id] = it }
    }

    private fun songs(parent: String, songs: List<Song>, group: String? = null): List<MediaItem> {
        listed[parent] = songs
        return songs.take(MAX_SONGS).map { song(parent, it, group) }
    }

    private fun song(parent: String, song: Song, group: String? = null): MediaItem {
        val id = "$parent$SEP${song.videoId}"
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.albumName)
                    .setArtworkUri(CarArtwork.uriFor(context, song.albumId))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(groupExtras(group))
                    .build(),
            )
            .build()
            .also { known[id] = it }
    }

    private fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        albumId: String? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        group: String? = null,
        browsableStyle: Int? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle?.takeIf { it.isNotBlank() })
                .setArtworkUri(CarArtwork.uriFor(context, albumId))
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(type)
                .setExtras(
                    groupExtras(group).apply {
                        browsableStyle?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
                    },
                )
                .build(),
        )
        .build()
        .also { known[id] = it }

    private fun groupExtras(group: String?) = Bundle().apply {
        group?.takeIf { it.isNotBlank() }?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
    }

    /** The A–Z heading a name is listed under. */
    private fun groupLetter(name: String): String {
        val c = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return "#"
        return if (c.isLetter()) c.toString() else "#"
    }

    /** Lets the browser that asked read the artwork this app serves it. */
    private fun grantArtwork(items: List<MediaItem>, browser: MediaSession.ControllerInfo?) {
        val pkg = browser?.packageName?.takeIf { it.isNotBlank() && it != context.packageName } ?: return
        items.mapNotNull { it.mediaMetadata.artworkUri }.filter { it.scheme == "content" }.forEach { uri ->
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun split(mediaId: String): Pair<String, String>? {
        val at = mediaId.lastIndexOf(SEP)
        return if (at <= 0) null else mediaId.substring(0, at) to mediaId.substring(at + 1)
    }

    companion object {
        private const val TAG = "CarLibrary"
        const val ROOT = "root"
        const val RECENT = "recent"
        const val LIBRARY = "library"
        const val LIKED = "liked"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val FOLDERS = "folders"
        const val ALL_SONGS = "songs"
        const val SHUFFLE = "shuffle"
        const val SEARCH = "search"
        private const val PLAYLIST = "playlist:"
        private const val ALBUM = "album:"
        private const val ARTIST = "artist:"
        private const val FOLDER = "folder:"
        private const val SEP = '|'
        private const val MAX_SONGS = 500
        private const val PER_GROUP = 10
        /** Android Auto shows its search box when the root says so. */
        private const val EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}

/**
 * Artwork URIs Android Auto can load: album covers served as content URIs
 * through [ArtworkProvider].
 */
object CarArtwork {
    fun uriFor(context: Context, albumId: String?): Uri? {
        val id = albumId?.removePrefix(LocalMusic.ALBUM_PREFIX)?.toLongOrNull() ?: return null
        return Uri.Builder().scheme("content").authority("${context.packageName}.artwork").appendPath("album").appendPath(id.toString()).build()
    }
}
