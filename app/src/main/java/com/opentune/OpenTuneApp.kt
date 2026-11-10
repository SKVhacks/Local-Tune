package com.opentune

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

class OpenTuneApp : Application(), SingletonImageLoader.Factory {
    /**
     * Album covers read from the phone (no network fetcher: nothing is loaded
     * from the web), a fifth of memory for decoded images and 100 MB on disk,
     * with a short cross-fade as each one lands.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(200)
            .build()

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is kept for Export diagnostics.
        com.opentune.data.CrashLog.install(this)
        // The home-screen widget follows the shared player service.
        com.opentune.data.AppHooks.onPlayerChanged = { context, player -> com.opentune.widget.NowPlayingWidget.refresh(context, player) }
        com.opentune.data.AppHooks.onPlayerStopped = { context -> com.opentune.widget.NowPlayingWidget.stopped(context) }
        java.util.Locale.setDefault(com.opentune.ui.theme.AppLanguages.wrap(this).resources.configuration.locales[0])
        com.opentune.data.Core.init(this)
    }
}
