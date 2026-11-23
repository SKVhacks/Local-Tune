// Ported from the phone app's core/.../data/history/Wrapped.kt. Keep the two in step.

using System.Globalization;

namespace LocalTune.Core;

/// <summary>
/// A listening history summed up: how long, who and what most, when in the day, the busiest day,
/// the longest run of days and the song played most in a single day. All from this PC's history.
/// </summary>
public static class Replay
{
    /// <summary>A listen with no measured time counts as this much; most songs run about three minutes.</summary>
    private const long GuessedMs = 3 * 60_000;

    public sealed record Entry(string Title, string Subtitle, int Plays, long Minutes, PlayRecord First);

    public enum Clock { Night, Morning, Day, Evening }

    public static (string Title, string Line) Describe(Clock c) => c switch
    {
        Clock.Night => ("Night owl", "Your music comes alive after dark"),
        Clock.Morning => ("Early bird", "You start the day with a soundtrack"),
        Clock.Day => ("Daydreamer", "Your music keeps you company all day"),
        _ => ("Sunset chaser", "Evenings are when you press play"),
    };

    public sealed class Summary
    {
        public long Minutes { get; init; }
        public int Plays { get; init; }
        public int Songs { get; init; }
        public int Artists { get; init; }
        public List<Entry> TopSongs { get; init; } = new();
        public List<Entry> TopArtists { get; init; } = new();
        public Entry? TopAlbum { get; init; }
        /// <summary>Minutes listened in each hour of the day, 0 to 23.</summary>
        public long[] Hours { get; init; } = new long[24];
        public int PeakHour { get; init; }
        public Clock Clock { get; init; }
        /// <summary>Minutes in each of the last twelve months, oldest first, with each month's short name.</summary>
        public List<(string Month, long Minutes)> Months { get; init; } = new();
        public DateTime? BusiestDay { get; init; }
        public long BusiestDayMinutes { get; init; }
        public int StreakDays { get; init; }
        /// <summary>The song played most times in one day.</summary>
        public Entry? OnRepeat { get; init; }
        public Entry? First { get; init; }
        public bool IsEmpty => Plays == 0;
    }

    public static long ListenedMs(PlayRecord r) => r.ListenedMs > 0 ? r.ListenedMs : DurationMs(r.DurationText) ?? GuessedMs;

