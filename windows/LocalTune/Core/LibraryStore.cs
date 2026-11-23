// Ported from the phone app's core/.../data/library/LibraryStore.kt. The file format is the
// phone's own, so playlists from a phone backup read straight in.

using System.Text.Json;
using System.Text.Json.Nodes;

namespace LocalTune.Core;

/// <summary>A song as stored in a playlist: enough to find it again (by title and artist when the id is the phone's).</summary>
public sealed class SongRef
{
    public string VideoId { get; set; } = "";
    public string Title { get; set; } = "";
    public string Artist { get; set; } = "";
    public string? ThumbnailUrl { get; set; }
    public string? DurationText { get; set; }
    public string? AlbumName { get; set; }
    public bool? Explicit { get; set; }
    public string? AlbumId { get; set; }
    public string? ArtistId { get; set; }

    public static SongRef Of(Track t) => new()
    {
        VideoId = History.IdOf(t),
        Title = t.Title,
        Artist = t.Artist,
        DurationText = t.DurationText,
        AlbumName = t.Album,
    };

    /// <summary>The song on this PC: by its file when it was saved here, else by title and artist.</summary>
    public Track? Resolve(Library lib) =>
        VideoId.StartsWith("file:") ? lib.Track(VideoId[5..]) ?? lib.Find(Title, Artist) : lib.Find(Title, Artist);
}

public sealed class LocalPlaylist
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public List<SongRef> Songs { get; set; } = new();
    public long CreatedAt { get; set; }
    public string? CoverUrl { get; set; }

    /// <summary>The playlist's songs that are on this PC, in its order.</summary>
    public List<Track> Tracks(Library lib) => Songs.Select(s => s.Resolve(lib)).OfType<Track>().ToList();
}

/// <summary>The saved document, as the phone writes it; the parts Windows doesn't use yet are kept as they are.</summary>
public sealed class LibraryDocument
{
    public List<SongRef> Liked { get; set; } = new();
    public List<LocalPlaylist> Playlists { get; set; } = new();
    public List<string> Disliked { get; set; } = new();
    public JsonArray HiddenSongs { get; set; } = new();
    public JsonArray HiddenArtists { get; set; } = new();
}

/// <summary>Playlists and liked songs, kept on this PC.</summary>
public static class LibraryStore
{
    private static readonly string FilePath = Path.Combine(AppDir.Root, "library-store.json");

    public static LibraryDocument Doc { get; private set; } = Load();

    /// <summary>Raised when a playlist is made, changed or removed.</summary>
    public static event Action? Changed;

    public static List<LocalPlaylist> Playlists => Doc.Playlists;

    public static LocalPlaylist? Playlist(string id) => Doc.Playlists.FirstOrDefault(p => p.Id == id);

