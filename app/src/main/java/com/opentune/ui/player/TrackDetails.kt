package com.opentune.ui.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.data.local.LocalMusic
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the file says about itself. */
private data class FileFacts(
    val path: String?,
    val sizeBytes: Long?,
    val format: String?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val bitrate: Int?,
    val year: String?,
    val genre: String?,
    val track: String?,
    val composer: String?,
    val albumArtist: String?,
    val lyrics: String?,
)

@Composable
internal fun TrackDetailsDialog(song: Song, durationMs: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var facts by remember(song.videoId) { mutableStateOf<FileFacts?>(null) }
    LaunchedEffect(song.videoId) {
        if (!LocalMusic.isLocal(song.videoId)) return@LaunchedEffect
        facts = withContext(Dispatchers.IO) { runCatching { readFacts(context, song, durationMs) }.getOrNull() }
    }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("Track details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Detail("Title", song.title)
            Detail("Artist", song.artist)
            song.albumName?.let { Detail("Album", it) }
            facts?.albumArtist?.takeIf { it.isNotBlank() && it != song.artist }?.let { Detail("Album artist", it) }
            facts?.track?.takeIf { it.isNotBlank() && it != "0" }?.let { Detail("Track", it) }
            facts?.year?.takeIf { it.isNotBlank() && it != "0" }?.let { Detail("Year", it) }
            facts?.genre?.takeIf { it.isNotBlank() }?.let { Detail("Genre", it) }
            facts?.composer?.takeIf { it.isNotBlank() }?.let { Detail("Composer", it) }
            if (durationMs > 0) Detail("Duration", com.opentune.ui.formatTime(durationMs))
            facts?.let { f ->
                val audio = listOfNotNull(
                    f.format,
                    f.bitDepth?.let { "$it-bit" },
                    f.sampleRate?.let { "%.1f kHz".format(it / 1000f).replace(".0 ", " ") },
                    f.channels?.let { if (it == 1) "mono" else if (it == 2) "stereo" else "$it channels" },
                    f.bitrate?.let { "${it / 1000} kbps" },
                ).joinToString(" · ")
                if (audio.isNotBlank()) Detail("Audio", audio)
                f.sizeBytes?.let { Detail("Size", "%.1f MB".format(it / 1_048_576.0)) }
                f.path?.let { Detail("File", it) }
                Detail("Lyrics", f.lyrics ?: "No lyric file found")
            }
        }
    }, confirmButton = { SheetButton("Close", onClick = onDismiss, closes = true) })
}

private suspend fun readFacts(context: android.content.Context, song: Song, durationMs: Long): FileFacts {
    val uri = LocalMusic.contentUri(song.videoId)
    @Suppress("DEPRECATION")
    val (path, size) = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
    } ?: (null to null)
    val retriever = MediaMetadataRetriever()
    val meta = runCatching {
        retriever.setDataSource(context, uri)
        fun m(key: Int) = retriever.extractMetadata(key)
        listOf(
            m(MediaMetadataRetriever.METADATA_KEY_YEAR),
            m(MediaMetadataRetriever.METADATA_KEY_GENRE),
            m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
            m(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            m(MediaMetadataRetriever.METADATA_KEY_BITRATE),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) m(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE) else null,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) else null,
        )
    }.getOrDefault(List(8) { null }).also { runCatching { retriever.release() } }
    var format: String? = null
    var rate: Int? = meta[7]?.toIntOrNull()
    var channels: Int? = null
    var bits: Int? = meta[6]?.toIntOrNull()
    runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            format = when (mime) {
                "audio/flac" -> "FLAC"
                "audio/alac" -> "ALAC"
                "audio/mpeg" -> "MP3"
                "audio/mp4a-latm" -> "AAC"
                "audio/opus" -> "Opus"
                "audio/vorbis" -> "Vorbis"
                "audio/raw" -> "PCM (WAV)"
                "audio/ac3" -> "AC-3"
                "audio/eac3" -> "E-AC-3"
                else -> mime.removePrefix("audio/").uppercase()
            }
            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = rate ?: f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (bits == null && f.containsKey("bits-per-sample")) bits = f.getInteger("bits-per-sample")
            break
        }
        ex.release()
    }
    val lyrics = LyricsRepository.lyricsFor(song.videoId, song.title, song.artist, durationMs)?.let { l ->
        val kind = when {
            l is com.opentune.data.lyrics.Lyrics.Synced && l.lines.any { it.wordSynced } -> "Word by word"
            l is com.opentune.data.lyrics.Lyrics.Synced -> "Line by line"
            else -> "Plain text"
        }
        "$kind · ${l.source.removePrefix("File · ")}"
    }
    return FileFacts(
        path = path, sizeBytes = size, format = format, sampleRate = rate, bitDepth = bits, channels = channels,
        bitrate = meta[5]?.toIntOrNull()?.takeIf { format !in setOf("FLAC", "ALAC", "PCM (WAV)") },
        year = meta[0], genre = meta[1], track = meta[2], composer = meta[3], albumArtist = meta[4], lyrics = lyrics,
    )
}

@Composable private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
