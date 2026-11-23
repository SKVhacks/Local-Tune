package com.opentune.data.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.opentune.data.DebugLog as Log
import com.opentune.data.local.LocalMusic
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lyrics kept as files on the phone, beside the songs they belong to:
 *
 *  - `.ttml` as Apple Music writes it: word or syllable timing, duet voices,
 *    backing vocals and the song's parts (verse, chorus).
 *  - `.lrc` (and `.elrc`): line timing, enhanced `<mm:ss.xx>` word timing,
 *    `[mm:ss.xx]` word timing inside a line, `v1:`/`v2:` duet marks and
 *    `[bg: …]` backing lines.
 *  - `.txt`: plain words.
 *
 * A file is matched to a song by name: `Song.flac` takes `Song.ttml` or
 * `Song.lrc`, from the same folder or a `Lyrics` folder inside it. Failing
 * that, a file named "Artist - Title" or just "Title" anywhere in the lyrics
 * folders counts too.
 *
 * Android only lets an app read music files with the audio permission, not
 * the text files next to them, so the folders holding lyric files are
 * granted once through the system folder picker (Settings › Lyrics ›
 * Lyrics folders) and remembered. Where the files can be read directly
 * (older Android, or "All files access") they're read without a grant.
 */
object LocalLyrics {
    private const val TAG = "LocalLyrics"
    private const val PREFS = "local_lyrics"
    private const val K_FOLDERS = "folders"

    /** Lyric file kinds, best first when one song has several. */
    val EXTENSIONS = listOf("ttml", "lrc", "elrc", "txt")

    private lateinit var app: Context

    private val _folders = MutableStateFlow<List<Uri>>(emptyList())
    /** Folders the listener has granted for lyric files. */
    val folders: StateFlow<List<Uri>> = _folders

    private val _indexed = MutableStateFlow(0)
    /** How many lyric files the granted folders hold, once looked through. */
    val indexed: StateFlow<Int> = _indexed

    fun init(context: Context) {
        app = context.applicationContext
        val stored = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(K_FOLDERS, emptySet()).orEmpty()
        // A grant can be taken back from the system settings; those are dropped.
        val held = app.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        _folders.value = stored.filter { it in held }.map(Uri::parse)
    }

