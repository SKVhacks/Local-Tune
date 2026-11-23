package com.opentune.data.lyrics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A song's lyrics, from the `.ttml`, `.lrc` or `.txt` files kept with it on
 * the phone (see [LocalLyrics]). Nothing is looked up online.
 */
object LyricsRepository {
    suspend fun lyricsFor(videoId: String, title: String, artist: String, durationMs: Long): Lyrics? =
        LocalLyrics.find(videoId, title, artist, durationMs)

    /** The song's lyrics, once; a flow so the player can treat every source alike. */
    fun lyricsUpdates(videoId: String, title: String, artist: String, durationMs: Long): Flow<Lyrics?> = flow {
        emit(lyricsFor(videoId, title, artist, durationMs))
    }

    /** Why [lyrics] wouldn't fit a track [durationMs] long; null when they do. */
    fun problemWith(lyrics: Lyrics, durationMs: Long): String? =
        if (lyrics is Lyrics.Synced && LyricsQuality.untimed(lyrics)) "Not really timed"
        else LyricsQuality.problem(LyricsQuality.tidy(lyrics, durationMs), durationMs)

    internal fun rank(lyrics: Lyrics?): Int = when (lyrics) {
        null -> 0
        is Lyrics.Plain -> 1
        is Lyrics.Synced -> if (lyrics.lines.any { it.wordSynced }) WORD_SYNCED else LINE_SYNCED
    }

    internal const val LINE_SYNCED = 2
    internal const val WORD_SYNCED = 3
}
