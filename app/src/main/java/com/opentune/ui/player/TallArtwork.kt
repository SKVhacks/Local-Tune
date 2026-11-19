package com.opentune.ui.player

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * A song folder's tall animated cover, drawn the way Apple Music draws it so
 * it costs as little as it can: the video goes on a SurfaceView, which the
 * phone's display hardware puts on screen by itself, frame after frame, with
 * no work for the graphics chip and nothing redrawn in the app. (A TextureView,
 * which the full cover's fade needs, copies every frame through the graphics
 * chip, and every glass button over it then blurred the moving picture again.)
 *
 * So the fade is painted over the video rather than cut out of it: its lower
 * part goes into [base], the one still colour the backdrop is filled with
 * while it shows. The whole picture is shown, the width of the screen, from
 * the very top. It's silent and moves only while the song plays.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun TallArtwork(url: String, playing: Boolean, base: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var size by remember(url) { mutableStateOf(VideoSize.UNKNOWN) }
    var drawn by remember(url) { mutableStateOf(false) }
    val shown by animateFloatAsState(if (drawn) 1f else 0f, tween(600), label = "tallArtwork")
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context)))
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                // Scaled once by the display hardware to the view, which has the picture's shape.
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { drawn = true }
                    override fun onVideoSizeChanged(videoSize: VideoSize) { size = videoSize }
                })
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    DisposableEffect(player, lifecycle) {
        val watch = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.playWhenReady = playing
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                else -> Unit
            }
        }
        lifecycle.addObserver(watch)
        onDispose {
            lifecycle.removeObserver(watch)
            player.release()
        }
    }
    val aspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 3f / 4f
    Box(modifier.fillMaxWidth().aspectRatio(aspect)) {
        AndroidView(
            factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.matchParentSize(),
        )
        // The lower part fades into the backdrop's colour, and a light scrim at the top keeps the status line readable.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.3f),
                    0.15f to Color.Transparent,
                    0.58f to Color.Transparent,
                    0.8f to base.copy(alpha = 0.75f),
                    1f to base,
                ),
            ),
        )
        // Until the first frame, the backdrop's colour; then it lifts.
        if (shown < 1f) Box(Modifier.matchParentSize().background(base.copy(alpha = 1f - shown)))
    }
}
