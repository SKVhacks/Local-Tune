// Ported from the phone app's core/.../data/local/ArtistImages.kt. Keep the two in step.

using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json.Nodes;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage.Streams;

namespace LocalTune.Core;

/// <summary>
/// An artist's own picture, from inside their songs' files: tags can carry more than the cover,
/// and some taggers put the artist's photo in as "lead artist", "artist" or "band". The first of
/// an artist's songs that has one gives it.
///
/// When none does, and it's allowed (Settings › Artist pictures from the internet), Deezer's
/// public artist search gives one: free, no key, large photos, good for Indian artists too. Only
/// the artist's name is sent.
///
/// Found pictures are kept as files, and so is not finding one, so each artist is looked up once.
/// Not finding one while offline isn't kept, so it's tried again once online.
/// </summary>
public static class ArtistImages
{
    /// <summary>Picture kinds that show the artist, best first: lead artist, artist, band, then conductor, composer, lyricist.</summary>
    private static readonly int[] Wanted = { 7, 8, 10, 9, 11, 12 };
    private const int MaxSongs = 8;

    private static readonly string Dir = Path.Combine(AppDir.Root, "artist-pictures");
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };
    /// <summary>A couple at a time: a Home full of artists doesn't fire dozens of requests at once.</summary>
    private static readonly SemaphoreSlim Gate = new(2);
    private static readonly Dictionary<string, BitmapImage?> Memory = new();

    static ArtistImages()
    {
        Directory.CreateDirectory(Dir);
        Http.DefaultRequestHeaders.UserAgent.ParseAdd("LocalTune/1.0");
    }

    /// <summary>The artist's picture, decoded at <paramref name="size"/>; null when there's none. Call on the UI thread.</summary>
    public static async Task<BitmapImage?> LoadAsync(Artist artist, int size)
    {
        var key = Key(artist);
        if (Memory.TryGetValue(key + "@" + size, out var hit)) return hit;
        var bytes = await Task.Run(() => Find(artist, key));
        BitmapImage? image = null;
        if (bytes != null)
        {
            try
            {
                image = new BitmapImage { DecodePixelWidth = size, DecodePixelType = DecodePixelType.Logical };
                using var stream = new InMemoryRandomAccessStream();
                await stream.WriteAsync(bytes.AsBuffer());
                stream.Seek(0);
                await image.SetSourceAsync(stream);
            }
            catch { image = null; }
        }
        Memory[key + "@" + size] = image;
        return image;
    }

    /// <summary>Forgets what was looked up (the setting changed, or the data was cleared).</summary>
    public static void Forget() => Memory.Clear();

    private static async Task<byte[]?> Find(Artist artist, string key)
    {
        var image = Path.Combine(Dir, key + ".img");
        var none = Path.Combine(Dir, key + ".none");
        if (File.Exists(image)) return await File.ReadAllBytesAsync(image);
        if (File.Exists(none)) return null;
        await Gate.WaitAsync();
        try
        {
            if (File.Exists(image)) return await File.ReadAllBytesAsync(image);
            var found = artist.Tracks.Take(MaxSongs).Select(Embedded).FirstOrDefault(b => b != null);
            var offline = false;
            if (found == null && Settings.Value.OnlineArtistPictures)
            {
                try { found = await Deezer(artist.Name); }
                catch (HttpRequestException) { offline = true; }
                catch (TaskCanceledException) { offline = true; }
            }
            if (found == null)
            {
                // Offline: no answer yet, so it's asked again later.
                if (!offline) await File.WriteAllBytesAsync(none, Array.Empty<byte>());
                return null;
            }
            await File.WriteAllBytesAsync(image, found);
            return found;
        }
        finally { Gate.Release(); }
    }

    /// <summary>The best artist picture in one song's tags (ID3 and FLAC mark each picture with what it shows).</summary>
    private static byte[]? Embedded(Track t)
    {
        try
        {
            using var file = TagLib.File.Create(t.Path);
            return file.Tag.Pictures
                .Where(p => Array.IndexOf(Wanted, (int)p.Type) >= 0 && p.Data.Count > 512)
                .OrderBy(p => Array.IndexOf(Wanted, (int)p.Type))
                .Select(p => p.Data.Data)
                .FirstOrDefault();
        }
        catch { return null; }
    }

    /// <summary>
    /// The artist's photo from Deezer's public search, when an artist of that name (letters and
    /// digits compared, case aside) has a real one, not the blank stand-in.
    /// </summary>
    private static async Task<byte[]?> Deezer(string name)
    {
        var body = await Http.GetStringAsync("https://api.deezer.com/search/artist?limit=5&q=" + Uri.EscapeDataString(name));
        if (JsonNode.Parse(body)?["data"] is not JsonArray results) return null;
        var wanted = Plain(name);
        var match = results.OfType<JsonObject>().FirstOrDefault(o => Plain(o["name"]?.GetValue<string>() ?? "") == wanted);
        if (match == null) return null;
        var picture = new[] { "picture_xl", "picture_big" }.Select(k => match[k]?.GetValue<string>()).FirstOrDefault(u => u != null && u.StartsWith("http"));
        // Artists without a photo get one with no image id in its path.
        if (picture == null || picture.Contains("/artist//")) return null;
        var bytes = await Http.GetByteArrayAsync(picture);
        return bytes.Length > 2048 ? bytes : null;
    }

    private static string Plain(string s) => new(s.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());

    // Changes when the artist's songs do, so a newly tagged song gets looked at, and with the
    // online lookup's switch, so turning it on looks again.
    private static string Key(Artist a) =>
        Convert.ToHexString(System.Security.Cryptography.SHA1.HashData(System.Text.Encoding.UTF8.GetBytes($"{a.Key}#{a.Tracks.Count}#{Settings.Value.OnlineArtistPictures}")))[..16];
}
