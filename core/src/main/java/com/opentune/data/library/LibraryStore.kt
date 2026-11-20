package com.opentune.data.library

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.model.Song
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A track as stored on the device: enough to show it and play it again. */
@Serializable
data class SongRef(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val explicit: Boolean? = null,
    val albumId: String? = null,
    val artistId: String? = null,
) {
    fun toSong() = Song(videoId, title, artist, thumbnailUrl, durationText, albumName = albumName, isExplicit = explicit, albumId = albumId, artistId = artistId)

    companion object {
        fun of(song: Song) = SongRef(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName, song.isExplicit, song.albumId, song.artistId)
    }
}

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<SongRef> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val coverUrl: String? = null,
)

@Serializable
private data class LibraryDocument(
    val liked: List<SongRef> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    /** Songs marked "Dislike": kept out of autoplay. */
    val disliked: Set<String> = emptySet(),
    /** "Not interested": kept out of suggestions, newest first. */
    val hiddenSongs: List<HiddenSong> = emptyList(),
    /** "Don't play [artist]": their songs kept out of suggestions, newest first. */
    val hiddenArtists: List<HiddenArtist> = emptyList(),
)

/** Liked songs and playlists made in the app, kept on the device. */
object LibraryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val doc = MutableStateFlow(LibraryDocument())

    private val _liked = MutableStateFlow<List<SongRef>>(emptyList())
    /** Newest like first. */
    val liked: StateFlow<List<SongRef>> = _liked.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private val _disliked = MutableStateFlow<Set<String>>(emptySet())
    /** The song ids marked "Dislike". */
    val disliked: StateFlow<Set<String>> = _disliked.asStateFlow()

    private val _hidden = MutableStateFlow(HiddenList.EMPTY)
    /** Songs and artists kept out of autoplay and smart shuffle. */
    val hidden: StateFlow<HiddenList> = _hidden.asStateFlow()

    /** Keeps [song] out of suggestions; undone with [unhideSong]. */
    fun hideSong(song: Song): HiddenSong {
        val entry = HiddenSong(song.videoId, song.title, song.artist, song.thumbnailUrl, System.currentTimeMillis())
        update { d -> d.copy(hiddenSongs = listOf(entry) + d.hiddenSongs.filterNot { it.videoId == song.videoId }) }
        return entry
    }

    fun unhideSong(videoId: String) = update { d -> d.copy(hiddenSongs = d.hiddenSongs.filterNot { it.videoId == videoId }) }

    /** Keeps every song by [song]'s first credited artist out of suggestions; null when it names none. */
    fun hideArtist(song: Song): HiddenArtist? {
        val name = HiddenList.mainArtist(song.artist).takeIf { it.isNotBlank() } ?: return null
        val entry = HiddenArtist(name, song.artistId, System.currentTimeMillis())
        update { d -> d.copy(hiddenArtists = listOf(entry) + d.hiddenArtists.filterNot { it.key == entry.key }) }
        return entry
    }

    fun unhideArtist(key: String) = update { d -> d.copy(hiddenArtists = d.hiddenArtists.filterNot { it.key == key }) }

    private var saved: com.opentune.data.SavedFile? = null

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val f = com.opentune.data.SavedFile(File(context.filesDir, "library.json"), "LibraryStore").also { saved = it }
        // A library that won't read is kept aside, never saved over with an empty one.
        publish(f.read(LibraryDocument()) { json.decodeFromString(LibraryDocument.serializer(), it) })
        scope.launch {
            doc.drop(1).onEach { f.dirty = true }.debounce(800).collect { d ->
                runCatching { f.write(json.encodeToString(LibraryDocument.serializer(), d)) }
                    .onFailure { Log.w("LibraryStore", "Couldn't save the library", it) }
            }
        }
    }

    /** Saves at once a change still waiting for its save: the app is leaving the screen or closing. */
    fun flush() { saved?.flushIfDirty { json.encodeToString(LibraryDocument.serializer(), doc.value) } }

    private fun publish(d: LibraryDocument) {
        doc.value = d
        _liked.value = d.liked
        _playlists.value = d.playlists
        _hidden.value = HiddenList(d.hiddenSongs, d.hiddenArtists)
        _disliked.value = d.disliked
    }

    @Synchronized
    private fun update(t: (LibraryDocument) -> LibraryDocument) = publish(t(doc.value))

    fun isLiked(videoId: String): Boolean = _liked.value.any { it.videoId == videoId }

    fun isDisliked(videoId: String): Boolean = videoId in doc.value.disliked

    /** Marks [song] as one not to hear again: out of Liked and kept out of autoplay. */
    fun dislike(song: Song) =
        update { d -> d.copy(liked = d.liked.filterNot { it.videoId == song.videoId }, disliked = d.disliked + song.videoId) }

    fun setLiked(song: Song, liked: Boolean) = update { d ->
        val rest = d.liked.filterNot { it.videoId == song.videoId }
        d.copy(liked = if (liked) listOf(SongRef.of(song)) + rest else rest, disliked = if (liked) d.disliked - song.videoId else d.disliked)
    }

    @Synchronized
    fun createPlaylist(name: String, first: Song? = null): String {
        val id = UUID.randomUUID().toString()
        val p = LocalPlaylist(id, name.trim().ifEmpty { "New playlist" }, listOfNotNull(first?.let(SongRef::of)))
        update { it.copy(playlists = listOf(p) + it.playlists) }
        return id
    }

    /** Adds [songs] in order, skipping any already there; one write for the lot. */
    @Synchronized
    fun addAllToPlaylist(id: String, songs: List<Song>) = editPlaylist(id) { p ->
        val have = p.songs.mapTo(HashSet()) { it.videoId }
        p.copy(songs = p.songs + songs.filter { have.add(it.videoId) }.map(SongRef::of))
    }

    fun addToPlaylist(id: String, song: Song) = addAllToPlaylist(id, listOf(song))

    @Synchronized
    fun removeFromPlaylist(id: String, index: Int) =
        editPlaylist(id) { p -> p.copy(songs = p.songs.filterIndexed { i, _ -> i != index }) }

    /** A song just taken out of a playlist, which [undoRemove] can put back where it was. */
    class Removed internal constructor(val playlistId: String, val index: Int, val song: SongRef)

    /** How long Undo is offered after a removal. */
    const val UNDO_MS = 8_000L

    @Synchronized
    fun removeUndoably(id: String, index: Int): Removed? {
        val gone = playlist(id)?.songs?.getOrNull(index) ?: return null
        editPlaylist(id) { p -> p.copy(songs = p.songs.filterIndexed { i, _ -> i != index }) }
        return Removed(id, index, gone)
    }

    @Synchronized
    fun undoRemove(removed: Removed) {
        val p = playlist(removed.playlistId) ?: return
        val at = removed.index.coerceIn(0, p.songs.size)
        editPlaylist(removed.playlistId) { it.copy(songs = p.songs.toMutableList().apply { add(at, removed.song) }) }
    }

    @Synchronized
    fun movePlaylistItem(id: String, from: Int, to: Int) {
        val p = playlist(id) ?: return
        if (from !in p.songs.indices || to !in p.songs.indices || from == to) return
        editPlaylist(id) { it.copy(songs = p.songs.toMutableList().apply { add(to, removeAt(from)) }) }
    }

    @Synchronized
    fun renamePlaylist(id: String, name: String) {
        val old = playlist(id)?.name ?: return
        val new = name.trim().ifEmpty { old }
        if (new != old) editPlaylist(id) { it.copy(name = new) }
    }

    @Synchronized
    fun deletePlaylist(id: String) = update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }

    fun playlist(id: String): LocalPlaylist? = _playlists.value.firstOrNull { it.id == id }

    private fun editPlaylist(id: String, t: (LocalPlaylist) -> LocalPlaylist) = update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) t(it) else it })
    }

    fun exportJson(): JsonElement = json.encodeToJsonElement(LibraryDocument.serializer(), doc.value)

    /** Checks a backup's library part before anything is replaced; throws when it can't be read. */
    fun checkJson(element: JsonElement) { json.decodeFromJsonElement(LibraryDocument.serializer(), element) }

    /** How many liked songs and playlists a backup's library part holds. */
    fun countJson(element: JsonElement): Pair<Int, Int> =
        json.decodeFromJsonElement(LibraryDocument.serializer(), element).let { it.liked.size to it.playlists.size }

    /**
     * Saved songs pointed at the files as they're numbered now ([fix] gives each its
     * current self): after a restore on another phone, a reinstall or a rescan.
     */
    @Synchronized
    fun relink(fix: (SongRef) -> SongRef) {
        val d = doc.value
        val liked = d.liked.map(fix).distinctBy { it.videoId }
        val playlists = d.playlists.map { p -> p.copy(songs = p.songs.map(fix)) }
        if (liked != d.liked || playlists != d.playlists) publish(d.copy(liked = liked, playlists = playlists))
    }

    fun importJson(element: JsonElement) = publish(json.decodeFromJsonElement(LibraryDocument.serializer(), element))
}