    /** Keeps read access to a folder picked with ACTION_OPEN_DOCUMENT_TREE and adds it. */
    fun addFolder(tree: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(TAG, "No lasting access to $tree", it) }
        if (tree !in _folders.value) save(_folders.value + tree)
        index = null
        cache.clear()
    }

    fun removeFolder(tree: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        save(_folders.value - tree)
        index = null
        cache.clear()
    }

    /** Looks through the folders again, for files added since. */
    fun refresh() {
        index = null
        cache.clear()
    }

    /** A folder's name as the picker showed it: "Music/Lyrics" for primary:Music/Lyrics. */
    fun label(tree: Uri): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val (volume, path) = id.substringBefore(':') to id.substringAfter(':', "")
        val where = if (volume == "primary") "Phone" else "SD card"
        if (path.isEmpty()) where else "$where › ${path.replace("/", " › ")}"
    }.getOrDefault(tree.toString())

    private fun save(list: List<Uri>) {
        _folders.value = list
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(K_FOLDERS, list.map { it.toString() }.toSet()).apply()
    }

    // ---- Finding a song's lyrics ----

    private val cache = ConcurrentHashMap<String, Result<Lyrics?>>()

    /**
     * The best lyrics among the files found for a song from the phone, or
     * null when there are none. Word timing beats line timing, which beats
     * plain words; between equals TTML comes before LRC.
     */
    suspend fun find(videoId: String, title: String, artist: String, durationMs: Long): Lyrics? {
        if (!::app.isInitialized || !LocalMusic.isLocal(videoId)) return null
        cache[videoId]?.let { return it.getOrNull() }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val audio = audioFile(videoId)
                val candidates = candidates(audio, title, artist)
                candidates.mapNotNull { file -> runCatching { parse(file, durationMs) }.onFailure { Log.w(TAG, "Couldn't read ${file.name}", it) }.getOrNull() }
                    .maxWithOrNull(compareBy<Lyrics> { LyricsRepository.rank(it) }.thenByDescending { kindOrder(it) })
            }
        }
        cache[videoId] = found
        return found.getOrNull()
    }

    private fun kindOrder(l: Lyrics): Int = -EXTENSIONS.indexOf(l.source.substringAfterLast('.').lowercase(Locale.ROOT)).coerceAtLeast(0)

    /** One lyric file found for a song, readable either way. */
    private class LyricFile(val name: String, val open: () -> ByteArray)

    private data class Audio(val displayName: String?, val path: String?, val folderName: String?)

    @SuppressLint("Recycle")
    private fun audioFile(videoId: String): Audio {
        val uri = LocalMusic.contentUri(videoId)
        @Suppress("DEPRECATION")
        val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA)
        return app.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0)
            val path = c.getString(1)
            Audio(name, path, path?.let { File(it).parentFile?.name })
        } ?: Audio(null, null, null)
    }

    private suspend fun candidates(audio: Audio, title: String, artist: String): List<LyricFile> {
        val out = mutableListOf<LyricFile>()
        val base = (audio.displayName ?: audio.path?.let { File(it).name })?.substringBeforeLast('.')
        // Straight from the folder, where Android lets us.
        val dir = audio.path?.let { File(it).parentFile }
        if (dir != null && base != null) {
            for (where in listOf(dir, File(dir, "Lyrics"), File(dir, "lyrics"))) {
                for (ext in EXTENSIONS) {
                    val f = File(where, "$base.$ext")
                    if (runCatching { f.canRead() }.getOrDefault(false)) out += LyricFile(f.name) { f.readBytes() }
                }
            }
        }
        if (out.isNotEmpty()) return out
        // Through the granted folders.
        val idx = index() ?: return out
        val keys = buildList {
            base?.let { add(key(it)) }
            if (artist.isNotBlank()) {
                add(key("$artist - $title"))
                add(key("$title - $artist"))
            }
            add(key(title))
        }.filter { it.isNotEmpty() }.distinct()
        for (k in keys) {
            val hits = idx[k].orEmpty()
            if (hits.isEmpty()) continue
            // Same-named files in several folders: the one in the song's own folder wins.
            val inFolder = hits.filter { it.parent.equals(audio.folderName, ignoreCase = true) || it.parent.equals("lyrics", ignoreCase = true) }
            val chosen = (inFolder.ifEmpty { hits }).groupBy { it.parent }.values.first()
            return chosen.map { doc -> LyricFile(doc.name) { app.contentResolver.openInputStream(doc.uri)!!.use { it.readBytes() } } }
        }
        return out
    }

    /** "Daft Punk – One More Time (Radio Edit)" and "daft punk - one more time (radio edit)" are one key. */
    internal fun key(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    // ---- The granted folders, looked through once ----

    private class Doc(val name: String, val uri: Uri, val parent: String)

    @Volatile private var index: Map<String, List<Doc>>? = null
    private val indexing = Mutex()

    private suspend fun index(): Map<String, List<Doc>>? {
        index?.let { return it }
        if (_folders.value.isEmpty()) return null
        return indexing.withLock {
            index ?: build().also { index = it; _indexed.value = it.values.sumOf { l -> l.size } }
        }
    }

    @SuppressLint("Recycle")
    private fun build(): Map<String, List<Doc>> {
        val out = HashMap<String, MutableList<Doc>>()
        val resolver = app.contentResolver
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        for (tree in _folders.value) {
            val root = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            // Breadth first, with the folder's own name carried along for matching.
            val queue = ArrayDeque(listOf(root to label(tree).substringAfterLast(" › ")))
            var seen = 0
            while (queue.isNotEmpty() && seen < MAX_DOCS) {
                val (dirId, dirName) = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
                runCatching {
                    resolver.query(children, columns, null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            seen++
                            val id = c.getString(0) ?: continue
                            val name = c.getString(1) ?: continue
                            if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (!name.startsWith(".")) queue += id to name
                                continue
                            }
                            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (ext !in EXTENSIONS) continue
                            val doc = Doc(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), dirName)
                            out.getOrPut(key(name.substringBeforeLast('.'))) { mutableListOf() } += doc
                        }
                    }
                }.onFailure { Log.w(TAG, "Couldn't look in $dirName", it) }
            }
        }
        // Best kind first within each name.
        out.values.forEach { list -> list.sortBy { EXTENSIONS.indexOf(it.name.substringAfterLast('.').lowercase(Locale.ROOT)) } }
        Log.d(TAG, "Found ${out.values.sumOf { it.size }} lyric files")
        return out
    }

    /** Enough for any music collection, and a stop for a folder picked by mistake (all of the phone). */
    private const val MAX_DOCS = 200_000

    // ---- Reading a file ----

    private fun parse(file: LyricFile, durationMs: Long): Lyrics? {
        val text = decode(file.open()).trim()
        if (text.isEmpty()) return null
        val ext = file.name.substringAfterLast('.').lowercase(Locale.ROOT)
        val source = "File · ${file.name}"
        val looksTtml = text.startsWith("<?xml") || text.contains("<tt", ignoreCase = true)
        val lyrics: Lyrics? = when {
            ext == "ttml" || looksTtml -> {
                val lines = Ttml.parse(text)
                if (lines.isEmpty()) null
                else Lyrics.Synced(lines, source, parts = Ttml.parts(text), recordingMs = Ttml.length(text) ?: 0)
            }
            else -> {
                val lines = LrcParser.parse(text, durationMs)
                when {
                    lines.isNotEmpty() -> Lyrics.Synced(lines, source)
                    // An .lrc with no times, or a .txt: the words as written.
                    else -> Lyrics.Plain(text, source)
                }
            }
        }
        return lyrics?.let { LyricsQuality.tidy(it, durationMs) }
    }

    /** UTF-8 unless a byte-order mark says UTF-16; text that isn't valid UTF-8 is read as Windows-1252, as old LRC files often are. */
    internal fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> {
            val decoder = Charsets.UTF_8.newDecoder()
            runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { String(bytes, Charset.forName("windows-1252")) }
        }
    }

    /** Whether lyric files next to songs can be read without a folder grant. */
    fun readsFilesDirectly(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()
}
