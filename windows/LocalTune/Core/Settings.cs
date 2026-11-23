using System.Text.Json;
using System.Text.Json.Serialization;

namespace LocalTune.Core;

/// <summary>Where LocalTune keeps its files on this PC: %APPDATA%\LocalTune.</summary>
public static class AppDir
{
    public static string Root { get; } = Create();

    private static string Create()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "LocalTune");
        Directory.CreateDirectory(dir);
        return dir;
    }

    public static readonly JsonSerializerOptions Json = new()
    {
        Converters = { new JsonStringEnumConverter() },
    };

    /// <summary>Writes beside and then moves into place, so a crash mid-write can't leave half a file.</summary>
    public static void WriteSafely(string path, string text)
    {
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, text);
        File.Move(tmp, path, overwrite: true);
    }
}

public enum RepeatMode { Off, All, One }

public sealed class AppSettings
{
    /// <summary>The folders the library is read from.</summary>
    public List<string> Folders { get; set; } = new();

    /// <summary>0..100.</summary>
    public int Volume { get; set; } = 80;

    /// <summary>The queue and where in it the last session stopped, so it picks up there.</summary>
    public List<string> Queue { get; set; } = new();
    public int QueueIndex { get; set; }
    public long PositionMs { get; set; }

    public bool Shuffle { get; set; }
    public RepeatMode Repeat { get; set; } = RepeatMode.Off;

    /// <summary>
    /// Artist pictures from Deezer's public search when none of the artist's songs carries one
    /// (only the artist's name is sent), as the phone's Settings › Appearance option.
    /// </summary>
    public bool OnlineArtistPictures { get; set; } = true;

    /// <summary>Songs snoozed from the right-click menu: kept out of Recommended until the time given (ms since 1970).</summary>
    public Dictionary<string, long> Snoozed { get; set; } = new();

    /// <summary>The sidebar open (names and icons) or closed (icons only).</summary>
    public bool SidebarOpen { get; set; } = true;

    /// <summary>Where the mini player was left: its left edge and its bottom edge (it grows upwards), in screen pixels.</summary>
    public int? MiniLeft { get; set; }
    public int? MiniBottom { get; set; }

    /// <summary>Larger or smaller lyrics, as on the phone.</summary>
    public double LyricsScale { get; set; } = 1.0;
}

/// <summary>The settings, read once and written back whenever they change.</summary>
public static class Settings
{
    private static readonly string FilePath = Path.Combine(AppDir.Root, "settings.json");
    private static readonly object Gate = new();

    public static AppSettings Value { get; } = Load();

    /// <summary>Raised on whichever thread made the change.</summary>
    public static event Action? Changed;

    public static void Update(Action<AppSettings> change)
    {
        lock (Gate)
        {
            change(Value);
            try { AppDir.WriteSafely(FilePath, JsonSerializer.Serialize(Value, AppDir.Json)); }
            catch (IOException) { }
        }
        Changed?.Invoke();
    }

    private static AppSettings Load()
    {
        try { return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), AppDir.Json) ?? new(); }
        catch { return new(); }
    }
}
