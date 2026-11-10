package com.opentune.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.QueueTier
import com.opentune.data.model.Song

/**
 * Queue entries point at `opentune://track/<videoId>` rather than at a stream
 * URL. [PlaybackService] swaps that for a real URL only when ExoPlayer opens
 * the item, so queueing twenty-five tracks costs nothing up front and no
 * entry sits in the queue holding a URL that has gone stale by its turn.
 */
private const val TRACK_SCHEME = "opentune"
private const val TRACK_HOST = "track"
private const val EXTRA_DURATION_TEXT = "durationText"
private const val EXTRA_ALBUM = "album"
private const val EXTRA_THUMB = "thumb"
private const val EXTRA_EXPLICIT = "explicit"
private const val EXTRA_ARTIST_ID = "artistId"
private const val EXTRA_ALBUM_ID = "albumId"
private const val EXTRA_SMART_SHUFFLE = "smartShuffle"
private const val EXTRA_TIER = "tier"

/** Every song is a file on the phone, played straight from MediaStore. */
fun trackUri(videoId: String): Uri =
    if (LocalMusic.isLocal(videoId)) LocalMusic.contentUri(videoId) else streamUri(videoId, upgraded = false)

/** A YouTube track's queue URI; [upgraded] asks for the better stream found mid-play. */
fun streamUri(videoId: String, upgraded: Boolean): Uri =
    Uri.Builder().scheme(TRACK_SCHEME).authority(TRACK_HOST).appendPath(videoId)
        .apply { if (upgraded) appendQueryParameter(QUALITY_PARAM, QUALITY_UPGRADED) }
        .build()

/** The video id a [trackUri] stands for, or null for any other URI. */
fun videoIdOf(uri: Uri): String? =
    if (uri.scheme == TRACK_SCHEME && uri.authority == TRACK_HOST) uri.lastPathSegment else null

fun isUpgradedUri(uri: Uri): Boolean = uri.getQueryParameter(QUALITY_PARAM) == QUALITY_UPGRADED

fun externalStreamUri(videoId: String, rendition: String): Uri =
    streamUri(videoId, upgraded = false).buildUpon().appendQueryParameter("external", rendition).build()

fun externalStreamKeyOf(uri: Uri): String? =
    if (videoIdOf(uri) != null) uri.getQueryParameter("external")?.takeIf { it.isNotBlank() } else null

/** The song cache key: the video id, kept apart for an upgraded copy. */
fun cacheKeyOf(uri: Uri): String? = videoIdOf(uri)?.let { id ->
    externalStreamKeyOf(uri)?.let { "$id:external:$it" } ?: if (isUpgradedUri(uri)) "$id:hq" else id
}

private const val QUALITY_PARAM = "q"
private const val QUALITY_UPGRADED = "hq"

fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(trackUri(videoId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setAlbumTitle(albumName)
                .setExtras(
                    Bundle().apply {
                        putString(EXTRA_DURATION_TEXT, durationText)
                        putString(EXTRA_ALBUM, albumName)
                        putString(EXTRA_THUMB, thumbnailUrl)
                        isExplicit?.let { putBoolean(EXTRA_EXPLICIT, it) }
                        // Kept so the player can open the artist's and album's pages.
                        artistId?.let { putString(EXTRA_ARTIST_ID, it) }
                        albumId?.let { putString(EXTRA_ALBUM_ID, it) }
                        if (smartShuffle) putBoolean(EXTRA_SMART_SHUFFLE, true)
                        if (queueTier != QueueTier.CONTEXT) putString(EXTRA_TIER, queueTier.name)
                    },
                )
                .build(),
        )
        .build()

fun MediaItem.toSong(): Song =
    Song(
        videoId = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        thumbnailUrl = mediaMetadata.extras?.getString(EXTRA_THUMB) ?: mediaMetadata.artworkUri?.toString(),
        durationText = mediaMetadata.extras?.getString(EXTRA_DURATION_TEXT),
        albumName = mediaMetadata.extras?.getString(EXTRA_ALBUM) ?: mediaMetadata.albumTitle?.toString(),
        isExplicit = mediaMetadata.extras?.takeIf { it.containsKey(EXTRA_EXPLICIT) }?.getBoolean(EXTRA_EXPLICIT),
        artistId = mediaMetadata.extras?.getString(EXTRA_ARTIST_ID),
        albumId = mediaMetadata.extras?.getString(EXTRA_ALBUM_ID),
        smartShuffle = mediaMetadata.extras?.getBoolean(EXTRA_SMART_SHUFFLE) == true,
        queueTier = mediaMetadata.extras?.getString(EXTRA_TIER)?.let { t -> QueueTier.entries.firstOrNull { it.name == t } } ?: QueueTier.CONTEXT,
    )

/** Update credits for this exact recording without touching its selected audio or queue extras. */
internal fun MediaItem.withTrackArtistCredit(credit: Song): MediaItem {
    if (mediaId != credit.videoId || !com.opentune.data.model.isPlaceholderArtist(mediaMetadata.artist?.toString()) ||
        com.opentune.data.model.isPlaceholderArtist(credit.artist)) return this
    val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
        remove(EXTRA_ARTIST_ID)
        credit.artistId?.let { putString(EXTRA_ARTIST_ID, it) }
    }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setArtist(credit.artist).setExtras(extras).build()).build()
}

/** Which part of the queue this entry is in. */
val MediaItem.queueTier: QueueTier
    get() = mediaMetadata.extras?.getString(EXTRA_TIER)?.let { t -> QueueTier.entries.firstOrNull { it.name == t } } ?: QueueTier.CONTEXT

/**
 * Queue indices in the order they will play from here: the current item
 * first, then what follows it with shuffle applied and repeat ignored.
 */
fun Player.upcomingPlayOrder(): List<Int> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptyList()
    val out = mutableListOf(currentMediaItemIndex)
    var index = currentMediaItemIndex
    while (true) {
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
        if (index == C.INDEX_UNSET || out.size > timeline.windowCount) break
        out += index
    }
    return out
}
