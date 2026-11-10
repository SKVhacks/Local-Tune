package com.opentune.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Size
import com.opentune.data.DebugLog as Log
import com.opentune.data.local.LocalMusic
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves album covers to Android Auto, which only loads `content://`
 * artwork it has been granted (see [CarArtwork]). `album/<id>` is the
 * cover of MediaStore album `id`, read once into the cache. The provider
 * isn't exported: the browser that asked for a list is granted read access
 * to the artwork in it.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val file = when (uri.pathSegments.firstOrNull()) {
            "album" -> uri.pathSegments.getOrNull(1)?.toLongOrNull()?.let(::albumCover)
            else -> null
        } ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun albumCover(albumId: Long): File? {
        val context = context ?: return null
        val dir = File(context.cacheDir, "car-art").apply { mkdirs() }
        val file = File(dir, "album-$albumId.jpg")
        if (file.length() > 0) return file
        return runCatching {
            val source = Uri.parse(LocalMusic.albumArt(albumId))
            val tmp = File(dir, "${file.name}.tmp")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bitmap = context.contentResolver.loadThumbnail(source, Size(512, 512), null)
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } else {
                context.contentResolver.openInputStream(source)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null
            }
            tmp.renameTo(file)
            file.takeIf { it.length() > 0 }
        }.onFailure { Log.d(TAG, "No cover for album $albumId: ${it.message}") }.getOrNull()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "ArtworkProvider"
    }
}
