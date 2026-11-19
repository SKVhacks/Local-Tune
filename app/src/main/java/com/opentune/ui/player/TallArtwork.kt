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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
internal fun TallArtwork(
    url: String,
    playing: Boolean,
    fallback: Color,
    modifier: Modifier = Modifier,
    /**
     * Off while the lyrics or the queue are open, or the cover is flying between them.
     * The video stays loaded then, paused on its frame and moved out of sight, so coming
     * back it's on screen at once and plays on, instead of loading again for half a second.
     */
    visible: Boolean = true,
    /** Lets the player take the frame on screen for the shrink into the lyrics header and back. */
    handle: TallHandle? = null,
) {
    val context = LocalContext.current
    val tones = rememberTallTones(url, fallback)
    val base = tones.first
    val deep = tones.second
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visibleNow by androidx.compose.runtime.rememberUpdatedState(visible)
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
    LaunchedEffect(player, playing, visible) { player.playWhenReady = playing && visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    DisposableEffect(player, lifecycle) {
        val watch = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.playWhenReady = playing && visibleNow
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
    handle?.aspect = aspect
    androidx.compose.foundation.layout.Column(modifier.graphicsLayer { alpha = if (visible) 1f else 0f }) {
    Box(Modifier.fillMaxWidth().aspectRatio(aspect)) {
        AndroidView(
            factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it); handle?.view = it } },
            // Hidden by moving it far off screen rather than removing it: its surface, and
            // the frame on it, live on, where taking the view away would throw them out.
            update = { v -> v.translationX = if (visible) 0f else -OFF_SCREEN_PX },
            modifier = Modifier.matchParentSize(),
        )
        // The lower part fades into the backdrop's colour, and a light scrim at the top keeps the status line readable.
        Box(Modifier.matchParentSize().background(tallFade(base)))
        // Until the first frame, the backdrop's colour; then it lifts.
        if (shown < 1f) Box(Modifier.matchParentSize().background(base.copy(alpha = 1f - shown)))
    }
    // Below the picture, its own colours: the edge it fades into, deepening to its main colour.
    Box(Modifier.fillMaxWidth().weight(1f).background(Brush.verticalGradient(0f to base, 1f to deep)))
    }
}

/**
 * The two still colours under the tall cover, from its first frame: the colour
 * along its bottom edge (its fade ends in it, so the seam can't show) and the
 * picture's main colour, deeper, for the bottom of the screen. Both are kept
 * dark enough for white text. Worked out once per video and remembered.
 */
@Composable
internal fun rememberTallTones(url: String?, fallback: Color): Pair<Color, Color> {
    val context = LocalContext.current
    // A new video's colours are worked out afresh (the last one's stay until they're in),
    // and every change of colour, song to song or from the fallback, is eased in.
    val tones by androidx.compose.runtime.produceState<Pair<Int, Int>?>(url?.let { TONES[it] }, url) {
        if (url == null) return@produceState
        value = TONES[url] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tonesOf(context, url) }?.also { TONES[url] = it }
    }
    val base by androidx.compose.animation.animateColorAsState(tones?.let { Color(it.first) } ?: fallback, androidx.compose.animation.core.tween(600), label = "tallBase")
    val deep by androidx.compose.animation.animateColorAsState(tones?.let { Color(it.second) } ?: fallback, androidx.compose.animation.core.tween(600), label = "tallDeep")
    return base to deep
}

private val TONES = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

private fun tonesOf(context: android.content.Context, url: String): Pair<Int, Int>? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(context, android.net.Uri.parse(url))
        val frame = (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 48, 64)
            else r.getFrameAtTime(0)?.let { android.graphics.Bitmap.createScaledBitmap(it, 48, 64, true) }) ?: return null
        val w = frame.width
        val h = frame.height
        val px = IntArray(w * h).also { frame.getPixels(it, 0, w, 0, 0, w, h) }
        // The bottom edge: the last eighth of the rows, averaged.
        val edge = average(px.copyOfRange(w * (h - maxOf(1, h / 8)), w * h))
        // The main colour: the busiest of twelve hue bands among the coloured pixels, averaged.
        val bands = Array(12) { mutableListOf<Int>() }
        val hsv = FloatArray(3)
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] > 0.2f && hsv[2] > 0.15f) bands[(hsv[0] / 30f).toInt().coerceIn(0, 11)] += c
        }
        val busiest = bands.maxByOrNull { it.size }?.takeIf { it.size > px.size / 20 }
        val main = busiest?.let { average(it.toIntArray()) } ?: edge
        darkened(edge, 0.3f) to darkened(blend(main, edge, 0.35f), 0.17f)
    } finally {
        r.release()
    }
}.getOrNull()