    public static LocalPlaylist Create(string name, IEnumerable<Track>? songs = null)
    {
        var p = new LocalPlaylist
        {
            Id = "local-" + Guid.NewGuid().ToString("N")[..12],
            Name = name.Trim().Length > 0 ? name.Trim() : "New playlist",
            Songs = (songs ?? Array.Empty<Track>()).Select(SongRef.Of).ToList(),
            CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
        Doc.Playlists.Insert(0, p);
        Save();
        return p;
    }

    /// <summary>Adds the songs that aren't in it already; returns how many were added.</summary>
    public static int Add(string id, IEnumerable<Track> tracks)
    {
        if (Playlist(id) is not { } p) return 0;
        var lib = MusicLibrary.Current;
        var have = p.Songs.Select(s => s.Resolve(lib)?.Path).OfType<string>().ToHashSet(StringComparer.OrdinalIgnoreCase);
        var added = 0;
        foreach (var t in tracks)
        {
            if (!have.Add(t.Path)) continue;
            p.Songs.Add(SongRef.Of(t));
            added++;
        }
        if (added > 0) Save();
        return added;
    }

    public static void Remove(string id, Track track)
    {
        if (Playlist(id) is not { } p) return;
        var lib = MusicLibrary.Current;
        var i = p.Songs.FindIndex(s => string.Equals(s.Resolve(lib)?.Path, track.Path, StringComparison.OrdinalIgnoreCase));
        if (i < 0) return;
        p.Songs.RemoveAt(i);
        Save();
    }

    // ---- Liked songs (newest like first, as on the phone) ----

    public static bool IsLiked(Track t)
    {
        var lib = MusicLibrary.Current;
        return Doc.Liked.Any(s => string.Equals(s.Resolve(lib)?.Path, t.Path, StringComparison.OrdinalIgnoreCase));
    }

    public static List<Track> LikedTracks(Library lib) => Doc.Liked.Select(s => s.Resolve(lib)).OfType<Track>().ToList();

    public static void Like(Track t)
    {
        if (IsLiked(t)) return;
        Doc.Liked.Insert(0, SongRef.Of(t));
        Save();
    }

    public static void Unlike(Track t)
    {
        var lib = MusicLibrary.Current;
        if (Doc.Liked.RemoveAll(s => string.Equals(s.Resolve(lib)?.Path, t.Path, StringComparison.OrdinalIgnoreCase)) > 0) Save();
    }

    /// <summary>Playlists, likes and the rest forgotten (Settings › Clear my data). The songs themselves aren't touched.</summary>
    public static void Clear()
    {
        Doc = new LibraryDocument();
        Save();
    }

    public static void Rename(string id, string name)
    {
        if (Playlist(id) is not { } p || name.Trim().Length == 0) return;
        p.Name = name.Trim();
        Save();
    }

    public static void Delete(string id)
    {
        if (Doc.Playlists.RemoveAll(p => p.Id == id) > 0) Save();
    }

    /// <summary>
    /// A phone backup's playlists and likes, added to the ones here: a playlist already imported
    /// (same id) is brought up to date rather than doubled; likes are added once each.
    /// Returns how many playlists and likes came in.
    /// </summary>
    public static (int Playlists, int Liked) Merge(LibraryDocument imported)
    {
        foreach (var p in imported.Playlists)
        {
            var i = Doc.Playlists.FindIndex(x => x.Id == p.Id);
            if (i >= 0) Doc.Playlists[i] = p;
            else Doc.Playlists.Add(p);
        }
        var liked = Doc.Liked.Select(s => (s.Title, s.Artist)).ToHashSet();
        var newLikes = imported.Liked.Where(s => liked.Add((s.Title, s.Artist))).ToList();
        Doc.Liked.AddRange(newLikes);
        foreach (var d in imported.Disliked) if (!Doc.Disliked.Contains(d)) Doc.Disliked.Add(d);
        Save();
        return (imported.Playlists.Count, newLikes.Count);
    }

    private static void Save()
    {
        try { AppDir.WriteSafely(FilePath, JsonSerializer.Serialize(Doc, History.Json)); }
        catch (IOException) { }
        Changed?.Invoke();
    }

    private static LibraryDocument Load()
    {
        try { return JsonSerializer.Deserialize<LibraryDocument>(File.ReadAllText(FilePath), History.Json) ?? new(); }
        catch (FileNotFoundException) { return new(); }
        catch
        {
            try { File.Copy(FilePath, FilePath + ".unreadable", overwrite: true); } catch { }
            return new();
        }
    }
}

/// <summary>
/// A backup from the LocalTune phone app (Settings › Music and storage › Backup): its history,
/// playlists and likes are added to this PC's. As on the phone, only LocalTune's (or OpenTune's)
/// backups are taken, and the whole file is read before anything is changed. The phone's
/// settings aren't taken: they're for the phone.
/// </summary>
public static class Backup
{
    public sealed record Result(int Listens, int Playlists, int Liked);

    /// <summary>
    /// Writes this PC's history, playlists and likes as a LocalTune backup, in the phone app's own
    /// format: the phone imports it (Settings › Music and storage › Backup › Import), and so does
    /// LocalTune on another PC. The phone matches the songs to its own files by title and artist.
    /// Settings aren't included: the phone's and this PC's are different things.
    /// </summary>
    public static void Write(string path)
    {
        var doc = new JsonObject
        {
            ["app"] = "LocalTune",
            ["version"] = 1,
            ["history"] = JsonSerializer.SerializeToNode(History.Records, History.Json),
            ["library"] = JsonSerializer.SerializeToNode(LibraryStore.Doc, History.Json),
        };
        AppDir.WriteSafely(path, doc.ToJsonString());
    }

    public static string SuggestedName() => $"localtune-backup-{DateTime.Now:yyyy-MM-dd}.json";

    /// <summary>The backup's parts, read and checked. Safe to call off the UI thread: nothing is changed yet.</summary>
    public sealed record Parts(List<PlayRecord>? History, LibraryDocument? Library);

    public static Parts Read(string path)
    {
        JsonObject doc;
        try { doc = JsonNode.Parse(File.ReadAllText(path)) as JsonObject ?? throw new FormatException(); }
        catch (Exception e) when (e is JsonException or FormatException) { throw new InvalidDataException("This isn't a LocalTune backup."); }
        string? app = null;
        try { app = doc["app"]?.GetValue<string>(); } catch (InvalidOperationException) { }
        if (app is not ("LocalTune" or "OpenTune") || (doc["history"] == null && doc["library"] == null))
            throw new InvalidDataException("This isn't a LocalTune backup.");
        // Every part is read first: a damaged file changes nothing, rather than half of it.
        try
        {
            return new Parts(
                doc["history"]?.Deserialize<List<PlayRecord>>(History.Json),
                doc["library"]?.Deserialize<LibraryDocument>(History.Json));
        }
        catch (JsonException) { throw new InvalidDataException("This backup is damaged and couldn't be read."); }
    }

    /// <summary>
    /// Adds what was read to this PC's history and playlists. On the UI thread: the pages showing
    /// them are told of the change and rebuilt, which only that thread may do.
    /// </summary>
    public static Result Apply(Parts parts)
    {
        var listens = parts.History != null ? History.Merge(parts.History) : 0;
        var (playlists, liked) = parts.Library != null ? LibraryStore.Merge(parts.Library) : (0, 0);
        return new Result(listens, playlists, liked);
    }
}
