package com.opentune.data.local

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LibrarySettings
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Audio files on the device, read from MediaStore. */
object LocalMusic {
    const val PREFIX = "local:"
    /** [Song.albumId] of a song on the phone: its MediaStore album. */
    const val ALBUM_PREFIX = "localalbum:"
    /** [Song.artistId] of a song on the phone: its artist's name, folded. */
    const val ARTIST_PREFIX = "localartist:"
    private val ALBUM_ART = "content://media/external/audio/albumart".toUri()

    fun isLocal(videoId: String) = videoId.startsWith(PREFIX)

    /** Saved by LocalTune for Windows: the song's file path on that PC (`file:E:\Music\…`). */
    const val WINDOWS_PREFIX = "file:"

    /**
     * A song saved on this phone, another phone or a PC: matched to this phone's files by
     * title and artist when its own id isn't here (see [Library.find]).
     */
    fun isFromAnyDevice(videoId: String) = videoId.startsWith(PREFIX) || videoId.startsWith(WINDOWS_PREFIX)

    fun contentUri(videoId: String): Uri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        videoId.removePrefix(PREFIX).toLong(),
    )

    fun albumArt(albumId: Long): String = ContentUris.withAppendedId(ALBUM_ART, albumId).toString()

    /** The permission that lets the app read audio files. */
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    data class Track(
        val song: Song,
        val folder: String,
        val mimeType: String?,
        val durationMs: Long,
        val isMusic: Boolean,
        val albumKey: Long,
        val albumArtist: String,
        /** Disc × 1000 + track, as MediaStore keeps it; 0 when the file doesn't say. */
        val trackNumber: Int,
        val year: Int,
        /** Seconds since 1970. */
        val dateAdded: Long,
    )

    data class Album(val id: String, val title: String, val artist: String, val art: String, val year: Int, val tracks: List<Track>) {
        val songs: List<Song> get() = tracks.map { it.song }
    }

    data class Artist(val id: String, val name: String, val tracks: List<Track>, val albums: List<Album>) {
        val songs: List<Song> get() = tracks.map { it.song }
        val art: String? get() = albums.firstOrNull()?.art ?: tracks.firstOrNull()?.song?.thumbnailUrl
    }

    data class Folder(val path: String, val tracks: List<Track>) {
        val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        val songs: List<Song> get() = tracks.map { it.song }
    }

    /** Everything on the phone, grouped every way the library shows it. */
    data class Library(val tracks: List<Track>, val albums: List<Album>, val artists: List<Artist>, val folders: List<Folder>) {
        val songs: List<Song> by lazy { tracks.map { it.song } }
        private val byId by lazy { tracks.associateBy { it.song.videoId } }
        fun track(videoId: String) = byId[videoId]
        private val byName by lazy { tracks.groupBy { nameKey(it.song.title, it.song.artist) } }
        private val byTitle by lazy { tracks.groupBy { plain(it.song.title) } }

        /**
         * This song as the library has it now. Android renumbers a file when it's moved,
         * renamed or scanned again, so a song saved earlier (liked, in a playlist, in the
         * history or the last queue) is found by its id, else by title and artist, else by
         * a title only one song has. Null when it's no longer on the phone.
         */
        fun find(song: Song): Song? =
            byId[song.videoId]?.song
                ?: byName[nameKey(song.title, song.artist)]?.firstOrNull()?.song
                ?: byTitle[plain(song.title)]?.singleOrNull()?.song

        fun album(id: String) = albums.firstOrNull { it.id == id }
        fun artist(id: String) = artists.firstOrNull { it.id == id }
        fun folder(path: String) = folders.firstOrNull { it.path == path }

        companion object { val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList()) }
    }

    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun nameKey(title: String, artist: String) = plain(title) + "|" + plain(splitArtists(artist).firstOrNull() ?: artist)

    /**
     * A saved song as it's on the phone now (see [Library.find]): itself while the
     * library isn't read yet, null when it's gone. Songs that aren't local pass as they are.
     */
    fun resolve(song: Song): Song? {
        if (!isFromAnyDevice(song.videoId)) return song
        val library = _library.value ?: return song
        return library.find(song)?.let { if (it.videoId == song.videoId) song else it.copy(queueTier = song.queueTier) }
    }

    /**
     * Liked songs, playlists and history moved onto the files' current numbers, once the
     * library's read: a backup from another phone, a reinstall or a rescan otherwise leaves
     * them pointing at numbers that are gone (their pictures blank, their plays uncounted).
     * Songs not found are left as they are, in case their files come back.
     */
    fun relinkSaved(library: Library? = _library.value) {
        library ?: return
        fun moved(id: String, title: String, artist: String): Song? {
            if (!isFromAnyDevice(id)) return null
            val found = library.find(Song(id, title, artist, null, null)) ?: return null
            return found.takeIf { it.videoId != id }
        }
        com.opentune.data.library.LibraryStore.relink { r ->
            moved(r.videoId, r.title, r.artist)?.let { s ->
                r.copy(videoId = s.videoId, thumbnailUrl = s.thumbnailUrl, albumId = s.albumId, durationText = s.durationText ?: r.durationText)
            } ?: r
        }
        com.opentune.data.history.History.relink { r ->
            moved(r.videoId, r.title, r.artist)?.let { s -> r.copy(videoId = s.videoId, thumbnailUrl = s.thumbnailUrl) } ?: r
        }
    }

    private val _library = MutableStateFlow<Library?>(null)
    /** The library, or null until it's first read (or without permission). */
    val library: StateFlow<Library?> = _library

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private var watching = false

    /** Reads the library now and again whenever files are added or removed, or the folder filter changes. */
    fun init(context: Context) {
        app = context.applicationContext
        scope.launch {
            AppSettings.library.distinctUntilChangedBy { it.localFolder to it.filterNonMusic }.collect { rescan(0) }
        }
    }

    /** Reads the library again, after [delayMs] to let a burst of file changes settle. */
    fun rescan(delayMs: Long = 1_500) {
        if (!::app.isInitialized) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            if (!hasPermission(app)) { _library.value = null; return@launch }
            watch()
            _scanning.value = true
            runCatching { build(query(app), AppSettings.library.value) }.onSuccess { _library.value = it; relinkSaved(it) }
            _scanning.value = false
        }
    }

    private fun watch() {
        if (watching) return
        watching = true
        app.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = rescan()
            },
        )
    }

    /** The library as it is now, read first if it hasn't been. */
    suspend fun current(context: Context): Library {
        _library.value?.let { return it }
        if (!::app.isInitialized) app = context.applicationContext
        if (!hasPermission(context)) return Library.EMPTY
        return withContext(Dispatchers.IO) { build(query(context), AppSettings.library.value) }.also { _library.value = it; relinkSaved(it) }
    }

    suspend fun tracks(context: Context, settings: LibrarySettings): List<Song> = withContext(Dispatchers.IO) {
        filtered(query(context), settings).map { it.song }
    }

    suspend fun folders(context: Context): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        query(context).groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }
    }

    private fun filtered(all: List<Track>, settings: LibrarySettings) = all
        .filter { settings.localFolder == null || it.folder.startsWith(settings.localFolder) }
        .filter { !settings.filterNonMusic || looksLikeMusic(it) }

    /** A name folded for grouping: "The Weeknd" and "the weeknd " are one artist. */
    fun nameKey(name: String) = name.trim().lowercase(Locale.ROOT)

    fun artistId(name: String) = ARTIST_PREFIX + nameKey(name)

    private fun build(all: List<Track>, settings: LibrarySettings): Library {
        val tracks = filtered(all, settings)
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        val albums = tracks.groupBy { it.albumKey }.map { (key, list) ->
            val sorted = list.sortedWith(compareBy<Track>({ it.trackNumber == 0 }, { it.trackNumber }).thenComparator { a, b -> collator.compare(a.song.title, b.song.title) })
            val first = sorted.first()
            // An album's artist: its album-artist tag, else the one artist on it, else "Various artists".
            val artist = sorted.map { it.albumArtist }.firstOrNull { it.isNotBlank() }
                ?: sorted.map { it.song.artist }.distinct().singleOrNull()
                ?: "Various artists"
            Album(
                id = "$ALBUM_PREFIX$key",
                title = first.song.albumName?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: first.folder.trimEnd('/').substringAfterLast('/').ifEmpty { "Unknown album" },
                artist = artist,
                art = albumArt(key),
                year = sorted.maxOf { it.year },
                tracks = sorted,
            )
        }.sortedWith { a, b -> collator.compare(a.title, b.title) }
        // A song by "A, B & C feat. D" counts for each of them on the artist pages.
        val artistTracks = LinkedHashMap<String, Pair<String, MutableList<Track>>>()
        for (t in tracks) for (name in splitArtists(t.song.artist)) {
            artistTracks.getOrPut(nameKey(name)) { name to mutableListOf() }.second += t
        }
        val albumByKey = albums.associateBy { it.id }
        val artists = artistTracks.map { (key, pair) ->
            val (name, list) = pair
            val own = list.map { "$ALBUM_PREFIX${it.albumKey}" }.distinct().mapNotNull { albumByKey[it] }
                .sortedByDescending { it.year }
            Artist(ARTIST_PREFIX + key, name, list.sortedWith { a, b -> collator.compare(a.song.title, b.song.title) }, own)
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }
        val folders = tracks.groupBy { it.folder }.map { (path, list) ->
            Folder(path, list.sortedWith(compareBy<Track>({ it.trackNumber == 0 }, { it.trackNumber }).thenComparator { a, b -> collator.compare(a.song.title, b.song.title) }))
        }.sortedWith { a, b -> collator.compare(a.path, b.path) }
        val songs = tracks.sortedWith { a, b -> collator.compare(a.song.title, b.song.title) }
        return Library(songs, albums, artists, folders)
    }

    private val ARTIST_SPLIT = Regex("""\s*(?:,|;|/| & | x | feat\.? | ft\.? | featuring )\s*""", RegexOption.IGNORE_CASE)

    /** "A, B & C feat. D" as its four names; one name when the tag holds one. */
    fun splitArtists(artist: String): List<String> =
        artist.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::nameKey).ifEmpty { listOf(artist) }

    /**
     * Short clips, WAV recordings, voice notes and system sounds aren't music,
     * even when they sit in a music folder.
     */
    fun looksLikeMusic(t: Track): Boolean {
        val folder = t.folder.lowercase()
        return t.isMusic &&
            t.durationMs >= 30_000 &&
            listOf("recording", "voice", "whatsapp audio", "notifications", "ringtones", "alarms", "call").none { it in folder }
    }

    // Lint's Recycle check doesn't see Kotlin's use {}, which closes the cursor.
    @SuppressLint("Recycle", "InlinedApi")
    private fun query(context: Context): List<Track> {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val folderColumn = if (q) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_MUSIC,
            folderColumn,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            // Not a public column before Android 11, but there all the same.
            "album_artist",
        )
        val out = mutableListOf<Track>()
        val cursor = runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)
        }.getOrNull() ?: context.contentResolver.query(
            // A device whose MediaStore lacks album_artist: everything else still reads.
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection.copyOf(projection.size - 1), null, null, null,
        ) ?: return out
        cursor.use { c ->
            val hasAlbumArtist = c.columnCount > 12
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val duration = c.getLong(5)
                val rawFolder = c.getString(8).orEmpty()
                val folder = if (q) rawFolder else rawFolder.substringBeforeLast('/') + "/"
                val artist = c.getString(2)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist"
                val albumKey = c.getLong(4)
                val primary = splitArtists(artist).first()
                out += Track(
                    song = Song(
                        videoId = "$PREFIX$id",
                        title = c.getString(1).orEmpty(),
                        artist = artist,
                        thumbnailUrl = albumArt(albumKey),
                        durationText = formatDuration(duration),
                        albumName = c.getString(3)?.takeUnless { it == "<unknown>" },
                        artistId = artistId(primary),
                        albumId = "$ALBUM_PREFIX$albumKey",
                    ),
                    folder = folder,
                    mimeType = c.getString(6),
                    durationMs = duration,
                    isMusic = c.getInt(7) != 0,
                    albumKey = albumKey,
                    albumArtist = if (hasAlbumArtist) c.getString(12)?.takeUnless { it == "<unknown>" }.orEmpty() else "",
                    trackNumber = c.getInt(9),
                    year = c.getInt(10),
                    dateAdded = c.getLong(11),
                )
            }
        }
        return out
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
