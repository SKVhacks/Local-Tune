using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage.Streams;

namespace LocalTune.Core;

/// <summary>Song covers, from the file's tags or an image in its folder, decoded at the size shown.</summary>
public static class Artwork
{
    private static readonly string[] FolderNames = { "cover", "folder", "front", "albumart", "album" };
    private static readonly string[] ImageExt = { ".jpg", ".jpeg", ".png", ".webp", ".bmp" };

    private const int Max = 600;
    private static readonly Dictionary<string, LinkedListNode<(string Key, BitmapImage? Image)>> map = new();
    private static readonly LinkedList<(string Key, BitmapImage? Image)> order = new();

    /// <summary>The cover of <paramref name="track"/> decoded at <paramref name="size"/> pixels across; null when it has none. Call on the UI thread.</summary>
    public static async Task<BitmapImage?> LoadAsync(Track track, int size)
    {
        var key = track.Path + "@" + size;
        if (map.TryGetValue(key, out var hit))
        {
            order.Remove(hit);
            order.AddFirst(hit);
            return hit.Value.Image;
        }
        var bytes = await Task.Run(() => Bytes(track));
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
        if (!map.ContainsKey(key))
        {
            map[key] = order.AddFirst((key, image));
            while (order.Count > Max)
            {
                map.Remove(order.Last!.Value.Key);
                order.RemoveLast();
            }
        }
        return image;
    }

    /// <summary>The cover's own bytes, at full size.</summary>
    public static byte[]? Bytes(Track track)
    {
        if (track.HasEmbeddedArt)
        {
            try
            {
                using var file = TagLib.File.Create(track.Path);
                var pictures = file.Tag.Pictures;
                // The front cover if it's marked, else the first picture.
                var pic = pictures.FirstOrDefault(p => p.Type == TagLib.PictureType.FrontCover) ?? pictures.FirstOrDefault();
                if (pic != null) return pic.Data.Data;
            }
            catch { }
        }
        try
        {
            var path = FolderImage(System.IO.Path.GetDirectoryName(track.Path));
            return path != null ? File.ReadAllBytes(path) : null;
        }
        catch { return null; }
    }

    private static string? FolderImage(string? dir)
    {
        if (dir == null || !Directory.Exists(dir)) return null;
        var images = Directory.EnumerateFiles(dir).Where(f => ImageExt.Contains(System.IO.Path.GetExtension(f).ToLowerInvariant())).ToList();
        string Name(string f) => System.IO.Path.GetFileNameWithoutExtension(f).ToLowerInvariant();
        foreach (var n in FolderNames)
        {
            var exact = images.FirstOrDefault(f => Name(f) == n);
            if (exact != null) return exact;
        }
        return images.FirstOrDefault(f => FolderNames.Any(n => Name(f).StartsWith(n)));
    }

    /// <summary>The cover as a stream, for Windows' media controls.</summary>
    public static async Task<RandomAccessStreamReference?> ThumbnailAsync(Track track)
    {
        var bytes = await Task.Run(() => Bytes(track));
        if (bytes == null) return null;
        var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(bytes.AsBuffer());
        stream.Seek(0);
        return RandomAccessStreamReference.CreateFromStream(stream);
    }
}
