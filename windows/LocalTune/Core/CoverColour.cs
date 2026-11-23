using System.Runtime.InteropServices.WindowsRuntime;
using Windows.UI;

namespace LocalTune.Core;

/// <summary>
/// A song's cover colour, for the backgrounds that take it (the bottom bar, the mini player):
/// the cover's average, from a 16 × 16 copy, as hue, saturation and lightness, to be brought to
/// whatever brightness the place needs. Worked out once per song and kept.
/// </summary>
public static class CoverColour
{
    public readonly record struct Hsl(double H, double S, double L);

    private static readonly Dictionary<string, Hsl?> cache = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The cover's average colour; null when the song has no cover. Slow the first time: call off the UI thread.</summary>
    public static Hsl? Of(Track t)
    {
        lock (cache) if (cache.TryGetValue(t.Path, out var hit)) return hit;
        var found = Read(t);
        lock (cache) cache[t.Path] = found;
        return found;
    }

    private static Hsl? Read(Track t)
    {
        try
        {
            var bytes = Artwork.Bytes(t);
            if (bytes == null) return null;
            using var stream = new Windows.Storage.Streams.InMemoryRandomAccessStream();
            stream.WriteAsync(bytes.AsBuffer()).AsTask().Wait();
            stream.Seek(0);
            var decoder = Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(stream).AsTask().Result;
            var pixels = decoder.GetPixelDataAsync(
                Windows.Graphics.Imaging.BitmapPixelFormat.Bgra8, Windows.Graphics.Imaging.BitmapAlphaMode.Ignore,
                new Windows.Graphics.Imaging.BitmapTransform { ScaledWidth = 16, ScaledHeight = 16, InterpolationMode = Windows.Graphics.Imaging.BitmapInterpolationMode.Fant },
                Windows.Graphics.Imaging.ExifOrientationMode.IgnoreExifOrientation, Windows.Graphics.Imaging.ColorManagementMode.DoNotColorManage).AsTask().Result.DetachPixelData();
            double r = 0, g = 0, b = 0;
            var n = pixels.Length / 4;
            for (var i = 0; i < pixels.Length; i += 4) { b += pixels[i]; g += pixels[i + 1]; r += pixels[i + 2]; }
            return ToHsl(r / (n * 255), g / (n * 255), b / (n * 255));
        }
        catch
        {
            return null;
        }
    }

    private static Hsl ToHsl(double r, double g, double b)
    {
        var max = Math.Max(r, Math.Max(g, b));
        var min = Math.Min(r, Math.Min(g, b));
        var l = (max + min) / 2;
        double h = 0, s = 0;
        if (max - min > 1e-6)
        {
            var d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            h = max == r ? (g - b) / d + (g < b ? 6 : 0) : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
            h /= 6;
        }
        return new Hsl(h, s, l);
    }

    /// <summary>The colour with its saturation scaled and capped, and its lightness held between two bounds.</summary>
    public static Color Shade(Hsl c, double saturation, double maxSaturation, double minLight, double maxLight) =>
        FromHsl(c.H, Math.Min(c.S * saturation, maxSaturation), Math.Clamp(c.L, minLight, maxLight));

    public static Color FromHsl(double h, double s, double l)
    {
        double Hue(double p, double q, double t)
        {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            return t < 1.0 / 6 ? p + (q - p) * 6 * t : t < 0.5 ? q : t < 2.0 / 3 ? p + (q - p) * (2.0 / 3 - t) * 6 : p;
        }
        double r, g, b;
        if (s == 0) r = g = b = l;
        else
        {
            var q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            var p = 2 * l - q;
            r = Hue(p, q, h + 1.0 / 3);
            g = Hue(p, q, h);
            b = Hue(p, q, h - 1.0 / 3);
        }
        return Color.FromArgb(255, (byte)Math.Round(r * 255), (byte)Math.Round(g * 255), (byte)Math.Round(b * 255));
    }
}
