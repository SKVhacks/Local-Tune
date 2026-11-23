using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Microsoft.UI.Dispatching;

namespace LocalTune.Core;

/// <summary>How one artist tag becomes several names, as on the phone (LocalMusic.splitArtists).</summary>
public static partial class ArtistNames
{
    [GeneratedRegex(@"\s*(?:,|;|/| & | x | feat\.? | ft\.? | featuring )\s*", RegexOptions.IgnoreCase)]
    private static partial Regex Split();

    /// <summary>The same artist however the tag capitalises or spaces their name.</summary>
    public static string Key(string name) => name.Trim().ToLowerInvariant();

    /// <summary>"A, B &amp; C feat. D" as its four names; one name when the tag holds one.</summary>
    public static List<string> SplitNames(string artist)
    {
        var names = Split().Split(artist).Select(s => s.Trim()).Where(s => s.Length > 0).DistinctBy(Key).ToList();
        return names.Count > 0 ? names : new List<string> { artist };
    }
}

/// <summary>One song file on this PC.</summary>
public sealed class Track
{
    public string Path { get; set; } = "";
    public string Title { get; set; } = "";
    public string Artist { get; set; } = "";
    public string Album { get; set; } = "";
    public string AlbumArtist { get; set; } = "";
    public long DurationMs { get; set; }
    public int TrackNumber { get; set; }
    public int DiscNumber { get; set; }
    public int Year { get; set; }
    public bool HasEmbeddedArt { get; set; }
    /// <summary>The file's own sound: bits per sample (0 for lossy formats), samples a second, and the format.</summary>
    public int BitsPerSample { get; set; }
    public int SampleRate { get; set; }
    public string Codec { get; set; } = "";
    public string Genre { get; set; } = "";
    /// <summary>For the cache: a file whose size and date are unchanged needn't be read again.</summary>
    public long Modified { get; set; }
    public long Size { get; set; }

    [JsonIgnore] public string AlbumKey => ArtistNames.Key(AlbumArtist) + "|" + Album.Trim().ToLowerInvariant();
    [JsonIgnore] public List<string> Artists => ArtistNames.SplitNames(Artist);
    [JsonIgnore] public string DurationText => Format.Time(DurationMs);
    [JsonIgnore] public string NumberText => TrackNumber > 0 ? TrackNumber.ToString() : "";

    private static readonly HashSet<string> LosslessCodecs = new(StringComparer.OrdinalIgnoreCase) { "FLAC", "ALAC", "WAV", "AIFF", "APE", "WavPack", "TTA", "DSD" };

    /// <summary>Apple's quality tiers: "Hi-Res Lossless" above 48 kHz at 24 bits, "Lossless" otherwise; null for lossy files.</summary>
    [JsonIgnore]
    public string? QualityTier
    {
        get
        {
            if (Codec is "E-AC-3" or "AC-3" or "TrueHD") return "Dolby Atmos";
            if (!LosslessCodecs.Contains(Codec)) return null;
            return BitsPerSample >= 24 && SampleRate > 48_000 ? "Hi-Res Lossless" : "Lossless";
        }
    }

    /// <summary>"24-bit/96 kHz FLAC", as Apple's badge says on hover.</summary>
    [JsonIgnore]
    public string QualityDetail
    {
        get
        {
            var khz = SampleRate > 0 ? (SampleRate % 1000 == 0 ? $"{SampleRate / 1000}" : $"{SampleRate / 1000.0:0.#}") + " kHz" : "";
            var bits = BitsPerSample > 0 ? $"{BitsPerSample}-bit" : "";
            var parts = string.Join("/", new[] { bits, khz }.Where(x => x.Length > 0));
            return parts.Length > 0 ? $"{parts} {Codec}".Trim() : Codec;
        }
    }
}

public sealed class Album
{
    public string Key { get; init; } = "";
    public string Title { get; init; } = "";
    public string Artist { get; init; } = "";
    public int Year { get; init; }
    public List<Track> Tracks { get; init; } = new();

