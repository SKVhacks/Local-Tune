package com.opentune.playback

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

/**
 * A song file's own loudness tag, for loudness normalization: ReplayGain's
 * track gain as FLAC, Ogg and Opus (Vorbis comments), MP3 (ID3 `TXXX`) and
 * M4A (iTunes `----` atoms) store it, or Opus's `R128_TRACK_GAIN`.
 */
@OptIn(UnstableApi::class)
object ReplayGain {
    private const val TRACK_GAIN = "replaygain_track_gain"
    private const val R128_TRACK_GAIN = "r128_track_gain"

    /** The gain the tags say to apply, in dB (negative for a loud master); null when they don't say. */
    fun trackGainDb(metadata: Metadata?): Float? {
        metadata ?: return null
        for (i in 0 until metadata.length()) {
            val entry = metadata[i]
            val (key, value) = when (entry) {
                is VorbisComment -> entry.key to entry.value
                is TextInformationFrame -> (entry.description ?: entry.id) to entry.value
                is InternalFrame -> entry.description to entry.text
                else -> continue
            }
            when (key.lowercase()) {
                TRACK_GAIN -> parseDb(value)?.let { return it }
                // Q7.8 fixed point against EBU R128's -23 LUFS; ReplayGain's reference is about 5 dB louder.
                R128_TRACK_GAIN -> value.trim().toIntOrNull()?.let { return it / 256f + 5f }
            }
        }
        return null
    }

    /** "-7.31 dB", "+1.2dB" or "-7.31". */
    internal fun parseDb(text: String): Float? =
        text.trim().removeSuffix("dB").removeSuffix("db").removeSuffix("DB").trim().removePrefix("+").toFloatOrNull()
            ?.takeIf { it in -60f..30f }
}
