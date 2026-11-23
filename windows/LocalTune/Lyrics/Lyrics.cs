// Ported from the phone app's core/.../data/lyrics/Lyrics.kt. Keep the two in step:
// a lyric file should light the same way on the phone and on Windows.

using System.Text.RegularExpressions;

namespace LocalTune.Lyrics;

/// <summary>One timed word (or syllable) inside a synced line.</summary>
public sealed record LyricWord(string Text, long StartMs, long EndMs);

public sealed record LyricLine
{
    public required long StartMs { get; init; }
    public required long EndMs { get; init; }
    public required string Text { get; init; }
    /// <summary>Per-word timing: exact when the source has word sync, otherwise spread across the line.</summary>
    public required IReadOnlyList<LyricWord> Words { get; init; }
    public required bool WordSynced { get; init; }
    /// <summary>Who sings it: 0 the lead, 1 a second voice in a duet, 2 everyone together.</summary>
    public int Voice { get; init; } = Voices.Lead;
    /// <summary>Backing vocals sung under this line, shown smaller beneath it.</summary>
    public string? Background { get; init; }
    /// <summary>When <see cref="Background"/> is sung, word by word; empty when the source only gives its words.</summary>
    public IReadOnlyList<LyricWord> BackgroundWords { get; init; } = Array.Empty<LyricWord>();
    /// <summary>The line's end is when its singing ends (Apple's TTML), not just when the next line starts.</summary>
    public bool ExactEnd { get; init; }
    /// <summary>Right to left, as the source marks it; null when it doesn't say.</summary>
    public bool? Rtl { get; init; }

    /// <summary>When the singing in this line actually stops: the end of its last word, or the line's end.</summary>
    public long SungUntilMs => Words.Count > 0 ? Words.Max(w => w.EndMs) : EndMs;

    /// <summary>Whether the line reads right to left: as its source says, else by its first letter with a direction.</summary>
    public bool ReadsRightToLeft
    {
        get
        {
            if (Rtl is { } r) return r;
            foreach (var c in Text)
            {
                if (IsRtlChar(c)) return true;
                if (char.IsLetter(c)) return false;
            }
            return false;
        }
    }

    private static bool IsRtlChar(char c) =>
        c is >= '֐' and <= 'ࣿ' or >= 'יִ' and <= '﷿' or >= 'ﹰ' and <= '﻿';
}

public static class Voices
{
    public const int Lead = 0;
    public const int Second = 1;
    public const int Together = 2;
}

/// <summary>A named stretch of a song (verse, chorus, bridge) as the lyrics source marks it.</summary>
public sealed record SongPart(string Name, long StartMs, long EndMs);

/// <summary>A stretch with no singing between two lines, long enough to show as an instrumental break.</summary>
public sealed record LyricGap(int AfterIndex, long StartMs, long EndMs);

public abstract record LyricsDoc(string Source);

public sealed record SyncedLyrics(IReadOnlyList<LyricLine> Lines, string Source, IReadOnlyList<SongPart> Parts, long RecordingMs) : LyricsDoc(Source);

public sealed record PlainLyrics(string Text, string Source) : LyricsDoc(Source);

public static class LyricLines
{
    /// <summary>
    /// Instrumental breaks between lines: a line stops being sung and the next
    /// doesn't start for at least <paramref name="minMs"/>.
    /// </summary>
    public static List<LyricGap> InstrumentalGaps(this IReadOnlyList<LyricLine> lines, long minMs = 5_000)
    {
        var gaps = new List<LyricGap>();
        for (var i = 0; i + 1 < lines.Count; i++)
        {
            var from = lines[i].SungUntilMs;
            var to = lines[i + 1].StartMs;
            if (to - from >= minMs) gaps.Add(new LyricGap(i, from, to));
        }
        return gaps;
    }
}

/// <summary>Parses LRC, including the enhanced &lt;mm:ss.xx&gt; word tags some sources carry.</summary>
public static partial class LrcParser
{
    [GeneratedRegex(@"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]")] private static partial Regex LineTag();
    [GeneratedRegex(@"<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>")] private static partial Regex WordTag();
    [GeneratedRegex(@"\[offset:\s*([+-]?\d+)]", RegexOptions.IgnoreCase)] private static partial Regex OffsetTag();
    [GeneratedRegex(@"^\[bg:\s*(.*)]$", RegexOptions.IgnoreCase)] private static partial Regex BgLine();
    [GeneratedRegex(@"^\s*(v1|v2|v1000)\s*:\s*", RegexOptions.IgnoreCase)] private static partial Regex VoiceMark();
    [GeneratedRegex(@"\s+")] private static partial Regex Spaces();
    [GeneratedRegex(@"\S+\s*")] private static partial Regex Tokens();