private fun average(px: IntArray): Int {
    var r = 0L; var g = 0L; var b = 0L
    for (c in px) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
    val n = px.size.coerceAtLeast(1)
    return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

private fun blend(a: Int, b: Int, t: Float): Int = android.graphics.Color.rgb(
    (android.graphics.Color.red(a) * (1 - t) + android.graphics.Color.red(b) * t).toInt(),
    (android.graphics.Color.green(a) * (1 - t) + android.graphics.Color.green(b) * t).toInt(),
    (android.graphics.Color.blue(a) * (1 - t) + android.graphics.Color.blue(b) * t).toInt(),
)

/** The colour brought down, keeping its hue, until its brightness is at most [most] (0..1). */
private fun darkened(c: Int, most: Float): Int {
    val r = android.graphics.Color.red(c) / 255f
    val g = android.graphics.Color.green(c) / 255f
    val b = android.graphics.Color.blue(c) / 255f
    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val k = if (l > most) most / l else 1f
    return android.graphics.Color.rgb((r * k * 255).toInt(), (g * k * 255).toInt(), (b * k * 255).toInt())
}

/** Far enough off any screen to be out of sight. */
private const val OFF_SCREEN_PX = 100_000f

/** The tall cover's own fade: a light scrim at the top, its lower part going into [base]. */
internal fun tallFade(base: Color) = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.3f),
    0.15f to Color.Transparent,
    0.58f to Color.Transparent,
    0.8f to base.copy(alpha = 0.75f),
    1f to base,
)

/**
 * The player's hold on the tall cover's video: its shape, and the frame on its surface,
 * taken when the player shrinks it into the lyrics header (or grows it back), so the
 * flying cover is the very picture on screen rather than the still cover art.
 */
internal class TallHandle {
    var view: SurfaceView? = null
    var aspect by androidx.compose.runtime.mutableFloatStateOf(3f / 4f)
    var frame by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set

    /** For a shrink: the last frame taken is old now, so none flies until the new one is in. */
    fun retake() { frame = null; capture() }

    /** Copies the frame on the surface. Works while it's moved off screen too: the surface keeps it. */
    fun capture() {
        val v = view ?: return
        if (v.width <= 0 || v.height <= 0 || !v.holder.surface.isValid) return
        val bitmap = android.graphics.Bitmap.createBitmap(v.width / 2, v.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(v, bitmap, { result ->
                if (result == android.view.PixelCopy.SUCCESS) frame = bitmap.asImageBitmap()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }
}

/**
 * The tall cover flying between the full player, where it's the width of the screen at
 * its own shape, and the lyrics header's small square: the frame that was on screen,
 * cropped to the box as it changes shape, its corners rounding as it shrinks, turning
 * into the still cover art over the last part of the way, as Apple Music does it.
 */
@Composable
internal fun TallFlight(
    frame: androidx.compose.ui.graphics.ImageBitmap,
    cover: String?,
    from: androidx.compose.ui.geometry.Rect,
    to: androidx.compose.ui.geometry.Rect,
    p: () -> Float,
    toSmall: Boolean,
    origin: androidx.compose.ui.geometry.Offset,
    /** The colour the player's video fades into, so the flying one fades the same way. */
    base: Color,
    modifier: Modifier = Modifier,
) {
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    fun smallness() = p().coerceIn(0f, 1f).let { if (toSmall) it else 1f - it }
    Box(
        modifier
            .layout { measurable, constraints ->
                val t = p().coerceIn(0f, 1f)
                val r = androidx.compose.ui.geometry.lerp(from, to, t)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((r.left - origin.x).toInt(), (r.top - origin.y).toInt())
                }
            }
            .graphicsLayer {
                shape = androidx.compose.foundation.shape.RoundedCornerShape(corner * smallness())
                clip = true
            },
    ) {
        androidx.compose.foundation.Image(frame, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        // The same fade as on the player, there for nearly the whole flight (whole from the
        // first quarter of the way out), so the picture and the colours below it are one
        // surface as it grows, not a hard-edged picture that gets its fade on landing.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((1f - smallness()) / 0.25f).coerceIn(0f, 1f) }.background(tallFade(base)))
        coil3.compose.AsyncImage(
            cover, null,
            Modifier.fillMaxSize().graphicsLayer {
                val s = smallness()
                alpha = ((s - 0.55f) / 0.45f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}