    public static Summary Summarize(IReadOnlyList<PlayRecord> records, DateTime since)
    {
        var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var sinceMs = new DateTimeOffset(since).ToUnixTimeMilliseconds();
        var window = records.Where(r => r.PlayedAt >= sinceMs && r.PlayedAt <= nowMs).ToList();
        static DateTime Local(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
        static long Minutes(IEnumerable<PlayRecord> list) => list.Sum(ListenedMs) / 60_000;
        static Entry MakeEntry(List<PlayRecord> list, string title, string subtitle) => new(title, subtitle, list.Count, Minutes(list), list[0]);
        IOrderedEnumerable<(string Key, List<PlayRecord> List)> Ranked(IEnumerable<(string Key, List<PlayRecord> List)> groups) =>
            groups.OrderByDescending(g => g.List.Count).ThenByDescending(g => g.List.Sum(ListenedMs));

        var bySong = Ranked(window.GroupBy(r => SongKey(r)).Select(g => (g.Key, g.ToList()))).ToList();
        var byArtist = Ranked(window.GroupBy(r => MainArtist(r.Artist)).Where(g => g.Key.Length > 0).Select(g => (g.Key, g.ToList()))).ToList();
        var byAlbum = Ranked(window.Where(r => !string.IsNullOrWhiteSpace(r.AlbumName)).GroupBy(r => r.AlbumName!).Select(g => (g.Key, g.ToList()))).ToList();

        var hours = new long[24];
        foreach (var r in window) hours[Local(r.PlayedAt).Hour] += ListenedMs(r);
        var peak = window.Count == 0 ? 20 : Array.IndexOf(hours, hours.Max());
        // The part of the day with the most listening, not just the peak hour.
        var parts = new Dictionary<Clock, int[]>
        {
            [Clock.Night] = new[] { 22, 23, 0, 1, 2, 3, 4 },
            [Clock.Morning] = new[] { 5, 6, 7, 8, 9, 10 },
            [Clock.Day] = new[] { 11, 12, 13, 14, 15, 16 },
            [Clock.Evening] = new[] { 17, 18, 19, 20, 21 },
        };
        var clock = parts.OrderByDescending(kv => kv.Value.Sum(h => hours[h])).First().Key;

        var byDay = window.GroupBy(r => Local(r.PlayedAt).Date).ToDictionary(g => g.Key, g => g.ToList());
        var busiest = byDay.Count == 0 ? (KeyValuePair<DateTime, List<PlayRecord>>?)null : byDay.MaxBy(kv => kv.Value.Sum(ListenedMs));

        // The longest run of days in a row with something played.
        int streak = 0, run = 0;
        DateTime? previous = null;
        foreach (var d in byDay.Keys.OrderBy(d => d))
        {
            run = previous is { } p && p.AddDays(1) == d ? run + 1 : 1;
            streak = Math.Max(streak, run);
            previous = d;
        }

        var onRepeat = byDay.Values.SelectMany(list => list.GroupBy(SongKey).Select(g => g.ToList()))
            .OrderByDescending(l => l.Count).ThenBy(l => l[0].PlayedAt).FirstOrDefault(l => l.Count >= 2);

        var nowLocal = DateTime.Now;
        var months = Enumerable.Range(0, 12).Reverse().Select(back =>
        {
            var m = new DateTime(nowLocal.Year, nowLocal.Month, 1).AddMonths(-back);
            var minutes = Minutes(records.Where(r => { var t = Local(r.PlayedAt); return t.Year == m.Year && t.Month == m.Month; }));
            return (CultureInfo.CurrentCulture.DateTimeFormat.GetAbbreviatedMonthName(m.Month), minutes);
        }).ToList();

        return new Summary
        {
            Minutes = Minutes(window),
            Plays = window.Count,
            Songs = bySong.Count,
            Artists = byArtist.Count,
            TopSongs = bySong.Take(5).Select(g => MakeEntry(g.List, g.List[0].Title, g.List[0].Artist)).ToList(),
            TopArtists = byArtist.Take(5).Select(g => MakeEntry(g.List, g.Key, $"{g.List.Select(SongKey).Distinct().Count()} songs")).ToList(),
            TopAlbum = byAlbum.Count > 0 ? MakeEntry(byAlbum[0].List, byAlbum[0].Key, MainArtist(byAlbum[0].List[0].Artist)) : null,
            Hours = hours.Select(h => h / 60_000).ToArray(),
            PeakHour = peak,
            Clock = clock,
            Months = months,
            BusiestDay = busiest?.Key,
            BusiestDayMinutes = busiest is { } b ? Minutes(b.Value) : 0,
            StreakDays = streak,
            OnRepeat = onRepeat != null ? MakeEntry(onRepeat, onRepeat[0].Title, onRepeat[0].Artist) : null,
            First = window.Count > 0 ? window.MinBy(r => r.PlayedAt) is { } f ? MakeEntry(new List<PlayRecord> { f }, f.Title, f.Artist) : null : null,
        };
    }

    /// <summary>One song, whether the listen was on this PC or imported from the phone.</summary>
    private static string SongKey(PlayRecord r) =>
        new string((r.Title + "|" + MainArtist(r.Artist)).ToLowerInvariant().Where(c => char.IsLetterOrDigit(c) || c == '|').ToArray());

    /// <summary>"Arijit Singh, Shreya Ghoshal" counts for Arijit Singh, like Replay on the phone.</summary>
    public static string MainArtist(string artist)
    {
        var a = artist.Split(',')[0];
        var i = a.IndexOf(" & ", StringComparison.Ordinal);
        return (i >= 0 ? a[..i] : a).Trim();
    }

    private static long? DurationMs(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        long total = 0;
        foreach (var part in text.Split(':'))
        {
            if (!long.TryParse(part.Trim(), out var n)) return null;
            total = total * 60 + n;
        }
        return total > 0 ? total * 1000 : null;
    }
}