    /// <summary>A line with no following line is given this long before it ends.</summary>
    private const long LastLineMs = 5_000;

    public static List<LyricLine> Parse(string lrc, long durationMs = 0)
    {
        var offsetMatch = OffsetTag().Match(lrc);
        var offset = offsetMatch.Success && long.TryParse(offsetMatch.Groups[1].Value, out var o) ? o : 0;

        // A line can carry several timestamps when a chorus repeats. Only the run of tags at the
        // very start are line times: a [mm:ss.xx] further in is read as a word tag.
        var raw = new List<(long Start, string Body)>();
        // Backing vocals on their own line, [bg: <00:12.00>ooh <00:12.40>yeah], go under the line before them.
        var backing = new Dictionary<int, string>();
        foreach (var rawLine in lrc.Split('\n'))
        {
            var line = rawLine.TrimEnd('\r');
            var bg = BgLine().Match(line.Trim());
            if (bg.Success)
            {
                var said = Spaces().Replace(WordTag().Replace(LineTag().Replace(bg.Groups[1].Value, ""), ""), " ").Trim();
                if (said.Length > 0 && raw.Count > 0)
                    backing[raw.Count - 1] = backing.TryGetValue(raw.Count - 1, out var before) ? before + " " + said : said;
                continue;
            }
            var tags = LineTag().Matches(line).ToList();
            if (tags.Count == 0 || tags[0].Index != 0) continue;
            var lead = 1;
            while (lead < tags.Count && tags[lead].Index == tags[lead - 1].Index + tags[lead - 1].Length) lead++;
            var bodyStart = tags[lead - 1].Index + tags[lead - 1].Length;
            var rest = line[bodyStart..];
            var body = lead == tags.Count ? rest : LineTag().Replace(rest, m => "<" + m.Value[1..^1] + ">");
            foreach (var tag in tags.Take(lead)) raw.Add((Math.Max(0, ToMs(tag) - offset), body));
        }
        // Stable: lines sharing a time keep their written order, and a backing line stays with its own.
        var order = Enumerable.Range(0, raw.Count).OrderBy(i => raw[i].Start).ToList();

        var lines = new List<LyricLine>();
        for (var i = 0; i < order.Count; i++)
        {
            var at = order[i];
            var (start, written) = raw[at];
            long end = i + 1 < order.Count
                ? raw[order[i + 1]].Start
                : durationMs > start ? Math.Min(durationMs, start + LastLineMs * 2) : start + LastLineMs;
            // Duet marks as AMLL and Lyricify write them: "v1:" the lead, "v2:" the second voice.
            var mark = VoiceMark().Match(written);
            var voice = mark.Success ? mark.Groups[1].Value.ToLowerInvariant() switch
            {
                "v2" => Voices.Second,
                "v1000" => Voices.Together,
                _ => Voices.Lead,
            } : Voices.Lead;
            var body = mark.Success ? written[(mark.Index + mark.Length)..] : written;
            var timed = ParseWords(body, start, end, offset);
            var text = Spaces().Replace(WordTag().Replace(body, ""), " ").Trim();
            if (text.Length == 0) continue; // an instrumental gap marker
            lines.Add(new LyricLine
            {
                StartMs = start,
                EndMs = end,
                Text = text,
                Words = timed ?? SpreadWords(text, start, end),
                WordSynced = timed != null,
                Voice = voice,
                Background = backing.GetValueOrDefault(at),
            });
        }
        return lines;
    }

    private static List<LyricWord>? ParseWords(string body, long lineStart, long lineEnd, long offset)
    {
        var tags = WordTag().Matches(body).ToList();
        if (tags.Count == 0) return null;
        var words = new List<LyricWord>();
        // Words before the first tag are sung from the line's own time: "[00:20]Hel[00:20.4]lo".
        var lead = body[..tags[0].Index];
        if (!string.IsNullOrWhiteSpace(lead)) words.Add(new LyricWord(lead, lineStart, Math.Max(lineStart, ToMs(tags[0]) - offset)));
        else if (tags.Count < 2) return null;
        for (var i = 0; i < tags.Count; i++)
        {
            var tag = tags[i];
            var textEnd = i + 1 < tags.Count ? tags[i + 1].Index : body.Length;
            var text = body[(tag.Index + tag.Length)..textEnd];
            if (string.IsNullOrWhiteSpace(text)) continue;
            var start = Math.Max(0, ToMs(tag) - offset);
            var end = i + 1 < tags.Count ? Math.Max(start, ToMs(tags[i + 1]) - offset) : lineEnd;
            words.Add(new LyricWord(text, start, end));
        }
        return words.Count > 0 ? words : null;
    }

