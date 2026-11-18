package com.opentune.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Apple Music's now-playing background: the cover itself, shrunk to a tiny
 * soft image, then drawn as three slowly turning, drifting copies that flow
 * into each other, with the colours made richer and the whole darkened
 * toward the bottom so white text reads.
 *
 * It's one small shader on the graphics chip sampling a 48 px image, with no
 * blur pass, so it costs little per frame; and it moves only while playing,
 * at 30 steps a second ([gentle]: 15). Before Android 13, which can't run
 * shaders, it falls back to Cover blur.
 */
@Composable
internal fun FlowBackground(artworkUrl: String?, moving: Boolean, gentle: Boolean, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        CoverBlur(artworkUrl, moving, modifier)
        return
    }
    // A graphics driver that can't build the shader gets Cover blur, rather than a crash.
    val works = remember { runCatching { RuntimeShader(FLOW_AGSL) }.isSuccess }
    if (works) FlowShader(artworkUrl, moving, gentle, modifier) else CoverBlur(artworkUrl, moving, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FlowShader(artworkUrl: String?, moving: Boolean, gentle: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val tones = listOf(scheme.primary, scheme.tertiary, scheme.primaryContainer, lerp(scheme.secondary, Color.Black, 0.4f)).map { it.toArgb() }
    var current by remember { mutableStateOf<Bitmap?>(null) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val fade = remember { Animatable(1f) }
    // The next song's cover flows in over the last one's.
    LaunchedEffect(artworkUrl) {
        val next = withContext(Dispatchers.Default) { coverField(context, artworkUrl) ?: toneField(tones) }
        previous = current ?: next
        current = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(1_400))
    }
    // Seconds of drift, advancing only while playing, in steps.
    val time by produceState(0f, moving, gentle) {
        if (!moving) return@produceState
        val step = if (gentle) 66L else 33L
        val start = withFrameNanos { it } - (value * 1e9f).toLong()
        while (true) {
            delay(step)
            value = (withFrameNanos { it } - start) / 1e9f
        }
    }
    val nowShader = remember(current) { current?.let(::fieldShader) }
    val beforeShader = remember(previous) { previous?.let(::fieldShader) }
    Box(
        modifier.drawWithCache {
            val shader = RuntimeShader(FLOW_AGSL)
            val brush = ShaderBrush(shader)
            onDrawBehind {
                // Until the cover's read, the plain backdrop under this shows.
                if (nowShader == null) return@onDrawBehind
                shader.setInputShader("cover0", beforeShader ?: nowShader)
                shader.setInputShader("cover1", nowShader)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("side", FIELD_PX.toFloat())
                shader.setFloatUniform("t", time)
                shader.setFloatUniform("fade", fade.value)
                drawRect(brush)
            }
        },
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun fieldShader(b: Bitmap) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
    filterMode = BitmapShader.FILTER_MODE_LINEAR
}

/** The cover shrunk to [FIELD_PX] square and softened, so it's colour fields rather than a picture. */
private suspend fun coverField(context: android.content.Context, url: String?): Bitmap? {
    url ?: return null
    val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    return runCatching {
        val src = result.image.toBitmap().let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
        soften(Bitmap.createScaledBitmap(src, FIELD_PX, FIELD_PX, true))
    }.getOrNull()
}

/** No cover: the song's own tones in the four corners, blended. */
private fun toneField(tones: List<Int>): Bitmap {
    val b = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    b.setPixels(tones.take(4).toIntArray(), 0, 2, 0, 0, 2, 2)
    return soften(Bitmap.createScaledBitmap(b, FIELD_PX, FIELD_PX, true))
}

private fun soften(scaled: Bitmap): Bitmap {
    val out = scaled.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(FIELD_PX * FIELD_PX)
    out.getPixels(px, 0, FIELD_PX, 0, 0, FIELD_PX, FIELD_PX)
    softenSquare(px, FIELD_PX, radius = 4, passes = 3)
    out.setPixels(px, 0, FIELD_PX, 0, 0, FIELD_PX, FIELD_PX)
    return out
}

private const val FIELD_PX = 48

/**
 * Three copies of the soft cover, each turning and drifting at its own slow
 * pace over a gently warped space, blended by two broad moving masks; then
 * richer colour, bright covers brought down, the bottom darkened for the
 * controls, and a little dither so the gradients don't band.
 */
private const val FLOW_AGSL = """
uniform shader cover0;
uniform shader cover1;
uniform float2 size;
uniform float side;
uniform float t;
uniform float fade;

float2 turn(float2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(c * p.x - s * p.y, s * p.x + c * p.y);
}

half3 pick(float2 p) {
    float2 m = 1.0 - abs(1.0 - mod(p, 2.0));
    float2 q = m * (side - 1.0) + 0.5;
    return mix(cover0.eval(q).rgb, cover1.eval(q).rgb, half(fade));
}

half4 main(float2 fc) {
    float2 uv = fc / size;
    float2 p = (fc - 0.5 * size) / max(size.x, size.y);
    float2 w = p + 0.09 * float2(sin(p.y * 4.0 + t * 0.21), cos(p.x * 3.5 - t * 0.17));
    half3 a = pick(turn(w, t * 0.045) * 0.95 + 0.5);
    half3 b = pick(turn(w * 1.35 + float2(0.18, -0.12), -t * 0.06 + 1.7) + 0.5);
    half3 c = pick(turn(w * 0.75 + float2(-0.2, 0.15), t * 0.032 + 3.1) + 0.5);
    float m1 = 0.5 + 0.5 * sin(p.x * 3.0 + p.y * 2.2 + t * 0.13);
    float m2 = 0.5 + 0.5 * cos(p.y * 3.1 - p.x * 2.4 - t * 0.11 + 1.3);
    half3 col = mix(mix(a, b, half(smoothstep(0.2, 0.8, m1))), c, half(0.45 * smoothstep(0.3, 0.9, m2)));
    half l = dot(col, half3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(half3(l), col, half(1.35)), half3(0.0), half3(1.0));
    l = dot(col, half3(0.2126, 0.7152, 0.0722));
    float shade = 0.85 * mix(1.0, 0.7, smoothstep(0.45, 0.9, float(l))) * mix(1.0, 0.6, smoothstep(0.3, 1.0, uv.y));
    col *= half(shade);
    float n = fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453);
    col += half((n - 0.5) / 255.0);
    return half4(col, 1.0);
}
"""