    /// <summary>The track whose cover stands for the album.</summary>
    public Track CoverTrack => Tracks.FirstOrDefault(t => t.HasEmbeddedArt) ?? Tracks[0];
}

/// <summary>A folder holding songs, for Home's Folders shelf.</summary>
public sealed class MusicFolder
{
    public string Path { get; init; } = "";
    public string Name => System.IO.Path.GetFileName(Path.TrimEnd('\\', '/'));
    public List<Track> Tracks { get; init; } = new();
}

public sealed class Artist
{
    public string Key { get; init; } = "";
    public string Name { get; init; } = "";
    public List<Track> Tracks { get; init; } = new();
    public List<Album> Albums { get; init; } = new();

    public Track CoverTrack => Albums.FirstOrDefault()?.CoverTrack ?? Tracks[0];
}

public sealed class Library
{
    public static readonly Library Empty = new(new(), new(), new());

    public List<Track> Songs { get; }
    public List<Album> Albums { get; }
    public List<Artist> Artists { get; }
    public List<MusicFolder> Folders { get; }

    private readonly Dictionary<string, Track> byPath;
    private readonly Dictionary<string, List<Track>> byName;
    private readonly Dictionary<string, List<Track>> byTitle;
    private readonly Dictionary<string, Album> albumByKey;
    private readonly Dictionary<string, Artist> artistByKey;

    public Library(List<Track> songs, List<Album> albums, List<Artist> artists)
    {
        Songs = songs;
        Albums = albums;
        Artists = artists;
        byPath = songs.GroupBy(s => s.Path, StringComparer.OrdinalIgnoreCase).ToDictionary(g => g.Key, g => g.First(), StringComparer.OrdinalIgnoreCase);
        albumByKey = albums.ToDictionary(a => a.Key);
        artistByKey = artists.ToDictionary(a => a.Key);
        byName = songs.GroupBy(t => NameKey(t.Title, t.Artist)).ToDictionary(g => g.Key, g => g.ToList());
        byTitle = songs.GroupBy(t => Plain(t.Title)).ToDictionary(g => g.Key, g => g.ToList());
        var collator = StringComparer.Create(System.Globalization.CultureInfo.CurrentCulture, System.Globalization.CompareOptions.IgnoreCase);
        Folders = songs.GroupBy(t => System.IO.Path.GetDirectoryName(t.Path) ?? "", StringComparer.OrdinalIgnoreCase)
            .Select(g => new MusicFolder
            {
                Path = g.Key,
                Tracks = g.OrderBy(t => t.DiscNumber).ThenBy(t => t.TrackNumber == 0).ThenBy(t => t.TrackNumber).ThenBy(t => t.Title, collator).ToList(),
            })
            .OrderBy(f => f.Name, collator).ToList();
    }

    private static string Plain(string s) => new(s.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());
    private static string NameKey(string title, string artist) => Plain(title) + "|" + Plain(ArtistNames.SplitNames(artist).FirstOrDefault() ?? artist);

    /// <summary>
    /// A song saved elsewhere (the phone, a backup) as it's on this PC: by title and main artist,
    /// else by a title only one song here has. As the phone's Library.find, after its id.
    /// </summary>
    public Track? Find(string title, string artist) =>
        byName.GetValueOrDefault(NameKey(title, artist))?.FirstOrDefault()
            ?? (byTitle.GetValueOrDefault(Plain(title)) is { Count: 1 } one ? one[0] : null);

    public MusicFolder? Folder(string path) => Folders.FirstOrDefault(f => string.Equals(f.Path, path, StringComparison.OrdinalIgnoreCase));

    public Track? Track(string path) => byPath.GetValueOrDefault(path);
    public Album? Album(string key) => albumByKey.GetValueOrDefault(key);
    public Artist? Artist(string name) => artistByKey.GetValueOrDefault(ArtistNames.Key(name));
}

