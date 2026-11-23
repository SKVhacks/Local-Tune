package com.opentune.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * An artist's own picture, from inside their songs' files. Tags can carry more
 * than the cover: ID3 (MP3 and others) and FLAC mark each picture with what it
 * shows, and some taggers put the artist's photo in as "lead artist", "artist"
 * or "band". The first of an artist's songs that has one gives it.
 *
 * When none does, and it's allowed (InterfaceSettings.onlineArtistPictures),
 * Deezer's public artist search gives one: free, no key, large photos, and good
 * for Indian artists too. Only the artist's name is sent.
 *
 * Found pictures are kept as files in the cache, and so is not finding one, so
 * each artist is looked up once. Null when there's none: the cover stands in.
 * Not finding one while offline isn't kept, so it's tried again once online.
 */
object ArtistImages {
    private val memory = ConcurrentHashMap<String, String>()
    private val lock = Mutex()

    /** What's already known, without reading anything: a picture's uri, "" for none, null for not looked yet. */
    fun known(context: Context, artist: LocalMusic.Artist): String? {
        memory[artist.id]?.let { return it }
        val dir = dir(context)
        val image = File(dir, key(artist) + ".img")
        if (image.exists()) return android.net.Uri.fromFile(image).toString().also { memory[artist.id] = it }
        if (File(dir, key(artist) + ".none").exists()) return "".also { memory[artist.id] = it }
        return null
    }

    /** The artist's picture as a file uri, or null when none of their songs carries one. */
    suspend fun imageFor(context: Context, artist: LocalMusic.Artist): String? = withContext(Dispatchers.IO) {
        known(context, artist)?.let { return@withContext it.ifEmpty { null } }
        lock.withLock {
            known(context, artist)?.let { return@withLock it.ifEmpty { null } }
            val dir = dir(context)
            val embedded = artist.tracks.asSequence().take(MAX_SONGS).firstNotNullOfOrNull { track ->
                runCatching {
                    context.contentResolver.openInputStream(LocalMusic.contentUri(track.song.videoId))?.use { picture(it) }
                }.getOrNull()
            }
            val online = com.opentune.data.settings.AppSettings.ui.value.onlineArtistPictures
            val offline = online && !com.opentune.data.settings.AppSettings.isOnline()
            val found = embedded ?: if (online && !offline) runCatching { deezer(artist.name) }.getOrNull() else null
            if (found == null) {
                // Offline: no answer yet, so it's asked again later.
                if (offline) return@withLock null
                File(dir, key(artist) + ".none").createNewFile()
                memory[artist.id] = ""
                null
            } else {
                val file = File(dir, key(artist) + ".img")
                file.writeBytes(found)
                android.net.Uri.fromFile(file).toString().also { memory[artist.id] = it }
            }
        }
    }

    private fun dir(context: Context) = File(context.cacheDir, "artist_pictures").apply { mkdirs() }

