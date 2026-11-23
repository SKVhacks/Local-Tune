// Ported from the phone app's app/.../ui/player/FlowBackground.kt (FLOW_AGSL). Keep the two in step.

using System.Runtime.InteropServices.WindowsRuntime;
using LocalTune.Core;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace LocalTune.Views;

/// <summary>
/// Apple Music's now-playing background, exactly as the phone draws it ("Flow"): the cover shrunk
/// to a tiny soft image, drawn as three slowly turning, drifting copies over a gently warped space,
/// two broad moving masks choosing between them so the colours stay apart in big fields, the
/// colour made much richer, very bright covers brought down a little, and the bottom a touch
/// darker so white text reads.
///
/// The phone runs FLOW_AGSL as a shader on every pixel. Here the very same sums are worked out for
/// a small picture (192 pixels across) that Windows stretches smoothly over the window: the shader's
/// output is broad colour fields with nothing finer than that to lose. It moves only while playing,
/// in 30 steps a second as on the phone, and a new cover flows in over 1.4 seconds.
/// </summary>
public sealed class FlowBackground : Grid
{
    private const int Side = 48;            // FIELD_PX
    private const float Speed = 2.2f;       // FLOW_SPEED
    private const int RenderWidth = 192;
    private static readonly TimeSpan Step = TimeSpan.FromMilliseconds(33);
    private static readonly TimeSpan FadeTime = TimeSpan.FromMilliseconds(1400);

    /// <summary>The drift's time in seconds, advancing only while playing (the phone's FlowClock).</summary>
    public static double Seconds { get; private set; }

    private readonly Image image = new() { Stretch = Stretch.Fill };
    private readonly DispatcherQueueTimer timer;
    private WriteableBitmap? bitmap;
    private byte[] pixels = Array.Empty<byte>();
    private int w, h;
    private float[]? current, previous;
    private DateTime fadeStart = DateTime.MinValue;
    private DateTime lastTick;
    private bool running;
    private string? shownPath;
    private int loadId;

