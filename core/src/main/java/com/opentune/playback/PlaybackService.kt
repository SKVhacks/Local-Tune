package com.opentune.playback

import com.opentune.data.model.durationMillis
import kotlinx.coroutines.ensureActive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import android.os.Bundle
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionError
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.future
import com.opentune.data.DebugLog as Log
import com.opentune.data.isYouTubeId
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SongRef
import com.opentune.data.NerdStats
import com.opentune.data.history.History
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.QueueTier
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.playback.dsp.DspAudioProcessor
import com.opentune.playback.dsp.DspParams
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.media3.common.Tracks
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Foreground media session hosting playback, the queue, autoplay, the audio
 * chain and listening history.
 *
 * Data path: every song is a file on the phone, read from its MediaStore
 * `content://` address.
 *
 * Audio path: decoder → [DspAudioProcessor] (EQ, tone, balance, widening,
 * levelling, limiter) → ExoPlayer's silence skipping and speed/pitch → output.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    /** What Android Auto and other media browsers can browse; see [CarLibrary]. */
    private val car by lazy { CarLibrary(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var castMirror: CastMirror? = null
    private var radioJob: Job? = null
    private var radioSeed: String? = null

    /** Seeds whose radio added nothing new; asking again would only repeat that. */
    private val exhaustedSeeds = mutableSetOf<String>()

    /** Every track queued since the service started, for "don't repeat songs". */
    private val sessionIds = mutableSetOf<String>()

    /** The item a failed load was last retried for. See [recover]. */
    private var retriedMediaId: String? = null

    private var soundEffects: SoundEffects? = null
    private val dsp = DspAudioProcessor()
    /** The same effects, for the bridge player that carries a song across a quality swap. */
    private val bridgeDsp = DspAudioProcessor()
    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        val context: Context = this

        val songSources = SongSources(context)
        val sources = DefaultMediaSourceFactory(songSources.direct)

        // The main player and the bridge decode the same way: the bridge used to
        // take the phone's default FLAC decoder, which some phones can't play
        // add-on FLAC with, so the swap to it failed and the song stayed on YouTube.
        fun renderersFor(effects: DspAudioProcessor, exclusive: Boolean) = object : DefaultRenderersFactory(context) {
            init {
                setEnableDecoderFallback(true)
                // FFmpeg's decoders come after the phone's own: they take what the phone
                // can't decode, or what's kept from its decoders below.
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
                setMediaCodecSelector { mime, secure, tunneling ->
                    // Apple Lossless always goes to FFmpeg: phones' ALAC decoders are few and
                    // unreliable (Qualcomm's c2.qti.alac.sw.decoder fails on every song, a moment in).
                    if (mime == MimeTypes.AUDIO_ALAC) return@setMediaCodecSelector emptyList()
                    val selector = if (mime == "audio/flac") androidx.media3.exoplayer.mediacodec.MediaCodecSelector.PREFER_SOFTWARE
                        else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    selector.getDecoderInfos(mime, secure, tunneling).filterNot {
                        mime == "audio/flac" && LosslessAudioPolicy.unsafeFlacDecoder(it.name)
                    }
                }
            }
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = com.opentune.playback.dsp.PrecisionAudioSink(
                // The effects run in the precision sink, on float samples, not in Media3's chain.
                // Float is always allowed through; the precision sink decides, song by song, whether to use it.
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain())
                    .apply {
                        // Only the main player takes the DAC exclusively: the bridge opening its
                        // own output would switch the DAC under the song that's playing.
                        if (exclusive) setAudioOutputProvider(ExclusiveUsbOutputProvider(
                            androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider.Builder(context).build()))
                    }
                    .build(),
                effects,
                // Float where asked for, and for an exclusive USB DAC whenever the song has more
                // than 16 bits: float carries 24-bit samples exactly, and they go out whole.
                // Not while the song is stretched in time: Media3 changes speed and pitch, skips
                // silence and nudges a listening room's sync only on 16-bit output, and on float
                // it quietly plays at normal speed. The effects still run on float before that.
                floatWanted = { source ->
                    !stretching() && (
                        AppSettings.playback.value.floatOutput ||
                            (BitPerfectUsb.wanted && source != C.ENCODING_PCM_16BIT && source != C.ENCODING_PCM_8BIT)
                        )
                },
            )
        }
        val renderers = renderersFor(dsp, exclusive = true)

        // Start as soon as a little audio is in: the defaults wait for 2.5 s,
        // which is video-sized caution. A stream starts after half a second buffered
        // and a local file after a quarter second.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(30_000, FAR_BUFFER_MS, 500, 2_000)
            .setBufferDurationsMsForLocalPlayback(5_000, FAR_BUFFER_MS, 250, 500)
            // The byte ceiling decides, not the time one: a whole song at CD
            // quality, a couple of minutes at 24-bit/96 kHz, so a fetched track is
            // fetched once and seeks within it are instant. 8 MB held only about
            // 15 s of hi-res FLAC, and any dip in the connection stalled it.
            .setTargetBufferBytes(FAR_BUFFER_BYTES)
            // However heavy the stream, at least the minimum time ahead is held.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()

        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // On a watch, music waits for Bluetooth headphones rather than playing from
            // its speaker, and Wear OS offers to connect some. Other devices are unaffected.
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioMimeTypes("audio/flac", "audio/alac", "audio/raw").build()
        player.addListener(playerListener)
        player.addAnalyticsListener(PipelineTelemetry)

        val effects = SoundEffects(player)
        soundEffects = effects
        scope.launch { AppSettings.sound.collect(effects::apply) }
        scope.launch {
            combine(AppSettings.equalizer, AppSettings.sound, AppSettings.playback, BitPerfectUsb.status, volumeTick) { eq, sound, pb, bp, _ ->
                // Bit-perfect means nothing but the volume touches the samples.
                if (bp is BitPerfectUsb.Status.Active) {
                    DspParams(outputGainDb = audioManager?.let(BitPerfectUsb::softwareGainDb) ?: 0f)
                } else if (pb.pureOutput) {
                    // Pure output: the samples pass through untouched.
                    DspParams()
                } else {
                    DspParams(eq, sound.bassBoost, pb.spatialAudio, pb.clarity, eightDPeriod = sound.eightD.periodSeconds, spacePeriod = sound.space.periodSeconds)
                }
            }.distinctUntilChanged().collect { dsp.params = it; bridgeDsp.params = it }
        }
        scope.launch {
            AppSettings.playback.map { it.skipSilence }.distinctUntilChanged().collect { player.skipSilenceEnabled = it }
        }
        // Turning autoplay back on near the end of the queue should fetch now,
        // not at the next track change.
        scope.launch {
            AppSettings.playback.map { it.autoplay to it.recommender }.distinctUntilChanged().collect {
                radioJob?.cancel()
                radioSeed = null
                exhaustedSeeds.clear()
                extendQueueIfNeeded()
            }
        }
        scope.launch { trackListening(player) }
        scope.launch {
            AppSettings.playback.map { listOf(it.loudnessNormalization, it.normalizeOnSpeaker, it.volumeLevel, it.pureOutput) }.distinctUntilChanged().collect { applyLoudness() }
        }
        scope.launch {
            AppSettings.playback.map { it.preloadUpcoming }.distinctUntilChanged().collect { on ->
                // ExoPlayer prepares and buffers the next item in play order
                // while this one plays, so it starts from memory.
                player.preloadConfiguration =
                    if (on) ExoPlayer.PreloadConfiguration(PRELOAD_US) else ExoPlayer.PreloadConfiguration.DEFAULT
            }
        }

        scope.launch { runSleepTimer(player) }
        scope.launch {
            AppSettings.playback.map { it.systemEffects to it.pureOutput }.distinctUntilChanged().collect { openEffectSession(player.audioSessionId) }
        }
        crossfade = Crossfade(context, scope, sources, player).also { it.start() }
        // Casting: the device follows this player while one is connected.
        castMirror = CastMirror(context, player, scope)
        scope.launch {
            com.opentune.cast.Cast.session.map { it?.receiver }.distinctUntilChanged().collect { r ->
                if (r != null) castMirror?.attach(r) else castMirror?.detach()
            }
        }
        audioManager = getSystemService(AudioManager::class.java)?.also { am ->
            BitPerfectUsb.init(am)
            am.registerAudioDeviceCallback(deviceCallback, null)
        }
        scope.launch {
            AppSettings.playback.map { it.preferUsbDac }.distinctUntilChanged().collect { applyPreferredDevice() }
        }
        scope.launch {
            AppSettings.playback.map { it.bitPerfectUsb to it.floatOutput }.distinctUntilChanged().collect { applyBitPerfect(reopen = true) }
        }
        scope.launch {
            // Speed or pitch moved off normal (or back), skip silence turned on or off, a room joined
            // or left: the output is set up again, so the song changes over at once rather than at the next.
            combine(AppSettings.sound, AppSettings.playback) { _, _ -> stretching() }
                .distinctUntilChanged().drop(1).collect { rebuildOutput() }
        }
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), VOLUME_RECEIVER_FLAGS)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            com.opentune.data.AppHooks.openApp(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()
        // Earlier versions posted the song as a Live Update on its own channel. That's
        // gone; clear the channel so it doesn't linger in the phone's settings.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getSystemService(android.app.NotificationManager::class.java)?.run {
                cancel(7_160)
                deleteNotificationChannel("live_update")
            }
        }
        // Likes changed anywhere (the app, the widget) show on the heart at once.
        scope.launch { LibraryStore.liked.collect { refreshButtons() } }
        scope.launch { skipDisliked(player) }

        restoreQueue(player)
        scope.launch { keepQueueSaved(player) }
    }

    /**
     * A song disliked anywhere (the app, the notification, a car) stops being
     * heard: if it's playing it's skipped, and the copies of it still to come
     * leave the queue. Songs already played stay, so going back still works.
     */
    private suspend fun skipDisliked(player: ExoPlayer) {
        var before: Set<String>? = null
        LibraryStore.disliked.collect { now ->
            val fresh = before?.let { now - it }.orEmpty()
            before = now
            if (fresh.isEmpty()) return@collect
            val current = player.currentMediaItemIndex
            for (i in player.mediaItemCount - 1 downTo current + 1) {
                if (player.getMediaItemAt(i).mediaId in fresh) player.removeMediaItem(i)
            }
            if (player.currentMediaItem?.mediaId in fresh) {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
            }
        }
    }

    /**
     * Puts back the queue from last time, paused where it was left and not
     * prepared: nothing is fetched until play is pressed.
     */
    private fun restoreQueue(player: ExoPlayer) {
        if (player.mediaItemCount > 0) return
        val saved = QueueStore.load() ?: return
        val items = saved.songs.mapIndexed { i, ref ->
            ref.toSong().copy(queueTier = saved.tiers?.getOrNull(i) ?: QueueTier.CONTEXT).toMediaItem()
        }
        // A saved queue with no songs in it has no index to start at.
        if (items.isEmpty()) return
        player.setMediaItems(items, saved.index.coerceIn(items.indices), saved.positionMs.coerceAtLeast(0))
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
    }

    /**
     * With the whole queue on repeat, songs queued by hand play once: when a
     * song of the list comes round, the ones already played leave.
     */
    private fun dropPlayedHandQueued() {
        val player = mediaSession?.player ?: return
        if (player.repeatMode != Player.REPEAT_MODE_ALL || player.shuffleModeEnabled) return
        val tiers = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueTier }
        QueueTimeline.playedUser(tiers, player.currentMediaItemIndex).asReversed().forEach(player::removeMediaItem)
    }

    /** Saves the queue every few seconds while it changes or plays. */
    private suspend fun keepQueueSaved(player: ExoPlayer) {
        var last: QueueStore.Saved? = null
        while (true) {
            delay(QUEUE_SAVE_MS)
            if (player.mediaItemCount == 0) continue
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            val tiers = items.map { it.queueTier }
            val saved = QueueStore.Saved(
                items.map { SongRef.of(it.toSong()) }, player.currentMediaItemIndex, player.currentPosition,
                tiers = tiers.takeIf { t -> t.any { it != QueueTier.CONTEXT } },
                shuffle = player.shuffleModeEnabled,
                repeat = player.repeatMode,
            )
            if (saved == last) continue
            last = saved
            withContext(Dispatchers.IO) { QueueStore.save(saved) }
        }
    }

    /**
     * Pauses at the time the sleep timer names, or at the end of the current
     * track (ExoPlayer's own pause-at-end, so it lands on the boundary).
     */
    private suspend fun runSleepTimer(player: ExoPlayer) {
        SleepTimer.state.collectLatest { state ->
            player.pauseAtEndOfMediaItems = state is SleepTimer.State.EndOfTrack
            try {
                when (state) {
                    is SleepTimer.State.At -> {
                        // Winding down: the volume eases away over the last few minutes.
                        val fadeMs = SleepTimer.fadeMsFor(state.endsAtMs - state.startedAtMs)
                        while (true) {
                            val left = state.endsAtMs - System.currentTimeMillis()
                            if (left <= 0) break
                            if (AppSettings.playback.value.sleepWindDown && left < fadeMs) {
                                val f = 1f - left.toFloat() / fadeMs
                                com.opentune.playback.dsp.DspAudioProcessor.masterGain = SleepTimer.gainAt(f)
                                SleepTimer.setWindDown(f)
                                delay(200)
                            } else {
                                delay(minOf(left - fadeMs, 1_000L).coerceAtLeast(50))
                            }
                        }
                        player.pause()
                        SleepTimer.cancel()
                    }
                    SleepTimer.State.EndOfTrack -> {
                        // The last seconds of the song fade out, then pauseAtEndOfMediaItems pauses.
                        val stopped = scope.launch { playWhenReadyFlow(player).first { !it } }
                        while (stopped.isActive) {
                            val left = player.duration - player.currentPosition
                            if (AppSettings.playback.value.sleepWindDown && player.duration > 0 && left in 0 until END_FADE_MS) {
                                val f = 1f - left.toFloat() / END_FADE_MS
                                com.opentune.playback.dsp.DspAudioProcessor.masterGain = SleepTimer.gainAt(f)
                                SleepTimer.setWindDown(f)
                            }
                            delay(200)
                        }
                        SleepTimer.cancel()
                    }
                    SleepTimer.State.Off -> Unit
                }
            } finally {
                com.opentune.playback.dsp.DspAudioProcessor.masterGain = 1f
                SleepTimer.setWindDown(0f)
            }
        }
    }

    /** Emits playWhenReady, now and on every change. */
    private fun playWhenReadyFlow(player: Player) = callbackFlow {
        trySend(player.playWhenReady)
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { trySend(playWhenReady) }
        }
        player.addListener(l)
        awaitClose { player.removeListener(l) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (AppSettings.playback.value.stopOnTaskRemoved) {
            player.pause()
            player.stop()
            stopSelf()
            return
        }
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        (mediaSession?.player as? ExoPlayer)?.takeIf { it.mediaItemCount > 0 }?.let { p ->
            QueueStore.save(QueueStore.Saved((0 until p.mediaItemCount).map { SongRef.of(p.getMediaItemAt(it).toSong()) }, p.currentMediaItemIndex, p.currentPosition))
        }
        com.opentune.data.AppHooks.onPlayerStopped(this)
        crossfade?.release()
        crossfade = null
        castMirror?.detach(resume = false)
        castMirror = null
        closeEffectSession()
        finishListen()
        // The listen just finished, and anything else not yet saved, before the process goes.
        com.opentune.data.Core.flush()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { unregisterReceiver(volumeReceiver) }
        BitPerfectUsb.setWanted(false)
        scope.cancel()
        soundEffects?.release()
        soundEffects = null
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /**
     * The session's callback, for the app's own controller and for media
     * browsers like Android Auto.
     *
     * Items that arrive from a controller may have lost their URI on the way
     * (Media3 only carries it across a binder in some cases), so it's rebuilt
     * from the media id, which always survives. A browser's items carry the
     * list they were shown in; [CarLibrary.resolve] queues that list.
     */
    private val sessionCallback = object : MediaLibrarySession.Callback {
        /** Every controller (notification, Android Auto, Bluetooth) also gets Like and Shuffle. */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE_COMMAND)
                .add(SHUFFLE_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                ACTION_LIKE -> player.currentMediaItem?.toSong()?.let { song ->
                    LibraryStore.setLiked(song, !LibraryStore.isLiked(song.videoId))
                }
                ACTION_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            refreshButtons()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * "Resume" from the system's media controls or a Bluetooth play button
         * when the app isn't running: the queue saved last time, where it was.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = QueueStore.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            val items = saved.songs.map { it.toSong().toMediaItem() }
            if (items.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, saved.index.coerceIn(items.indices), saved.positionMs.coerceAtLeast(0)),
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map(car::toQueueItem).toMutableList(),
        )

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            car.resolve(mediaItems, startIndex, startPositionMs)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(car.root(), LibraryParams.Builder().setExtras(car.rootExtras()).build()),
        )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            car.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(car.children(parentId, browser).page(page, pageSize), params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't list $parentId for ${browser.packageName}", e)
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = runCatching { car.search(query, browser).also { searchResults[query] = it }.size }
                .onFailure { Log.w(TAG, "Search failed for ${browser.packageName}", it) }
                .getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = searchResults[query] ?: runCatching { car.search(query, browser) }.getOrDefault(emptyList())
            LibraryResult.ofItemList(items.page(page, pageSize), params)
        }
    }

    /** Like (heart) and Shuffle, as the notification and Android Auto show them now. */
    private fun mediaButtons(): List<CommandButton> {
        val player = mediaSession?.player
        val liked = player?.currentMediaItem?.mediaId?.let(LibraryStore::isLiked) == true
        val shuffle = player?.shuffleModeEnabled == true
        return listOf(
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (liked) "Remove from Liked" else "Like")
                .setSessionCommand(LIKE_COMMAND)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (shuffle) "Shuffle off" else "Shuffle on")
                .setSessionCommand(SHUFFLE_COMMAND)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun refreshButtons() {
        mediaSession?.setMediaButtonPreferences(mediaButtons())
        com.opentune.data.AppHooks.onPlayerChanged(this, mediaSession?.player)
    }

    /** The last browser search's results, between onSearch and onGetSearchResult. */
    private val searchResults = object : LinkedHashMap<String, List<MediaItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) = size > 4
    }

    private fun List<MediaItem>.page(page: Int, pageSize: Int): List<MediaItem> =
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) this else drop(page * pageSize).take(pageSize)

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                for (i in 0 until player.mediaItemCount) sessionIds += player.getMediaItemAt(i).mediaId
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                )
            ) {
                refreshButtons()
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    // Asked again as playback starts and ends, in case an earlier ask came to nothing.
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                extendQueueIfNeeded()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyLoudness()
            // After this callback, not inside it: the queue changes under it otherwise.
            scope.launch(Dispatchers.Main) { dropPlayedHandQueued() }
            // Nothing but a blend turns the main player down; outside one, a new song always starts at full level.
            (mediaSession?.player as? ExoPlayer)?.let { p ->
                if (crossfade?.blending != true && p.volume < 1f) {
                    Log.w(TAG, "Player volume was left at ${p.volume}; back to full")
                    p.volume = 1f
                }
            }
            // Anything much longer than a song (a mix, a long live set) picks up where it was left.
            mediaItem?.mediaId?.let { com.opentune.data.library.ResumePoints.resumeAt(it) }?.let { at ->
                mediaSession?.player?.takeIf { it.currentPosition < RESUME_SLACK_MS }?.seekTo(at)
            }
            finishListen()
            startListen(mediaItem?.toSong(), mediaSession?.player?.isPlaying == true)
            // Only time starts meant to sound now, not a skip made while paused.
            startRequestedAt = if (mediaSession?.player?.playWhenReady == true) SystemClock.elapsedRealtime() else 0L
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mediaSession?.player?.let { watchPlayed(it, isPlaying) }
            if (!isPlaying) mediaSession?.player?.let(::saveEpisodePosition)
            if (isPlaying && startRequestedAt > 0) {
                NerdStats.onStartup(SystemClock.elapsedRealtime() - startRequestedAt)
                startRequestedAt = 0
            }
            if (isPlaying) {
                if (listenSince < 0) listenSince = SystemClock.elapsedRealtime()
            } else {
                pauseListen()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) applyLoudness()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) } ?: return
            // The file's own ReplayGain, for loudness normalization.
            val id = mediaSession?.player?.currentMediaItem?.mediaId
            if (id != null) {
                val gain = ReplayGain.trackGainDb(format.metadata)
                if (gain != null) replayGain[id] = gain else replayGain.remove(id)
                applyLoudness()
                // What Signal path and the quality label show as the source: the file and its format.
                val codec = when (format.sampleMimeType) {
                    "audio/flac" -> "FLAC"
                    "audio/alac" -> "ALAC"
                    "audio/mpeg" -> "MP3"
                    "audio/mp4a-latm" -> "AAC"
                    "audio/opus" -> "Opus"
                    "audio/vorbis" -> "Vorbis"
                    "audio/raw" -> "WAV"
                    else -> format.sampleMimeType?.removePrefix("audio/")?.uppercase()
                }
                val bits = when (format.pcmEncoding) {
                    C.ENCODING_PCM_16BIT -> 16
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
                    else -> null
                }
                NerdStats.onPlaybackSource(NerdStats.Source(
                    provider = listOfNotNull("This phone", codec).joinToString(" · "),
                    bits = bits,
                    sampleRate = format.sampleRate.takeIf { it > 0 },
                    kbps = format.bitrate.takeIf { it > 0 }?.div(1000),
                    mediaId = id,
                ))
            }
            // The DAC follows the song's rate by itself: it's set as each song's output opens.
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            openEffectSession(audioSessionId)
            applyLoudness()
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = mediaSession?.player?.currentMediaItem
            Log.e(TAG, "Playback error on ${item?.mediaId}: ${error.errorCodeName}", error)
            recover(error)
        }
    }

    // ---- Listening history ---------------------------------------------------

    private var listenSong: Song? = null
    private var listenedMs = 0L
    private var listenSince = -1L
    private var listenRecord: Long? = null
    private var listenDuration: Long? = null

    private fun startListen(song: Song?, playing: Boolean) {
        listenSong = song
        listenedMs = 0
        listenSince = if (playing) SystemClock.elapsedRealtime() else -1
        listenRecord = null
        listenDuration = mediaSession?.player?.duration?.takeIf { it > 0 }
    }

    private fun pauseListen() {
        if (listenSince >= 0) listenedMs += SystemClock.elapsedRealtime() - listenSince
        listenSince = -1
    }

    private fun listenedSoFar(): Long =
        listenedMs + if (listenSince >= 0) SystemClock.elapsedRealtime() - listenSince else 0

    private fun finishListen() {
        val heard = listenedSoFar()
        listenRecord?.let { History.updateListened(it, heard) }
        listenRecord = null
    }

    /** Keeps a podcast episode's place, or that of anything as long as one, so it resumes there. */
    private fun saveEpisodePosition(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        com.opentune.data.library.ResumePoints.save(id, player.currentPosition, duration)
    }

    /** A track counts as played after 30 seconds, or half its length if shorter. */
    private suspend fun trackListening(player: Player) {
        var ticks = 0
        while (scope.isActive) {
            delay(5_000)
            if (player.isPlaying && ++ticks % 3 == 0) saveEpisodePosition(player)
            val song = listenSong ?: continue
            val heard = listenedSoFar()
            val known = player.duration.takeIf { it > 0 }
            listenDuration = known
            if (listenRecord != null) continue
            val duration = known ?: Long.MAX_VALUE
            val threshold = minOf(30_000L, duration / 2)
            if (heard >= threshold) {
                listenRecord = History.record(song, heard)
            }
        }
    }

    // ---- Loudness ----------------------------------------------------------------

    private var crossfade: Crossfade? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSession = C.AUDIO_SESSION_ID_UNSET

    /**
     * Loudness normalization: one fixed gain per track, from the file's own
     * ReplayGain tag (see [ReplayGain]), applied with the platform's LoudnessEnhancer. Loud masters come down to
     * the reference and quiet ones come up a little (at most +3 dB), with no
     * pumping and nothing touching the peaks in between. Without a figure, or
     * with the setting off, the gain is zero and the effect is off.
     */
    /**
     * Tells the system a music session is open, so the phone's own sound
     * effects (Dolby Atmos, Samsung's SoundAlive and Adapt Sound, the system
     * equalizer and similar) attach to this app the way they do to the stock
     * players. Without this many phones leave OpenTune unprocessed and it
     * sounds flatter and quieter next to other apps.
     */
    private var effectSession = C.AUDIO_SESSION_ID_UNSET

    private fun openEffectSession(session: Int) {
        PhoneEqualizer.session = session
        if (!AppSettings.playback.value.systemEffects || AppSettings.playback.value.pureOutput || BitPerfectUsb.active) return closeEffectSession()
        if (session == C.AUDIO_SESSION_ID_UNSET || session == effectSession) return
        closeEffectSession()
        effectSession = session
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    private fun closeEffectSession() {
        if (effectSession == C.AUDIO_SESSION_ID_UNSET) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, effectSession)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName),
        )
        effectSession = C.AUDIO_SESSION_ID_UNSET
    }

    private var loudnessRetry: Job? = null

    private fun applyLoudness() {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET) return
        openEffectSession(session)
        val id = player.currentMediaItem?.mediaId
        // The file's ReplayGain track gain, read into YouTube's terms: ReplayGain aims at
        // about -18 LUFS and YouTube's reference at about -14, so a song needing -6 dB
        // under ReplayGain is 2 dB louder than YouTube's reference.
        val db = id?.let { replayGain[it] }?.let { -it.toDouble() - REPLAYGAIN_TO_REFERENCE_DB }
        // On the phone's own speaker, levelling only takes the loud masters
        // down, and a small speaker needs every dB; there it waits for headphones.
        val pb = AppSettings.playback.value
        val speakerHold = pb.loudnessNormalization && !pb.normalizeOnSpeaker && onPhoneSpeaker()
        NerdStats.onLoudnessOffOnSpeaker(speakerHold)
        val on = pb.loudnessNormalization && !pb.pureOutput && db != null && !BitPerfectUsb.active
        val enhancer = loudnessEnhancer?.takeIf { loudnessSession == session }
            ?: runCatching { LoudnessEnhancer(session) }
                .onFailure { Log.w(TAG, "LoudnessEnhancer unavailable", it) }
                .getOrNull()
                ?.also {
                    loudnessEnhancer?.release()
                    loudnessEnhancer = it
                    loudnessSession = session
                }
            ?: return
        runCatching {
            if (on) {
                val gainMb = (loudnessGainDb(db!!, pb.volumeLevel, speakerHold) * 100).roundToInt().coerceIn(MIN_LOUDNESS_GAIN_MB, MAX_LOUDNESS_GAIN_MB)
                enhancer.setTargetGain(gainMb)
                enhancer.enabled = true
                NerdStats.onLoudnessGain(gainMb / 100f)
            } else {
                enhancer.setTargetGain(0)
                enhancer.enabled = false
                NerdStats.onLoudnessGain(null)
            }
        }.onFailure { Log.w(TAG, "Couldn't apply loudness gain", it) }
    }

    /** ReplayGain track gains read from the songs' own tags, by song. */
    private val replayGain = ConcurrentHashMap<String, Float>()

    /** When a song was asked to start, for "Stats for nerds"' startup time. */
    private var startRequestedAt = 0L

    // ---- Output device ---------------------------------------------------------

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            applyPreferredDevice()
            // A DAC plugged in mid-song: the song moves onto it exclusively at once.
            if (devicesListed && BitPerfectUsb.wanted && addedDevices.orEmpty().any { it.type in USB_OUTPUTS }) {
                (mediaSession?.player as? ExoPlayer)?.let(::reopenOutput)
            }
            // The callback reports every device already there as it's
            // registered; only one that turns up afterwards is a connection.
            if (!devicesListed) {
                devicesListed = true
                return
            }
            if (addedDevices.orEmpty().any { it.isSink && it.type in LISTENING_DEVICES }) resumeOnConnect()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            applyPreferredDevice()
            if (removedDevices.orEmpty().any { it.type in USB_OUTPUTS }) BitPerfectUsb.devicesChanged()
        }
    }
    private var devicesListed = false

    /**
     * Headphones or a Bluetooth device came on: play the paused queue, if
     * the setting is on and there's something to play. Audio routes to the
     * new device the moment it appears, so this doesn't start on the speaker.
     */
    private fun resumeOnConnect() {
        val player = mediaSession?.player ?: return
        if (!AppSettings.playback.value.resumeOnConnect || player.mediaItemCount == 0 || player.playWhenReady) return
        Log.i(TAG, "Output device connected; resuming")
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /** Bumped when the volume keys move, so the bit-perfect software volume follows. */
    private val volumeTick = MutableStateFlow(0)

    /** Set while paused by "Pause at zero volume", so raising it again resumes. */
    private var pausedAtZero = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            volumeTick.value++
            if (intent?.getIntExtra(EXTRA_VOLUME_STREAM, AudioManager.STREAM_MUSIC) == AudioManager.STREAM_MUSIC) onMusicVolume()
        }
    }

    private fun onMusicVolume() {
        val player = mediaSession?.player ?: return
        val volume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
        if (!AppSettings.playback.value.pauseAtZeroVolume) {
            pausedAtZero = false
            return
        }
        if (volume == 0 && player.playWhenReady) {
            pausedAtZero = true
            player.pause()
        } else if (volume > 0 && pausedAtZero) {
            pausedAtZero = false
            player.play()
        }
    }

    private var exclusiveWas: Boolean? = null

    /**
     * Turns exclusive USB output on or off. With [reopen], a song already
     * playing has its output opened again where it is (a seek in place does
     * it), so the change is heard now and not from the next song.
     */
    private fun applyBitPerfect(reopen: Boolean = false) {
        val pb = AppSettings.playback.value
        BitPerfectUsb.setWanted(pb.bitPerfectUsb)
        applyLoudness()
        val player = mediaSession?.player as? ExoPlayer ?: return
        openEffectSession(player.audioSessionId)
        val changed = exclusiveWas != null && exclusiveWas != pb.bitPerfectUsb
        exclusiveWas = pb.bitPerfectUsb
        if (reopen && (changed || pb.bitPerfectUsb)) reopenOutput(player)
    }

    private fun stretching(): Boolean = isStretched(
        AppSettings.sound.value, AppSettings.playback.value.skipSilence, false,
    )

    /**
     * The player's output set up afresh at the same place, for a change in
     * whether the song is stretched: a seek keeps the output as it is, so the
     * player is stopped and prepared again. Only when float could be in use.
     */
    private fun rebuildOutput() {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val pb = AppSettings.playback.value
        if (!pb.floatOutput && !pb.bitPerfectUsb) return
        if (player.currentMediaItem == null) return
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
        val at = player.currentPosition
        player.stop()
        player.prepare()
        player.seekTo(at)
    }

    /** The song's output closed and opened again at the same place, so it opens on the current route. */
    private fun reopenOutput(player: ExoPlayer) {
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
        if (player.currentMediaItem == null) return
        player.seekTo(player.currentPosition)
    }

    /**
     * Whether sound is going to the phone's own speaker: no headphones,
     * Bluetooth audio, USB, HDMI, dock or line output is connected. Android
     * routes media to any of those as soon as it appears.
     */
    private fun onPhoneSpeaker(): Boolean {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return outputs.none { it.isSink && it.type in EXTERNAL_OUTPUTS }
    }

    /** Route to a USB DAC when one is plugged in and the setting asks for it. */
    private fun applyPreferredDevice() {
        applyBitPerfect()
        val player = mediaSession?.player as? ExoPlayer ?: return
        val usb = if (AppSettings.playback.value.preferUsbDac) {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } else {
            null
        }
        player.setPreferredAudioDevice(usb)
    }

    // ---- Autoplay ----------------------------------------------------------------

    /**
     * Append radio for the last queued track once playback nears the end, so
     * the music doesn't stop. Starting a single song goes through here too:
     * a one-track queue is already "near the end". With repeat on the queue
     * never ends, so there is nothing to extend.
     */
    private fun extendQueueIfNeeded() {
        val player = mediaSession?.player ?: return
        if (!AppSettings.playback.value.autoplay || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val remaining = player.upcomingPlayOrder().size - 1
        if (!Autoplay.shouldExtend(remaining, player.mediaItemCount)) return
        val seed = player.getMediaItemAt(player.mediaItemCount - 1).mediaId
        if (seed in exhaustedSeeds) return
        if (radioJob?.isActive == true && radioSeed == seed) return
        radioJob?.cancel()
        radioSeed = seed
        radioJob = scope.launch {
            // The last few songs lead, so what comes next follows the queue rather than one song.
            val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
            val seeds = queue.takeLast(AUTOPLAY_SEEDS)
            val queued = queue.mapTo(HashSet()) { it.videoId }
            val skip = if (AppSettings.playback.value.noRepeatInSession) queued + sessionIds else queued
            val additions = withContext(Dispatchers.Default) {
                com.opentune.data.local.LocalSuggestions.after(seeds, AUTOPLAY_BATCH, exclude = skip)
                    .ifEmpty { com.opentune.data.local.LocalSuggestions.after(seeds, AUTOPLAY_BATCH, exclude = queued) }
            }
            val count = player.mediaItemCount
            if (!AppSettings.playback.value.autoplay || count == 0 || player.getMediaItemAt(count - 1).mediaId != seed ||
                player.repeatMode != Player.REPEAT_MODE_OFF) return@launch
            if (additions.isEmpty()) {
                exhaustedSeeds += seed
                return@launch
            }
            val ended = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(additions.map { it.copy(queueTier = QueueTier.AUTOPLAY, fromAutoplay = true).toMediaItem() })
            // The song finished before the next ones came; carry on into them.
            if (ended && player.playWhenReady) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /**
     * What to do when the current track fails: it's retried once, then
     * skipped, but only up to [MAX_FAILED_IN_ROW] tracks in a row; past that
     * playback stops on the error, so it's shown.
     */
    private fun recover(error: PlaybackException) {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        // A file deleted or moved since it was queued won't come back by trying again.
        val permanent = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        if (!permanent && retriedMediaId != mediaId) {
            Log.w(TAG, "Retrying $mediaId after ${error.errorCodeName}")
            retriedMediaId = mediaId
            player.prepare()
            return
        }
        failedInRow++
        if (failedInRow >= MAX_FAILED_IN_ROW) {
            Log.w(TAG, "$failedInRow tracks in a row failed (last: $mediaId, ${error.errorCodeName}); stopping")
            failedInRow = 0
            return
        }
        if (player.hasNextMediaItem()) {
            Log.w(TAG, "Skipping $mediaId after ${error.errorCodeName}")
            player.seekToNextMediaItem()
            player.prepare()
        }
    }

    /** Tracks in a row that failed and were skipped; see [recover]. */
    private var failedInRow = 0
    private var playedCheck: Job? = null

    /**
     * A track counts as working once it has really played for a while, not
     * when it first reports ready: a stream refused a few seconds in is
     * ready first and fails after. Until then its retry stays spent and
     * the failures in a row stand.
     */
    private fun watchPlayed(player: Player, playing: Boolean) {
        playedCheck?.cancel()
        if (!playing) return
        val id = player.currentMediaItem?.mediaId ?: return
        playedCheck = scope.launch {
            delay(PLAYED_OK_MS)
            if (player.isPlaying && player.currentMediaItem?.mediaId == id) {
                failedInRow = 0
                retriedMediaId = null
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

        /** Bounds on the loudness gain, in millibels: down 15 dB, up 3 dB at most. */
        const val LOUDNESS_RETRY_MS = 6_000L
        /**
         * The volume broadcast comes from the system, so the receiver is
         * exported; a spoofed one only makes the gain re-read the real volume.
         */
        val VOLUME_RECEIVER_FLAGS = if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        const val QUEUE_SAVE_MS = 5_000L
        /** A swap waits for the song to have played this much, with [UPGRADE_BUFFERED_MS] ahead of it... */
        const val UPGRADE_SETTLE_MS = 300L
        const val UPGRADE_BUFFERED_MS = 2_000L
        /** ...or for this long at most, when it's paused or slow to start. */
        const val UPGRADE_START_WAIT_MS = 6_000L
        /** How many songs ahead the lossless source is looked up. */
        const val PREFETCH_AHEAD = 5
        /** How many of those also get the FLAC search ahead of time; the rest get JioSaavn and are searched as they start. */
        const val PREFETCH_LOSSLESS_AHEAD = 2
        /** A near-ready verified FLAC gets priority before beginning an intermediate handoff. */
        const val SAAVN_HOLD_MS = 3_000L
        /** How often [guardQuality] looks, how far into a song it starts, how long between its tries, and how many a song gets. */
        const val GUARD_EVERY_MS = 1_000L
        const val VERIFIED_RETRY_GAP_MS = 2_000L
        const val GUARD_SETTLE_MS = 8_000L
        const val GUARD_GAP_MS = 12_000L
        const val GUARD_TRIES = 4
        /** After a fallback, how long the song sounds before the upgrade runs again. */
        const val FALLBACK_RETRY_MS = 3_000L
        /** Songs ahead looked up at once; the nearest go first. */
        const val PREFETCH_AT_ONCE = 2
        /** How long the queue has to rest on a song before the ones after it are looked up. */
        const val PREFETCH_SETTLE_MS = 1_500L
        /** Songs a server queue carries on with when it runs out. */
        const val RADIO_FROM_SERVER = 25
        const val RADIO_REQUEST_MS = 12_000L
        /** With "end of song", how long the song fades out before it stops. */
        const val END_FADE_MS = 15_000L
        /** Waits before each ask for radio: at once, then twice more if it fails. */
        val RADIO_RETRY_MS = longArrayOf(0L, 3_000L, 10_000L)
        /** Whole rounds of asking, when a round brings nothing: now, then again while the song plays. */
        val RADIO_AGAIN_MS = longArrayOf(0L, 20_000L, 45_000L, 90_000L)
        /** An episode only jumps to its saved place if it hasn't got going yet. */
        const val RESUME_SLACK_MS = 5_000L
        const val SEGMENT_POLL_MS = 250L
        const val SEGMENT_IDLE_POLL_MS = 1_000L
        const val SEGMENT_END_SLACK_MS = 500L
        /** The stream a VOLUME_CHANGED_ACTION is about (a hidden AudioManager extra). */
        const val EXTRA_VOLUME_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        /**
         * Outputs a person listens on privately, as opposed to the phone's
         * speaker. The BLE types are plain ints that older Android never reports.
         */
        @android.annotation.SuppressLint("InlinedApi")
        val LISTENING_DEVICES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        /** A USB DAC or headset, the outputs exclusive mode takes. */
        val USB_OUTPUTS = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
        /** Outputs that take media away from the phone's speaker. */
        val EXTERNAL_OUTPUTS = LISTENING_DEVICES + setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK,
        )
        const val ACTION_LIKE = "com.opentune.LIKE"
        const val ACTION_SHUFFLE = "com.opentune.SHUFFLE"
        val LIKE_COMMAND = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
        val SHUFFLE_COMMAND = SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY)
        /** Failed tracks in a row before playback stops on the error. */
        const val MAX_FAILED_IN_ROW = 3
        /** Playing this long means the track's stream really works. */
        const val PLAYED_OK_MS = 10_000L
        const val UPGRADE_MIN_REMAINING_MS = 20_000L
        /** A FLAC search that failed only on server errors: its erring add-ons are asked again after each of these waits. */
        val LOSSLESS_RETRY_MS = longArrayOf(3_000L, 6_000L, 12_000L)
        /** With a FLAC already found, the add-ons that erred are asked again after each of these waits. */
        val ERRED_RETRY_MS = longArrayOf(4_000L, 10_000L, 20_000L, 40_000L)
        /** A source's file is pinged with a read this size... */
        const val PING_BYTES = 65_536
        /** ...and counted as not answering after this long. */
        const val PING_LIMIT_MS = 8_000L
        /** An upgrade is fetched this far past the playing position before it's swapped in. */
        const val AUDITION_AHEAD_MS = 2_000L
        /** It starts loading a little ahead, for the time the loading itself takes. */
        const val AUDITION_LEAD_MS = 1_500L
        const val AUDITION_TIMEOUT_MS = 12_000L
        /** Network and input errors (Media3's 2000s): what a lost connection fails with. */
        val IO_ERRORS = 2000..2999
        /** Tries at switching to the verified files, this far apart, before giving up for the song. */
        const val SWAP_ROUNDS = 4
        const val SWAP_ROUND_GAP_MS = 3_000L
        /** The volume dip around a swap: quickly down, gently back. */
        /** Each crossfade between the main player and the bridge. */
        const val BRIDGE_FADE_MS = 160L
        /** Each half of a hand-back without a blend: one player out, then the other in. */
        const val BRIDGE_CUT_MS = 70L
        /** Bound attempts before using the already buffered stream with a short fade. */
        const val BRIDGE_ALIGN_TRIES = 2
        /** A first guess at how long a seek takes to sound, landed ahead by. */
        const val BRIDGE_SEEK_LEAD_MS = 120L
        const val BRIDGE_SETTLE_MS = 120L
        /** How long the bridge carries the song while the main player loads the new stream. */
        const val BRIDGE_CATCH_UP_MS = 8_000L
        const val SWAP_DIP_MS = 120L
        const val SWAP_RISE_MS = 260L
        /** Stuck buffering this long mid-song, a better stream gives way to the regular one. */
        const val STALL_FALLBACK_MS = 6_000L
        const val STALL_MIN_POSITION_MS = 3_000L
        const val FAR_BUFFER_MS = 15 * 60 * 1000
        const val FAR_BUFFER_BYTES = 48 * 1024 * 1024
        const val BACK_BUFFER_MS = 30_000
        const val MIN_LOUDNESS_GAIN_MB = -1500
        const val MAX_LOUDNESS_GAIN_MB = 900

        /** How much of the next track ExoPlayer buffers ahead of time. */
        const val PRELOAD_US = 10_000_000L

        /** Songs autoplay adds at a time, and how many of the queue's last songs it follows. */
        const val AUTOPLAY_BATCH = 20
        const val AUTOPLAY_SEEDS = 3
        /** ReplayGain's reference (about -18 LUFS) is this much below YouTube's (about -14). */
        const val REPLAYGAIN_TO_REFERENCE_DB = 4.0

        /** Tracks ahead of the current one whose stream URLs are resolved early. */
        const val WARM_AHEAD = 2

        /** A little under StreamResolver's 20-minute URL lifetime. */
        const val WARM_TTL_MS = 15 * 60 * 1000L
    }
}

/**
 * The gain, in dB, for a song YouTube measured at [loudnessDb] against its
 * reference: brought to the reference, moved by [level]'s offset, and lifted
 * no more than its cap. With [speakerHold] (the phone speaker, normalization
 * held off there) it's never negative: a song is only lifted, never cut.
 */
internal fun loudnessGainDb(loudnessDb: Double, level: com.opentune.data.settings.VolumeLevel, speakerHold: Boolean): Double {
    val target = (-loudnessDb + level.offsetDb).coerceAtMost(level.maxGainDb.toDouble())
    return if (speakerHold) target.coerceAtLeast(0.0) else target
}


