// Ported from the phone app's core/.../data/history/History.kt. The file format is the phone's
// own (the same field names), so a phone backup's history reads straight in.

using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.UI.Dispatching;

namespace LocalTune.Core;

/// <summary>One listen: a track that played long enough to count.</summary>
public sealed class PlayRecord
{
    /// <summary>"file:&lt;path&gt;" for a listen on this PC; the phone's own id ("local:123") for one imported from it.</summary>
    public string VideoId { get; set; } = "";
    public string Title { get; set; } = "";
    public string Artist { get; set; } = "";
    public string? ThumbnailUrl { get; set; }
    public string? DurationText { get; set; }
    public string? AlbumName { get; set; }
    public long PlayedAt { get; set; }
    public long ListenedMs { get; set; }
    public bool? Explicit { get; set; }
    public string? ArtistId { get; set; }
}

/// <summary>Listening history, kept on this PC only, in one JSON file.</summary>
public static class History
{
    private const int MaxRecords = 10_000;
    private static readonly string FilePath = Path.Combine(AppDir.Root, "history.json");

    public static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private static DispatcherQueueTimer? saveTimer;

    /// <summary>Newest first.</summary>
    public static List<PlayRecord> Records { get; private set; } = Load();

    /// <summary>Raised on the UI thread when a listen is added or history is imported.</summary>
    public static event Action? Changed;

    public static string IdOf(Track t) => "file:" + t.Path;

    public static void Start(DispatcherQueue ui)
    {
        // Saved a moment after the last change, not on every second of a listen.
        saveTimer = ui.CreateTimer();
        saveTimer.Interval = TimeSpan.FromSeconds(2);
        saveTimer.IsRepeating = false;
        saveTimer.Tick += (_, _) => Save();
    }

    /// <summary>Records a listen and returns its key for <see cref="UpdateListened"/>.</summary>
    public static long Record(Track t, long listenedMs)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Records.Insert(0, new PlayRecord
        {
            VideoId = IdOf(t),
            Title = t.Title,
            Artist = t.Artist,
            DurationText = t.DurationText,
            AlbumName = t.Album,
            PlayedAt = now,
            ListenedMs = listenedMs,
        });
        if (Records.Count > MaxRecords) Records.RemoveRange(MaxRecords, Records.Count - MaxRecords);
        countCache = null;
        Dirty();
        Changed?.Invoke();
        return now;
    }

    public static void UpdateListened(long playedAt, long listenedMs)
    {
        var r = Records.FirstOrDefault(x => x.PlayedAt == playedAt);
        if (r == null || r.ListenedMs == listenedMs) return;
        r.ListenedMs = listenedMs;
        Dirty();
    }

    /// <summary>
    /// Listens from a phone backup, added to the ones made here: the same listen (same song, same
    /// moment) isn't counted twice, so importing the same backup again changes nothing.
    /// </summary>
    public static int Merge(IEnumerable<PlayRecord> imported)
    {
        var seen = Records.Select(r => (r.PlayedAt, r.Title, r.Artist)).ToHashSet();
        var added = imported.Where(r => seen.Add((r.PlayedAt, r.Title, r.Artist))).ToList();
        if (added.Count == 0) return 0;
        Records = Records.Concat(added).OrderByDescending(r => r.PlayedAt).Take(MaxRecords).ToList();
        countCache = null;
        Dirty();
        Changed?.Invoke();
        return added.Count;
    }

    /// <summary>Every listen forgotten (Settings › Clear my data).</summary>
    public static void Clear()
    {
        Records = new();
        countCache = null;
        Save();
        Changed?.Invoke();
    }

    /// <summary>The song a listen was of, as it's on this PC now; null when it isn't here.</summary>
    public static Track? TrackOf(PlayRecord r, Library lib) =>
        r.VideoId.StartsWith("file:") ? lib.Track(r.VideoId[5..]) ?? lib.Find(r.Title, r.Artist) : lib.Find(r.Title, r.Artist);

    /// <summary>Each song once, most recent listen first, as found on this PC.</summary>
    public static List<Track> Recents(Library lib, int limit = 40)
    {
        var out_ = new List<Track>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var r in Records)
        {
            if (TrackOf(r, lib) is not { } t || !seen.Add(t.Path)) continue;
            out_.Add(t);
            if (out_.Count >= limit) break;
        }
        return out_;
    }

    private static Dictionary<string, int>? countCache;
    private static Library? countCacheFor;

    /// <summary>How many times this song has been played (the Songs page's Plays column); counted once, again after each new listen.</summary>
    public static int PlayCount(Track t)
    {
        var lib = MusicLibrary.Current;
        if (countCache == null || countCacheFor != lib)
        {
            countCache = PlayCounts(lib);
            countCacheFor = lib;
        }
        return countCache.GetValueOrDefault(t.Path);
    }

    /// <summary>How many times each song on this PC has been played.</summary>
    public static Dictionary<string, int> PlayCounts(Library lib)
    {
        var counts = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (var r in Records)
            if (TrackOf(r, lib) is { } t) counts[t.Path] = counts.GetValueOrDefault(t.Path) + 1;
        return counts;
    }

    private static void Dirty()
    {
        if (saveTimer == null) { Save(); return; }
        saveTimer.Stop();
        saveTimer.Start();
    }

    public static void Save()
    {
        try { AppDir.WriteSafely(FilePath, JsonSerializer.Serialize(Records, Json)); }
        catch (IOException) { }
    }

    private static List<PlayRecord> Load()
    {
        try { return JsonSerializer.Deserialize<List<PlayRecord>>(File.ReadAllText(FilePath), Json) ?? new(); }
        catch (FileNotFoundException) { return new(); }
        catch
        {
            // History that won't read is kept aside, never saved over with an empty list.
            try { File.Copy(FilePath, FilePath + ".unreadable", overwrite: true); } catch { }
            return new();
        }
    }
}