    public FlowBackground()
    {
        Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 31, 31, 31));
        Children.Add(image);
        timer = DispatcherQueue.GetForCurrentThread().CreateTimer();
        timer.Interval = Step;
        timer.Tick += (_, _) => Frame();
        SizeChanged += (_, _) => { Allocate(); Render(); };
    }

    /// <summary>On while shown; it moves only while the song plays.</summary>
    public void SetRunning(bool shown, bool playing)
    {
        var on = shown && playing;
        if (on != running)
        {
            running = on;
            lastTick = DateTime.UtcNow;
            if (on) timer.Start(); else timer.Stop();
        }
        if (shown) Render();
    }

    /// <summary>The next song's cover flows in over the last one's.</summary>
    public async void Show(Track? track)
    {
        if (track?.Path == shownPath && current != null) return;
        shownPath = track?.Path;
        var id = ++loadId;
        var field = track == null ? null : await Task.Run(() => CoverField(track));
        if (id != loadId) return;
        field ??= ToneField();
        previous = current ?? field;
        current = field;
        fadeStart = DateTime.UtcNow;
        Render();
        // While paused it still has to draw the fade.
        while (!running && Fade() < 1f && id == loadId)
        {
            await Task.Delay(Step);
            Render();
        }
    }

    private float Fade() => (float)Math.Clamp((DateTime.UtcNow - fadeStart) / FadeTime, 0, 1);

    private void Frame()
    {
        var now = DateTime.UtcNow;
        var d = (now - lastTick).TotalSeconds;
        lastTick = now;
        // A pause longer than a moment isn't counted.
        if (d > 0 && d < 0.25) Seconds += d;
        Render();
    }

    private void Allocate()
    {
        if (ActualWidth < 1 || ActualHeight < 1) return;
        var nw = RenderWidth;
        var nh = Math.Max(1, (int)Math.Round(RenderWidth * ActualHeight / ActualWidth));
        if (nw == w && nh == h && bitmap != null) return;
        w = nw;
        h = nh;
        bitmap = new WriteableBitmap(w, h);
        pixels = new byte[w * h * 4];
        image.Source = bitmap;
    }

    // ---- FLOW_AGSL, worked out per pixel ----

    // What depends only on the column, or only on the row, worked out once a frame.
    private float[] colPx = Array.Empty<float>(), colWarpY = Array.Empty<float>(), colM1Sin = Array.Empty<float>(), colM1Cos = Array.Empty<float>(), colM2Sin = Array.Empty<float>(), colM2Cos = Array.Empty<float>();
    private float[] rowPy = Array.Empty<float>(), rowWarpX = Array.Empty<float>(), rowM1Sin = Array.Empty<float>(), rowM1Cos = Array.Empty<float>(), rowM2Sin = Array.Empty<float>(), rowM2Cos = Array.Empty<float>(), rowShade = Array.Empty<float>();

    private void Render()
    {
        if (bitmap == null) Allocate();
        if (bitmap == null || current == null) return;
        var t = (float)(Seconds * Speed);
        var fade = Fade();
        var c0 = previous ?? current;
        var c1 = current;
        float sx = w, sy = h, big = Math.Max(sx, sy);
        if (colPx.Length != w || rowPy.Length != h)
        {
            colPx = new float[w]; colWarpY = new float[w]; colM1Sin = new float[w]; colM1Cos = new float[w]; colM2Sin = new float[w]; colM2Cos = new float[w];
            rowPy = new float[h]; rowWarpX = new float[h]; rowM1Sin = new float[h]; rowM1Cos = new float[h]; rowM2Sin = new float[h]; rowM2Cos = new float[h]; rowShade = new float[h];
        }
        // float2 p = (fc - 0.5 * size) / max(size.x, size.y);
        // float2 w = p + 0.07 * float2(sin(p.y * 3.0 + t * 0.21), cos(p.x * 2.6 - t * 0.17));
        // m1 = 0.5 + 0.5 * sin(p.x * 2.4 + p.y * 1.8 + t * 0.13)        (split as sin(A + B))
        // m2 = 0.5 + 0.5 * cos(p.y * 2.5 - p.x * 2.0 - t * 0.11 + 1.3)  (split as cos(A + B))
        for (var x = 0; x < w; x++)
        {
            var px = (x + 0.5f - 0.5f * sx) / big;
            colPx[x] = px;
            colWarpY[x] = 0.07f * MathF.Cos(px * 2.6f - t * 0.17f);
            var a1 = px * 2.4f + t * 0.13f;
            colM1Sin[x] = MathF.Sin(a1); colM1Cos[x] = MathF.Cos(a1);
            var a2 = -px * 2.0f - t * 0.11f + 1.3f;
            colM2Sin[x] = MathF.Sin(a2); colM2Cos[x] = MathF.Cos(a2);
        }
        for (var y = 0; y < h; y++)
        {
            var py = (y + 0.5f - 0.5f * sy) / big;
            rowPy[y] = py;
            rowWarpX[y] = 0.07f * MathF.Sin(py * 3.0f + t * 0.21f);
            rowM1Sin[y] = MathF.Sin(py * 1.8f); rowM1Cos[y] = MathF.Cos(py * 1.8f);
            rowM2Sin[y] = MathF.Sin(py * 2.5f); rowM2Cos[y] = MathF.Cos(py * 2.5f);
            // mix(1.0, 0.82, smoothstep(0.45, 1.0, uv.y))
            rowShade[y] = Lerp(1f, 0.82f, SmoothStep(0.45f, 1f, (y + 0.5f) / sy));
        }
        // The three copies' turns: turn(w * 0.7, t * 0.045), turn(w * 0.9 + (0.22, -0.15), -t * 0.06 + 1.7),
        // turn(w * 0.55 + (-0.25, 0.2), t * 0.032 + 3.1).
        float ca = MathF.Cos(t * 0.045f), sa = MathF.Sin(t * 0.045f);
        float cb = MathF.Cos(-t * 0.06f + 1.7f), sb = MathF.Sin(-t * 0.06f + 1.7f);
        float cc = MathF.Cos(t * 0.032f + 3.1f), sc = MathF.Sin(t * 0.032f + 3.1f);

        Span<float> a = stackalloc float[3];
        Span<float> b = stackalloc float[3];
        Span<float> c = stackalloc float[3];
        var i = 0;
        for (var y = 0; y < h; y++)
        {
            var py = rowPy[y];
            for (var x = 0; x < w; x++, i += 4)
            {
                var wx = colPx[x] + rowWarpX[y];
                var wy = py + colWarpY[x];
                float ux = wx * 0.7f, uy = wy * 0.7f;
                Pick(c0, c1, fade, ca * ux - sa * uy + 0.5f, sa * ux + ca * uy + 0.5f, a);
                ux = wx * 0.9f + 0.22f; uy = wy * 0.9f - 0.15f;
                Pick(c0, c1, fade, cb * ux - sb * uy + 0.5f, sb * ux + cb * uy + 0.5f, b);
                ux = wx * 0.55f - 0.25f; uy = wy * 0.55f + 0.2f;
                Pick(c0, c1, fade, cc * ux - sc * uy + 0.5f, sc * ux + cc * uy + 0.5f, c);
                var m1 = 0.5f + 0.5f * (colM1Sin[x] * rowM1Cos[y] + colM1Cos[x] * rowM1Sin[y]);
                var m2 = 0.5f + 0.5f * (rowM2Cos[y] * colM2Cos[x] - rowM2Sin[y] * colM2Sin[x]);
                // col = mix(mix(a, b, smoothstep(0.35, 0.65, m1)), c, 0.5 * smoothstep(0.4, 0.75, m2))
                var s1 = SmoothStep(0.35f, 0.65f, m1);
                var s2 = 0.5f * SmoothStep(0.4f, 0.75f, m2);
                var r = Lerp(Lerp(a[0], b[0], s1), c[0], s2);
                var g = Lerp(Lerp(a[1], b[1], s1), c[1], s2);
                var bl = Lerp(Lerp(a[2], b[2], s1), c[2], s2);
                // Much richer colour: mix(luma, col, 1.7), clamped.
                var l = 0.2126f * r + 0.7152f * g + 0.0722f * bl;
                r = Math.Clamp(Lerp(l, r, 1.7f), 0f, 1f);
                g = Math.Clamp(Lerp(l, g, 1.7f), 0f, 1f);
                bl = Math.Clamp(Lerp(l, bl, 1.7f), 0f, 1f);
                // shade = 0.93 * mix(1.0, 0.8, smoothstep(0.6, 0.95, l)) * mix(1.0, 0.82, smoothstep(0.45, 1.0, uv.y))
                l = 0.2126f * r + 0.7152f * g + 0.0722f * bl;
                var shade = 0.93f * Lerp(1f, 0.8f, SmoothStep(0.6f, 0.95f, l)) * rowShade[y] * 255f;
                pixels[i] = (byte)(bl * shade + 0.5f);
                pixels[i + 1] = (byte)(g * shade + 0.5f);
                pixels[i + 2] = (byte)(r * shade + 0.5f);
                pixels[i + 3] = 255;
            }
        }
        using (var stream = bitmap.PixelBuffer.AsStream()) stream.Write(pixels, 0, pixels.Length);
        bitmap.Invalidate();
    }

    /// <summary>
    /// pick(p): the field mirrored at its edges (1 - abs(1 - mod(p, 2))) and sampled smoothly between
    /// its pixels, as the phone's linear-filtered BitmapShader; the old cover fading into the new.
    /// </summary>
    private static void Pick(float[] f0, float[] f1, float fade, float x, float y, Span<float> into)
    {
        var qx = (1f - MathF.Abs(1f - Mod2(x))) * (Side - 1f);
        var qy = (1f - MathF.Abs(1f - Mod2(y))) * (Side - 1f);
        int x0 = (int)qx, y0 = (int)qy;
        int x1 = Math.Min(x0 + 1, Side - 1), y1 = Math.Min(y0 + 1, Side - 1);
        float tx = qx - x0, ty = qy - y0;
        int i00 = (y0 * Side + x0) * 3, i01 = (y0 * Side + x1) * 3, i10 = (y1 * Side + x0) * 3, i11 = (y1 * Side + x1) * 3;
        for (var k = 0; k < 3; k++)
        {
            var v = Lerp(Lerp(f1[i00 + k], f1[i01 + k], tx), Lerp(f1[i10 + k], f1[i11 + k], tx), ty);
            if (fade < 1f)
                v = Lerp(Lerp(Lerp(f0[i00 + k], f0[i01 + k], tx), Lerp(f0[i10 + k], f0[i11 + k], tx), ty), v, fade);
            into[k] = v;
        }
    }

    private static float Mod2(float v) => v - 2f * MathF.Floor(v * 0.5f);
    private static float Lerp(float a, float b, float t) => a + (b - a) * t;

    private static float SmoothStep(float e0, float e1, float x)
    {
        var t = Math.Clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // ---- The cover as a soft colour field ----

    /// <summary>The cover shrunk to 48 px square and softened, so it's colour fields rather than a picture (coverField).</summary>
    private static float[]? CoverField(Track track)
    {
        var bytes = Artwork.Bytes(track);
        if (bytes == null) return null;
        try
        {
            using var stream = new InMemoryRandomAccessStream();
            stream.WriteAsync(bytes.AsBuffer()).AsTask().GetAwaiter().GetResult();
            stream.Seek(0);
            var decoder = BitmapDecoder.CreateAsync(stream).AsTask().GetAwaiter().GetResult();
            var transform = new BitmapTransform { ScaledWidth = Side, ScaledHeight = Side, InterpolationMode = BitmapInterpolationMode.Fant };
            var data = decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, transform,
                ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb).AsTask().GetAwaiter().GetResult();
            var px = data.DetachPixelData();
            var field = new float[Side * Side * 3];
            for (var i = 0; i < Side * Side; i++)
            {
                field[i * 3] = px[i * 4 + 2] / 255f;
                field[i * 3 + 1] = px[i * 4 + 1] / 255f;
                field[i * 3 + 2] = px[i * 4] / 255f;
            }
            Soften(field, radius: 2, passes: 3);
            return field;
        }
        catch { return null; }
    }

    /// <summary>No cover: deep greys with a touch of the accent in the corners, blended (toneField).</summary>
    private static float[] ToneField()
    {
        float[][] corners = { new[] { 0.98f, 0.18f, 0.28f }, new[] { 0.25f, 0.22f, 0.3f }, new[] { 0.35f, 0.12f, 0.2f }, new[] { 0.12f, 0.12f, 0.14f } };
        var field = new float[Side * Side * 3];
        for (var y = 0; y < Side; y++)
            for (var x = 0; x < Side; x++)
            {
                float u = x / (Side - 1f), v = y / (Side - 1f);
                for (var c = 0; c < 3; c++)
                {
                    var top = corners[0][c] * (1 - u) + corners[1][c] * u;
                    var bottom = corners[2][c] * (1 - u) + corners[3][c] * u;
                    field[(y * Side + x) * 3 + c] = top * (1 - v) + bottom * v;
                }
            }
        return field;
    }

    /// <summary>A box blur, several times over (softenSquare).</summary>
    private static void Soften(float[] f, int radius, int passes)
    {
        var tmp = new float[f.Length];
        for (var pass = 0; pass < passes; pass++)
        {
            for (var horizontal = 0; horizontal < 2; horizontal++)
            {
                for (var y = 0; y < Side; y++)
                    for (var x = 0; x < Side; x++)
                        for (var c = 0; c < 3; c++)
                        {
                            float sum = 0;
                            for (var k = -radius; k <= radius; k++)
                            {
                                int xx = horizontal == 0 ? Math.Clamp(x + k, 0, Side - 1) : x;
                                int yy = horizontal == 1 ? Math.Clamp(y + k, 0, Side - 1) : y;
                                sum += f[(yy * Side + xx) * 3 + c];
                            }
                            tmp[(y * Side + x) * 3 + c] = sum / (2 * radius + 1);
                        }
                Array.Copy(tmp, f, f.Length);
            }
        }
    }
}
