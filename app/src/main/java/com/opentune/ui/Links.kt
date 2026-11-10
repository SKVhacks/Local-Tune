package com.opentune.ui

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something handed to the app from outside, for the UI to act on. */
sealed interface LinkTarget {
    /** "Play … on OpenTune" from the assistant: an artist, album or song in the library, or everything when blank. */
    data class PlaySearch(val query: String) : LinkTarget
    /** An audio file opened with OpenTune from a file manager or another app. */
    data class PlayFile(val uri: Uri) : LinkTarget
    /** The notification or a widget asked for the player. */
    data object Player : LinkTarget
}

object Links {
    const val ACTION_OPEN_PLAYER = "com.opentune.action.OPEN_PLAYER"

    private val _incoming = MutableStateFlow<LinkTarget?>(null)
    val incoming: StateFlow<LinkTarget?> = _incoming.asStateFlow()

    fun receive(intent: Intent?) {
        intent ?: return
        _incoming.value = when {
            intent.action == ACTION_OPEN_PLAYER -> LinkTarget.Player
            intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ->
                LinkTarget.PlaySearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
            intent.action == Intent.ACTION_VIEW && intent.type?.startsWith("audio/") == true -> intent.data?.let(LinkTarget::PlayFile)
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme in setOf("content", "file") -> intent.data?.let(LinkTarget::PlayFile)
            else -> null
        } ?: return
    }

    fun consumed() {
        _incoming.value = null
    }
}
