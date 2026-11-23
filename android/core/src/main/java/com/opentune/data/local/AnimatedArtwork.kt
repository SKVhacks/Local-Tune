package com.opentune.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Apple Music's tall animated cover kept beside a song: a video in the song's
 * own folder named like `tall_animated_artwork.mp4` ("Tall Animated Artwork",
 * "song - tall-animated-artwork.mov" and so on; case, spaces, dashes and
 * underscores don't matter). Played full screen behind the player, as Apple does.
 *
 * Videos are read through the phone's media library, which needs Android 13's
 * "Photos and videos" permission ([permission]); older phones read them with
 * the storage permission the music already has.
 */
object AnimatedArtwork {
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Bumped when the permission is granted, so the playing song looks again. */
    private val _changes = MutableStateFlow(0)
    val changes = _changes.asStateFlow()
    fun refresh() { _changes.value++ }

    fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True for a file name meaning the tall animated cover. */
    fun isTall(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in VIDEO_EXTENSIONS) return false
        val words = name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), " ")
        return Regex("""\btall animated (art ?work|cover)\b""").containsMatchIn(words)
    }

    /** The tall animated cover in the folder of the local song [mediaId], as a playable uri, or null. */
    suspend fun tallFor(context: Context, mediaId: String): String? = withContext(Dispatchers.IO) {
        if (!mediaId.startsWith(LocalMusic.PREFIX)) return@withContext null
        runCatching {
            val audio = LocalMusic.contentUri(mediaId)
            @Suppress("DEPRECATION")
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                arrayOf(MediaStore.Audio.Media.DATA, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME)
            else arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATA)
            val (path, relative, volume) = context.contentResolver.query(audio, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Triple(c.getString(0), c.getString(1), c.getString(2))
                else Triple(c.getString(0), null, null)
            } ?: return@runCatching null
            // Through the media library first: that's what the permission opens.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && allowed(context) && relative != null) {
                val videos = MediaStore.Video.Media.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL)
                context.contentResolver.query(
                    videos,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(relative),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (isTall(c.getString(1) ?: continue)) return@runCatching ContentUris.withAppendedId(videos, c.getLong(0)).toString()
                    }
                }
            }
            // Else straight from the folder, where the phone lets it be read.
            val dir = path?.let { File(it).parentFile } ?: return@runCatching null
            dir.listFiles()?.firstOrNull { isTall(it.name) && it.canRead() }?.let { android.net.Uri.fromFile(it).toString() }
        }.getOrNull()
    }

    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv")
}