    /// <summary>
    /// Words laid by letter from <paramref name="start"/>, for a line timed only as a whole (Apple's
    /// line-timed TTML), at a sung pace: no slower than over 45% of the line and done by 90% of it.
    /// </summary>
    public static List<LyricWord> SpreadAcross(string text, long start, long end) =>
        end <= start ? SpreadWords(text, start, end) : SpreadOver(text, start, start + SweepMs(text, end - start));

    /// <summary>Words laid by letter over exactly start..end, with no gaps.</summary>
    public static List<LyricWord> SpreadOver(string text, long start, long end)
    {
        var tokens = Tokens().Matches(text).Select(m => m.Value).ToList();
        if (tokens.Count == 0 || end <= start) return SpreadWords(text, start, end);
        var weights = tokens.Select(t => Math.Max(1, t.Count(char.IsLetterOrDigit))).ToList();
        double total = weights.Sum();
        var acc = 0;
        var words = new List<LyricWord>(tokens.Count);
        for (var i = 0; i < tokens.Count; i++)
        {
            var from = start + (long)((end - start) * (acc / total));
            acc += weights[i];
            words.Add(new LyricWord(tokens[i], from, start + (long)((end - start) * (acc / total))));
        }
        return words;
    }

    public static long SweepMs(string text, long lineMs) =>
        Math.Max(1, Math.Clamp(PacedMs(text), (long)(lineMs * MinShare), (long)(lineMs * MaxShare)));

    /// <summary>About how long text takes at the pace of most pop and rap vocals: some 12 letters a second.</summary>
    public static long PacedMs(string text)
    {
        long ms = 150;
        foreach (var c in text)
        {
            if (!char.IsLetterOrDigit(c)) continue;
            ms += IsSyllabic(c) ? 240 : 80;
        }
        return ms;
    }

    private const double MinShare = 0.45;
    private const double MaxShare = 0.9;

    public static List<LyricWord> SpreadWords(string text, long start, long end)
    {
        var tokens = Tokens().Matches(text).Select(m => m.Value).ToList();
        if (tokens.Count == 0) return new List<LyricWord>();
        // The words take an ordinary sung time, not the whole gap to the next line.
        var span = Math.Clamp(Math.Min((long)((end - start) * 0.85), SingingMs(text)), 300, 8_000);
        var totalChars = tokens.Sum(t => Math.Max(1, t.Trim().Length));
        var cursor = start;
        var words = new List<LyricWord>(tokens.Count);
        foreach (var token in tokens)
        {
            var share = span * Math.Max(1, token.Trim().Length) / totalChars;
            words.Add(new LyricWord(token, cursor, cursor + share));
            cursor += share;
        }
        return words;
    }

    /// <summary>About how long text takes to sing at an ordinary pace.</summary>
    public static long SingingMs(string text)
    {
        long ms = 400;
        foreach (var c in text)
        {
            if (!char.IsLetterOrDigit(c)) continue;
            ms += IsSyllabic(c) ? 330 : 160;
        }
        return ms;
    }

    /// <summary>Chinese, Japanese and Korean characters, each a syllable of its own.</summary>
    private static bool IsSyllabic(char c) =>
        c is >= '぀' and <= 'ヿ' // Hiragana, Katakana
            or >= 'ㇰ' and <= 'ㇿ'
            or >= '㐀' and <= '䶿' // CJK extension A
            or >= '一' and <= '鿿' // CJK
            or >= '豈' and <= '﫿'
            or >= 'ᄀ' and <= 'ᇿ' // Hangul
            or >= '㄰' and <= '㆏'
            or >= '가' and <= '힯';

    private static long ToMs(Match m)
    {
        var min = long.Parse(m.Groups[1].Value);
        var sec = long.Parse(m.Groups[2].Value);
        var frac = m.Groups[3].Value;
        long fracMs = frac.Length switch
        {
            0 => 0,
            1 => long.Parse(frac) * 100,
            2 => long.Parse(frac) * 10,
            _ => long.Parse(frac[..3]),
        };
        return min * 60_000 + sec * 1_000 + fracMs;
    }
}
