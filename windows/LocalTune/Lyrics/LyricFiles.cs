using System.Text;
using LocalTune.Core;

namespace LocalTune.Lyrics;

/// <summary>
/// A song's lyrics from a file beside it, as on the phone: Song.flac takes Song.ttml, Song.lrc
/// or Song.txt, from the same folder or a Lyrics folder inside it. Word timing beats line timing,
/// which beats plain words; between equals TTML comes first.
/// </summary>
public static class LyricFiles
{
    private static readonly string[] Extensions = { "ttml", "lrc", "elrc", "txt" };
    private static readonly Dictionary<string, LyricsDoc?> cache = new(StringComparer.OrdinalIgnoreCase);
    private static readonly Dictionary<string, IReadOnlyList<string>> writers = new(StringComparer.OrdinalIgnoreCase);

    static LyricFiles()
    {
        // Windows-1252, for old LRC files that aren't UTF-8.
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    public static async Task<LyricsDoc?> FindAsync(Track track)
    {
        lock (cache) if (cache.TryGetValue(track.Path, out var hit)) return hit;
        var found = await Task.Run(() =>
        {
            var dir = Path.GetDirectoryName(track.Path) ?? "";
            var name = Path.GetFileNameWithoutExtension(track.Path);
            var candidates = new[] { dir, Path.Combine(dir, "Lyrics") }
                .SelectMany(where => Extensions.Select(ext => Path.Combine(where, $"{name}.{ext}")))
                .Where(File.Exists)
                .DistinctBy(p => Path.GetFullPath(p).ToLowerInvariant())
                .ToList();
            LyricsDoc? best = null;
            string? bestFile = null;
            var bestRank = -1;
            foreach (var file in candidates)
            {
                LyricsDoc? doc;
                try { doc = Parse(file, track.DurationMs); }
                catch { continue; }
                if (doc == null) continue;
                var rank = Rank(doc);
                // Earlier candidates win ties: TTML before LRC, the song's folder before Lyrics.
                if (rank > bestRank) { best = doc; bestFile = file; bestRank = rank; }
            }
            var names = best != null ? WritersOf(bestFile!, track) : Array.Empty<string>();
            lock (cache) writers[track.Path] = names;
            return best;
        });
        lock (cache) cache[track.Path] = found;
        return found;
    }

    /// <summary>Who wrote the song, for "Written by" under the lyrics: known once <see cref="FindAsync"/> has run.</summary>
    public static IReadOnlyList<string> Writers(Track track)
    {
        lock (cache) return writers.TryGetValue(track.Path, out var w) ? w : Array.Empty<string>();
    }

    /// <summary>
    /// Apple's lyric files list the songwriters (every TTML in the user's library does); an LRC
    /// may name them in an [au:] line. Failing those, the song's own composer tag.
    /// </summary>
    private static IReadOnlyList<string> WritersOf(string lyricFile, Track track)
    {
        try
        {
            var text = Decode(File.ReadAllBytes(lyricFile));
            var found = System.Text.RegularExpressions.Regex.Matches(text, @"<songwriter>(.*?)</songwriter>", System.Text.RegularExpressions.RegexOptions.Singleline)
                .Select(m => System.Net.WebUtility.HtmlDecode(m.Groups[1].Value).Trim())
                .Where(n => n.Length > 0).Distinct().ToList();
            if (found.Count == 0)
            {
                var au = System.Text.RegularExpressions.Regex.Match(text, @"^\s*\[au:\s*(.+?)\s*\]\s*$", System.Text.RegularExpressions.RegexOptions.Multiline | System.Text.RegularExpressions.RegexOptions.IgnoreCase);
                if (au.Success) found = au.Groups[1].Value.Split(new[] { ',', ';', '/' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
            }
            if (found.Count == 0)
            {
                using var tags = TagLib.File.Create(track.Path);
                found = tags.Tag.Composers.SelectMany(c => c.Split(new[] { ',', ';', '/' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                    .Where(n => n.Length > 0).Distinct().ToList();
            }
            return found;
        }
        catch
        {
            return Array.Empty<string>();
        }
    }

    /// <summary>Forgets what was found, so edited lyric files are read again.</summary>
    public static void Forget()
    {
        lock (cache) { cache.Clear(); writers.Clear(); }
    }

    /// <summary>Word timing 2, line timing 1, plain words 0.</summary>
    private static int Rank(LyricsDoc l) => l switch
    {
        SyncedLyrics s => s.Lines.Any(x => x.WordSynced) ? 2 : 1,
        _ => 0,
    };

    private static LyricsDoc? Parse(string file, long durationMs)
    {
        var text = Decode(File.ReadAllBytes(file)).Trim();
        if (text.Length == 0) return null;
        var ext = Path.GetExtension(file).TrimStart('.').ToLowerInvariant();
        var source = "File · " + Path.GetFileName(file);
        var looksTtml = text.StartsWith("<?xml") || text.Contains("<tt", StringComparison.OrdinalIgnoreCase);
        if (ext == "ttml" || looksTtml)
        {
            var lines = Ttml.Parse(text);
            return lines.Count == 0 ? null : new SyncedLyrics(lines, source, Ttml.Parts(text), Ttml.Length(text) ?? 0);
        }
        var lrc = LrcParser.Parse(text, durationMs);
        return lrc.Count > 0 ? new SyncedLyrics(lrc, source, Array.Empty<SongPart>(), 0) : new PlainLyrics(text, source);
    }

    /// <summary>UTF-8 unless a byte-order mark says UTF-16; text that isn't valid UTF-8 is read as Windows-1252.</summary>
    private static string Decode(byte[] b)
    {
        if (b.Length >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF) return Encoding.UTF8.GetString(b, 3, b.Length - 3);
        if (b.Length >= 2 && b[0] == 0xFF && b[1] == 0xFE) return Encoding.Unicode.GetString(b, 2, b.Length - 2);
        if (b.Length >= 2 && b[0] == 0xFE && b[1] == 0xFF) return Encoding.BigEndianUnicode.GetString(b, 2, b.Length - 2);
        try { return new UTF8Encoding(false, throwOnInvalidBytes: true).GetString(b); }
        catch (DecoderFallbackException) { return Encoding.GetEncoding(1252).GetString(b); }
    }
}
