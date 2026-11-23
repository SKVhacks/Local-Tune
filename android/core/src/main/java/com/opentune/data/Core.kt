package com.opentune.data

import android.app.Application
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.AppSettings
import com.opentune.playback.QueueStore

/** Starts the shared services, in the order they depend on each other. */
object Core {
    /**
     * [phone] is false on the watch, which leaves out what only the phone
     * and TV app does.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun init(app: Application, phone: Boolean = true) {
        // Media3's warnings and errors (a failed load, a decoder that won't
        // start) into the app's own log as well as logcat, for "Copy log".
        androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
            private val system = androidx.media3.common.util.Log.Logger.DEFAULT
            override fun d(tag: String, message: String, throwable: Throwable?) = system.d(tag, message, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = system.i(tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) {
                AppLog.add('W', tag, message, throwable)
                system.w(tag, message, throwable)
            }
            override fun e(tag: String, message: String, throwable: Throwable?) {
                AppLog.add('E', tag, message, throwable)
                system.e(tag, message, throwable)
            }
        })
        AppSettings.init(app)
        History.init(app)
        com.opentune.data.history.ListenedCollections.init(app)
        QueueStore.init(app)
        com.opentune.data.library.ResumePoints.init(app)
        LibraryStore.init(app)
        com.opentune.data.local.LocalMusic.init(app)
        com.opentune.data.lyrics.LocalLyrics.init(app)
    }

    /**
     * Saves at once what's still waiting for its save (each store waits a moment
     * after a change, so a run of changes is written once): the app is leaving
     * the screen or the player is closing, and the process may go any moment.
     */
    fun flush() {
        LibraryStore.flush()
        History.flush()
        com.opentune.data.history.ListenedCollections.flush()
    }
}