    // Changes when the artist's songs do, so a newly tagged song gets looked at,
    // and with the online lookup's switch, so turning it on looks again.
    private fun key(artist: LocalMusic.Artist) = Integer.toHexString(
        (artist.id + "#" + artist.tracks.size + "#" + com.opentune.data.settings.AppSettings.ui.value.onlineArtistPictures).hashCode(),
    )

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(15))
            .build()
    }

    /**
     * The artist's photo from Deezer's public search, when an artist of that name
     * (letters and digits compared, case aside) has a real one, not the blank stand-in.
     */
    private fun deezer(name: String): ByteArray? {
        val url = "https://api.deezer.com/search/artist?limit=5&q=" + java.net.URLEncoder.encode(name, "UTF-8")
        val body = http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else null } ?: return null
        val results = kotlinx.serialization.json.Json.parseToJsonElement(body)
            .let { (it as? kotlinx.serialization.json.JsonObject)?.get("data") as? kotlinx.serialization.json.JsonArray } ?: return null
        val wanted = plain(name)
        val match = results.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.firstOrNull { o ->
            plain((o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()) == wanted
        } ?: return null
        val picture = listOf("picture_xl", "picture_big").firstNotNullOfOrNull { k ->
            (match[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.startsWith("http") }
        } ?: return null
        // Artists without a photo get one with no image id in its path.
        if ("/artist//" in picture) return null
        return http.newCall(okhttp3.Request.Builder().url(picture).build()).execute().use { r ->
            if (r.isSuccessful) r.body.bytes().takeIf { it.size > 2048 } else null
        }
    }

    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The artist picture in one file's tags, the best kind first, or null. */
    internal fun picture(input: InputStream): ByteArray? {
        val data = DataInputStream(input.buffered(64 * 1024))
        val magic = ByteArray(4)
        data.readFully(magic, 0, 3)
        return when {
            magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() -> id3(data)
            magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() && magic[2] == 'a'.code.toByte() -> {
                data.readFully(magic, 3, 1)
                if (magic[3] == 'C'.code.toByte()) flac(data) else null
            }
            else -> null
        }
    }

    /** ID3v2.3 / v2.4 APIC frames (and v2.2 PIC). */
    private fun id3(data: DataInputStream): ByteArray? {
        val major = data.readUnsignedByte()
        data.readUnsignedByte() // revision
        val flags = data.readUnsignedByte()
        val tagSize = syncsafe(data.readInt())
        if (tagSize > MAX_TAG) return null
        val tag = ByteArray(tagSize).also { data.readFully(it) }
        var at = 0
        if (flags and 0x40 != 0 && major >= 3) {
            // An extended header, skipped.
            val ext = if (major == 4) syncsafe(int(tag, 0)) else int(tag, 0) + 4
            at += ext
        }
        val pictures = mutableListOf<Pair<Int, ByteArray>>()
        val idLen = if (major == 2) 3 else 4
        val headLen = if (major == 2) 6 else 10
        while (at + headLen <= tag.size) {
            val id = String(tag, at, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = when (major) {
                2 -> ((tag[at + 3].toInt() and 0xFF) shl 16) or ((tag[at + 4].toInt() and 0xFF) shl 8) or (tag[at + 5].toInt() and 0xFF)
                4 -> syncsafe(int(tag, at + 4))
                else -> int(tag, at + 4)
            }
            val body = at + headLen
            if (size <= 0 || body + size > tag.size) break
            if (id == "APIC" || id == "PIC") apic(tag, body, size, major)?.let { pictures += it }
            at = body + size
        }
        return best(pictures)
    }

    /** One APIC/PIC frame: (picture type, image bytes). */
    private fun apic(tag: ByteArray, start: Int, size: Int, major: Int): Pair<Int, ByteArray>? {
        val end = start + size
        var at = start
        val encoding = tag[at++].toInt()
        if (major == 2) at += 3 else { while (at < end && tag[at] != 0.toByte()) at++; at++ } // image format / mime
        if (at >= end) return null
        val type = tag[at++].toInt() and 0xFF
        if (type !in WANTED) return null
        // The description, ended by one zero byte, or two in UTF-16.
        if (encoding == 1 || encoding == 2) {
            while (at + 1 < end && !(tag[at] == 0.toByte() && tag[at + 1] == 0.toByte())) at += 2
            at += 2
        } else {
            while (at < end && tag[at] != 0.toByte()) at++
            at++
        }
        if (at >= end) return null
        return type to tag.copyOfRange(at, end)
    }

    /** FLAC PICTURE metadata blocks. */
    private fun flac(data: DataInputStream): ByteArray? {
        val pictures = mutableListOf<Pair<Int, ByteArray>>()
        while (true) {
            val header = try { data.readUnsignedByte() } catch (_: EOFException) { break }
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
            if (type == 6 && length in 32..MAX_TAG) {
                val block = ByteArray(length).also { data.readFully(it) }
                val kind = int(block, 0)
                if (kind in WANTED) {
                    var at = 4
                    at += 4 + int(block, at) // mime
                    at += 4 + int(block, at) // description
                    at += 16 // width, height, depth, colours
                    val size = int(block, at)
                    at += 4
                    if (size > 0 && at + size <= block.size) pictures += kind to block.copyOfRange(at, at + size)
                }
            } else {
                var left = length.toLong()
                while (left > 0) { val n = data.skip(left); if (n <= 0) break; left -= n }
            }
            if (last) break
        }
        return best(pictures)
    }

    private fun best(pictures: List<Pair<Int, ByteArray>>) =
        pictures.minByOrNull { WANTED.indexOf(it.first) }?.second?.takeIf { it.size > 512 }

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun syncsafe(v: Int) = ((v shr 24) and 0x7F shl 21) or ((v shr 16) and 0x7F shl 14) or ((v shr 8) and 0x7F shl 7) or (v and 0x7F)

    /** Picture kinds that show the artist, best first: lead artist, artist, band, then conductor, composer, lyricist. */
    private val WANTED = listOf(7, 8, 10, 9, 11, 12)
    private const val MAX_TAG = 32 * 1024 * 1024
    private const val MAX_SONGS = 8
}
