package com.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities

/**
 * Spatial and surround formats (Dolby Digital, Digital Plus and Atmos, AC-4, TrueHD,
 * DTS, MPEG-H) are played only by the phone's own licensed decoders, or passed through
 * whole to a TV or soundbar. The bundled FFmpeg decoder is kept from them: it would
 * drop Atmos's height and objects and fold the rest to stereo, so a song would play,
 * but not as made. FFmpeg still plays everything else the phone can't (Apple Lossless).
 */
@UnstableApi
internal object SpatialGuard {
    private val SPATIAL = setOf(
        MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4,
        MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS,
        MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_MPEGH_MHA1, MimeTypes.AUDIO_MPEGH_MHM1,
    )

    fun isSpatial(format: Format) = format.sampleMimeType in SPATIAL

    /** Swaps the FFmpeg audio renderer in [out] for one that says no to spatial formats. */
    fun guard(out: ArrayList<Renderer>) {
        for (i in out.indices) {
            val r = out[i]
            if (!r.javaClass.name.contains("Ffmpeg", ignoreCase = true)) continue
            out[i] = object : ForwardingRenderer(r) {
                private val caps = object : RendererCapabilities by r.capabilities {
                    override fun supportsFormat(format: Format): Int =
                        if (isSpatial(format)) RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                        else r.capabilities.supportsFormat(format)
                }
                override fun getCapabilities(): RendererCapabilities = caps
            }
        }
    }

    /** The song's spatial format, when it has audio but none of it could be played. */
    fun refused(tracks: Tracks): Format? {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty() || audio.any { it.isSupported }) return null
        return audio.flatMap { g -> (0 until g.length).map(g::getTrackFormat) }.firstOrNull(::isSpatial)
    }

    fun name(format: Format) = when (format.sampleMimeType) {
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_MPEGH_MHA1, MimeTypes.AUDIO_MPEGH_MHM1 -> "MPEG-H 3D Audio"
        else -> "DTS"
    }
}