/// <summary>The music in the chosen folders: read from the cache at once, then checked against the disk.</summary>
public static class MusicLibrary
{
    /// <summary>What the library reads. Videos (animated artwork) are left out on purpose.</summary>
    public static readonly HashSet<string> Audio = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".flac", ".m4a", ".alac", ".aac", ".ogg", ".oga", ".opus", ".wav", ".aif", ".aiff",
        ".wma", ".ape", ".wv", ".dsf", ".dff", ".mka", ".ac3", ".eac3", ".dts", ".mpc", ".tta",
    };

    /// <summary>2: tracks carry their bit depth, sample rate and format. 3: and their genre.</summary>
    private const int CacheVersion = 3;
    private static readonly string CachePath = Path.Combine(AppDir.Root, "library.json");

    private static DispatcherQueue? ui;
    private static CancellationTokenSource? scan;

    public static Library Current { get; private set; } = Library.Empty;
    public static bool Scanning { get; private set; }

    /// <summary>Raised on the UI thread when the library or the scanning flag changes.</summary>
    public static event Action? Changed;

    private sealed class Cache
    {
        public int Version { get; set; }
        public List<Track> Tracks { get; set; } = new();
    }

    /// <summary>Reads the cache straight away (the window opens on the library as it was), then checks the folders.</summary>
    public static void Start(DispatcherQueue dispatcher)
    {
        ui = dispatcher;
        try
        {
            var cached = JsonSerializer.Deserialize<Cache>(File.ReadAllText(CachePath), AppDir.Json);
            if (cached?.Version == CacheVersion) Current = Build(cached.Tracks);
        }
        catch { }
        Rescan();
    }

    public static void Rescan()
    {
        scan?.Cancel();
        var cts = scan = new CancellationTokenSource();
        var token = cts.Token;
        Scanning = true;
        Notify();
        var folders = Settings.Value.Folders.ToList();
        var known = Current.Songs.ToDictionary(t => t.Path, StringComparer.OrdinalIgnoreCase);
        Task.Run(() =>
        {
            var files = folders.SelectMany(ListAudio).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            var tracks = new Track?[files.Count];
            Parallel.For(0, files.Count, new ParallelOptions { MaxDegreeOfParallelism = 4, CancellationToken = token }, i =>
            {
                var info = new FileInfo(files[i]);
                if (known.TryGetValue(info.FullName, out var old) && old.Modified == info.LastWriteTimeUtc.Ticks && old.Size == info.Length)
                    tracks[i] = old;
                else
                    tracks[i] = Read(info);
            });
            var list = tracks.OfType<Track>().ToList();
            var library = Build(list);
            try { AppDir.WriteSafely(CachePath, JsonSerializer.Serialize(new Cache { Version = CacheVersion, Tracks = list }, AppDir.Json)); }
            catch (IOException) { }
            return library;
        }, token).ContinueWith(t =>
        {
            ui?.TryEnqueue(() =>
            {
                if (token.IsCancellationRequested) return;
                if (t.Status == TaskStatus.RanToCompletion) Current = t.Result;
                Scanning = false;
                Notify();
            });
        }, TaskScheduler.Default);
    }

    private static void Notify()
    {
        if (ui == null || ui.HasThreadAccess) Changed?.Invoke();
        else ui.TryEnqueue(() => Changed?.Invoke());
    }

    private static IEnumerable<string> ListAudio(string root)
    {
        if (!Directory.Exists(root)) return Array.Empty<string>();
        try
        {
            return Directory.EnumerateFiles(root, "*", new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true })
                .Where(f => Audio.Contains(System.IO.Path.GetExtension(f))).ToList();
        }
        catch { return Array.Empty<string>(); }
    }

    /// <summary>A file's tags; the file name and folder stand in for any that are missing.</summary>
    private static Track Read(FileInfo f)
    {
        var fallbackTitle = System.IO.Path.GetFileNameWithoutExtension(f.Name);
        var fallbackAlbum = f.Directory?.Name ?? "";
        try
        {
            using var file = TagLib.File.Create(f.FullName);
            var tag = file.Tag;
            var artist = tag.Performers is { Length: > 0 } p ? string.Join(", ", p.Where(s => !string.IsNullOrWhiteSpace(s))) : "";
            if (string.IsNullOrWhiteSpace(artist)) artist = "Unknown artist";
            var albumArtist = tag.FirstAlbumArtist;
            return new Track
            {
                Path = f.FullName,
                Title = string.IsNullOrWhiteSpace(tag.Title) ? fallbackTitle : tag.Title.Trim(),
                Artist = artist.Trim(),
                Album = string.IsNullOrWhiteSpace(tag.Album) ? fallbackAlbum : tag.Album.Trim(),
                AlbumArtist = string.IsNullOrWhiteSpace(albumArtist) ? ArtistNames.SplitNames(artist)[0] : albumArtist.Trim(),
                DurationMs = (long)file.Properties.Duration.TotalMilliseconds,
                TrackNumber = (int)tag.Track,
                DiscNumber = (int)tag.Disc,
                Year = (int)tag.Year,
                HasEmbeddedArt = tag.Pictures is { Length: > 0 },
                BitsPerSample = file.Properties.BitsPerSample,
                SampleRate = file.Properties.AudioSampleRate,
                Codec = CodecOf(f.Extension, file.Properties.Description),
                Genre = (tag.FirstGenre ?? "").Trim(),
                Modified = f.LastWriteTimeUtc.Ticks,
                Size = f.Length,
            }.WithAlacDetails();
        }
        catch
        {
            // A file the tag reader doesn't know: it's still listed, under its file name.
            return new Track
            {
                Path = f.FullName, Title = fallbackTitle, Artist = "Unknown artist", Album = fallbackAlbum, AlbumArtist = "Unknown artist",
                Modified = f.LastWriteTimeUtc.Ticks, Size = f.Length,
            };
        }
    }

    /// <summary>
    /// An Apple Lossless file's bit depth and sample rate, from its ALAC header: the tag reader
    /// gives no bit depth for these, so the badge said "48 kHz ALAC" with no "24-bit".
    /// </summary>
    private static Track WithAlacDetails(this Track t)
    {
        if (t.Codec != "ALAC" || t.BitsPerSample > 0) return t;
        try
        {
            using var fs = File.OpenRead(t.Path);
            var moov = FindAtom(fs, "moov");
            if (moov == null) return t;
            var type = "alac"u8;
            // The ALAC config box: 36 bytes, "alac", version/flags, frame length, compatible version,
            // bit depth, then (after other fields) the sample rate at its end.
            for (var i = 4; i + 32 <= moov.Length; i++)
            {
                if (!moov.AsSpan(i, 4).SequenceEqual(type)) continue;
                var size = (moov[i - 4] << 24) | (moov[i - 3] << 16) | (moov[i - 2] << 8) | moov[i - 1];
                if (size != 36) continue;
                t.BitsPerSample = moov[i + 13];
                var rate = (moov[i + 28] << 24) | (moov[i + 29] << 16) | (moov[i + 30] << 8) | moov[i + 31];
                if (rate > 0) t.SampleRate = rate;
                break;
            }
        }
        catch { }
        return t;
    }

    /// <summary>A top-level MP4 box's contents (moov sits at the start or the end of the file).</summary>
    private static byte[]? FindAtom(FileStream fs, string name)
    {
        var head = new byte[16];
        long pos = 0;
        while (pos + 8 <= fs.Length)
        {
            fs.Position = pos;
            if (fs.Read(head, 0, 8) < 8) return null;
            long size = (uint)((head[0] << 24) | (head[1] << 16) | (head[2] << 8) | head[3]);
            var type = System.Text.Encoding.ASCII.GetString(head, 4, 4);
            var header = 8;
            if (size == 1)
            {
                if (fs.Read(head, 8, 8) < 8) return null;
                size = 0;
                for (var k = 8; k < 16; k++) size = (size << 8) | head[k];
                header = 16;
            }
            else if (size == 0) size = fs.Length - pos;
            if (size < header) return null;
            if (type == name)
            {
                var len = (int)Math.Min(size - header, 64L * 1024 * 1024);
                var data = new byte[len];
                fs.Position = pos + header;
                var read = 0;
                while (read < len) { var n = fs.Read(data, read, len - read); if (n <= 0) break; read += n; }
                return data;
            }
            pos += size;
        }
        return null;
    }

    /// <summary>A short name for the file's format, from its extension and what the tag reader says it holds.</summary>
    private static string CodecOf(string extension, string? description)
    {
        var d = description ?? "";
        return extension.ToLowerInvariant() switch
        {
            ".flac" => "FLAC",
            ".wav" => "WAV",
            ".aif" or ".aiff" => "AIFF",
            ".ape" => "APE",
            ".wv" => "WavPack",
            ".tta" => "TTA",
            ".dsf" or ".dff" => "DSD",
            ".mp3" => "MP3",
            ".ogg" or ".oga" => d.Contains("opus", StringComparison.OrdinalIgnoreCase) ? "Opus" : "Vorbis",
            ".opus" => "Opus",
            ".wma" => "WMA",
            ".ac3" => "AC-3",
            ".eac3" => "E-AC-3",
            ".m4a" or ".alac" or ".mp4" or ".aac" =>
                d.Contains("alac", StringComparison.OrdinalIgnoreCase) ? "ALAC"
                : d.Contains("ec-3", StringComparison.OrdinalIgnoreCase) || d.Contains("eac3", StringComparison.OrdinalIgnoreCase) ? "E-AC-3"
                : d.Contains("ac-3", StringComparison.OrdinalIgnoreCase) ? "AC-3"
                : "AAC",
            _ => extension.TrimStart('.').ToUpperInvariant(),
        };
    }

    private static Library Build(List<Track> tracks)
    {
        var collator = StringComparer.Create(CultureInfo.CurrentCulture, CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace);
        var albums = tracks.GroupBy(t => t.AlbumKey).Select(g =>
        {
            var sorted = g.OrderBy(t => t.DiscNumber).ThenBy(t => t.TrackNumber == 0).ThenBy(t => t.TrackNumber).ThenBy(t => t.Title, collator).ToList();
            return new Album { Key = g.Key, Title = sorted[0].Album, Artist = sorted[0].AlbumArtist, Year = sorted.Max(t => t.Year), Tracks = sorted };
        }).OrderBy(a => a.Title, collator).ToList();
        var albumByKey = albums.ToDictionary(a => a.Key);

        // A song by "A, B & C feat. D" counts for each of them.
        var byArtist = new Dictionary<string, (string Name, List<Track> Tracks)>();
        foreach (var t in tracks)
            foreach (var name in t.Artists)
            {
                var key = ArtistNames.Key(name);
                if (!byArtist.TryGetValue(key, out var entry)) byArtist[key] = entry = (name, new List<Track>());
                entry.Tracks.Add(t);
            }
        var artists = byArtist.Select(kv => new Artist
        {
            Key = kv.Key,
            Name = kv.Value.Name,
            Tracks = kv.Value.Tracks.OrderBy(t => t.Title, collator).ToList(),
            Albums = kv.Value.Tracks.Select(t => t.AlbumKey).Distinct().Select(k => albumByKey[k]).OrderByDescending(a => a.Year).ToList(),
        }).OrderBy(a => a.Name, collator).ToList();

        return new Library(tracks.OrderBy(t => t.Title, collator).ToList(), albums, artists);
    }
}

public static class Format
{
    public static string Time(long ms)
    {
        if (ms <= 0) return "0:00";
        var t = TimeSpan.FromMilliseconds(ms);
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }
}
