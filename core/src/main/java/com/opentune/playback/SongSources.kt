package com.opentune.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

/**
 * Where songs are read from: every song is a file on the phone, read from
 * its `content://` address by the platform data source.
 *
 * [PlaybackService]'s player reads through these, and so do short-lived
 * players elsewhere (the music behind Wrapped), so they play songs exactly
 * as the main player does.
 */
@OptIn(UnstableApi::class)
internal class SongSources(context: Context) {
    val direct: DataSource.Factory = DefaultDataSource.Factory(context)
    val routing: DataSource.Factory = direct
}
