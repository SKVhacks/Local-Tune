// Ported from the phone app's core/.../data/lyrics/Ttml.kt. Keep the two in step.

using System.Globalization;
using System.Text.RegularExpressions;

namespace LocalTune.Lyrics;

/// <summary>The TTML timed-lyrics format, as Apple Music writes it: &lt;p begin end&gt; lines, &lt;span begin end&gt; words.</summary>
public static partial class Ttml
{
    [GeneratedRegex(@"<(?:tt|body)\b([^>]*)>")] private static partial Regex DocTag();
    [GeneratedRegex(@"<div\b([^>]*)>")] private static partial Regex DivTag();
    [GeneratedRegex(@"<p\b([^>]*)>(.*?)</p>", RegexOptions.Singleline)] private static partial Regex Paragraph();
    [GeneratedRegex(@"<span\b([^>]*)>([^<]*)</span>(\s*)")] private static partial Regex Span();
    [GeneratedRegex(@"<span\b[^>]*ttm:role=""x-bg""[^>]*>((?:\s*<span\b[^>]*>[^<]*</span>\s*)*)</span>")] private static partial Regex Backing();
    [GeneratedRegex(@"<div\b([^>]*)>(.*?)</div>", RegexOptions.Singleline)] private static partial Regex Div();
    [GeneratedRegex(@"<p\b([^>]*)>")] private static partial Regex POpen();
    [GeneratedRegex(@"<body\b[^>]*\bdur=""([^""]+)""")] private static partial Regex BodyDur();
    [GeneratedRegex(@"<ttm:agent\b([^>]*)>")] private static partial Regex Agent();
    [GeneratedRegex(@"<[^>]+>")] private static partial Regex AnyTag();
    [GeneratedRegex(@"\s+")] private static partial Regex Spaces();
    [GeneratedRegex(@"^(.*?[^\s(])\s*((?:\([^()]*\)\s*)+)$")] private static partial Regex TrailingAdlibs();
    [GeneratedRegex(@"\(([^()]*)\)")] private static partial Regex Bracketed();

    private const long AdlibMinMs = 500;

    public static List<LyricLine> Parse(string xml)
    {
        var voices = VoicesOf(xml);
        // Apple's line-timed files say so. In any other, a line's timed words are word timing, even
        // when there's only one: an ad-lib line of its own ("Yeah", "Uh") sung over the next line.
        var lineTimedFile = xml.Contains("itunes:timing=\"Line\"");
        // Direction is inherited: from the document, then each <div>, then the <p> itself.
        var doc = DirectionOf(string.Join(" ", DocTag().Matches(xml).Select(m => m.Groups[1].Value)));
        var divs = DivTag().Matches(xml).Select(m => (m.Index, Dir: DirectionOf(m.Groups[1].Value))).ToList();
        var lines = new List<LyricLine>();
        foreach (Match p in Paragraph().Matches(xml))
        {
            var attrs = p.Groups[1].Value;
            var rtl = DirectionOf(attrs) ?? divs.LastOrDefault(d => d.Index < p.Index).Dir ?? doc;
            if (Time(Attr(attrs, "begin")) is not { } start) continue;
            var end = Time(Attr(attrs, "end")) ?? start + 4_000;
            // Backing vocals come wrapped in a span of their own; they're shown under the line.
            string? background = null;
            var backgroundWords = new List<LyricWord>();
            var inner = Backing().Replace(p.Groups[2].Value, m =>
            {
                var said = Spaces().Replace(Unescape(AnyTag().Replace(m.Groups[1].Value, "")), " ").Trim();
                if (said.Length > 0) background = background == null ? said : background + " " + said;
                // Their own word times, when given: they sweep on those.
                foreach (Match s in Span().Matches(m.Groups[1].Value))
                {
                    if (Time(Attr(s.Groups[1].Value, "begin")) is not { } b) continue;
                    var e = Time(Attr(s.Groups[1].Value, "end")) ?? b;
                    var word = Unescape(s.Groups[2].Value);
                    if (!string.IsNullOrWhiteSpace(word)) backgroundWords.Add(new LyricWord(word + (s.Groups[3].Value.Length > 0 ? " " : ""), b, e));
                }
                return "";
            });
            var voice = Attr(attrs, "ttm:agent") is { } agent && voices.TryGetValue(agent, out var v) ? v : Voices.Lead;
            var spans = new List<LyricWord>();
            foreach (Match s in Span().Matches(inner))
            {
                if (Time(Attr(s.Groups[1].Value, "begin")) is not { } b) continue;
                var e = Time(Attr(s.Groups[1].Value, "end")) ?? b;
                var word = new LyricWord(Unescape(s.Groups[2].Value) + s.Groups[3].Value, b, e);
                if (!string.IsNullOrWhiteSpace(word.Text)) spans.Add(word);
            }
            var text = spans.Count > 0
                ? Spaces().Replace(string.Concat(spans.Select(w => w.Text)), " ").Trim()
                : Spaces().Replace(Unescape(AnyTag().Replace(inner, "")), " ").Trim();
            if (text.Length == 0) continue;
            if (spans.Count >= 2 || (spans.Count == 1 && !lineTimedFile))
            {
                // Timed backing words are shown as timed: the text is theirs, joined.
                var bgWords = BackingWords(backgroundWords);
                lines.Add(new LyricLine
                {
                    StartMs = start, EndMs = end, Text = text, Words = spans, WordSynced = true, Voice = voice,
                    Background = bgWords.Count > 0 ? string.Concat(bgWords.Select(w => w.Text)).Trim() : background,
                    BackgroundWords = bgWords, Rtl = rtl, ExactEnd = true,
                });
                continue;
            }
            lines.Add(LineTimed(start, end, text, voice, background, rtl));
        }
        return lines;
    }

    /// <summary>The song's parts as Apple marks them on each &lt;div&gt;: itunes:song-part="Chorus".</summary>
    public static List<SongPart> Parts(string xml)
    {
        var parts = new List<SongPart>();
        foreach (Match d in Div().Matches(xml))
        {
            var name = Attr(d.Groups[1].Value, "itunes:song-part") ?? Attr(d.Groups[1].Value, "itunes:songPart");
            if (name == null) continue;
            var lines = POpen().Matches(d.Groups[2].Value).Select(m => m.Groups[1].Value).ToList();
            var start = Time(Attr(d.Groups[1].Value, "begin")) ?? lines.Select(l => Time(Attr(l, "begin"))).FirstOrDefault(t => t != null);
            if (start == null) continue;
            var end = Time(Attr(d.Groups[1].Value, "end")) ?? lines.Select(l => Time(Attr(l, "end"))).Where(t => t != null).Max() ?? start;
            parts.Add(new SongPart(name, start.Value, end.Value));
        }
        return parts;
    }

    /// <summary>How long the recording these were timed to is: &lt;body dur&gt;, when it says.</summary>
    public static long? Length(string xml)
    {
        var m = BodyDur().Match(xml);
        return m.Success ? Time(m.Groups[1].Value) : null;
    }

    /// <summary>
    /// A line timed only as a whole. Apple writes its ad-libs at the end in brackets, "… yeah (Four-by—)",
    /// and shows them as backing vocals: under the line, lighting while its last words still sweep.
    /// </summary>
    public static LyricLine LineTimed(long start, long end, string text, int voice, string? background, bool? rtl)
    {
        var split = TrailingAdlibs().Match(text);
        if (!split.Success || background != null || end <= start)
        {
            return new LyricLine
            {
                StartMs = start, EndMs = end, Text = text, Words = LrcParser.SpreadAcross(text, start, end), WordSynced = false,
                Voice = voice, Background = background, Rtl = rtl, ExactEnd = true,
            };
        }
        var main = split.Groups[1].Value.Trim();
        var adlibs = string.Join(" ", Bracketed().Matches(split.Groups[2].Value).Select(m => m.Groups[1].Value.Trim()).Where(s => s.Length > 0));
        var span = end - start;
        // The line's words at a sung pace, leaving the ad-lib room at the end ...
        var mainMs = Math.Min(LrcParser.SweepMs(main, span), span - Math.Min(AdlibMinMs, span / 2));
        var words = LrcParser.SpreadOver(main, start, start + mainMs);
        // ... and the ad-lib coming in as the line's last words go, at its own pace.
        var adlibStart = start + mainMs * 7 / 8;
        var adlibEnd = Math.Min(end, adlibStart + Math.Max(LrcParser.PacedMs(adlibs), AdlibMinMs));
        return new LyricLine
        {
            StartMs = start, EndMs = end, Text = main, Words = words, WordSynced = false, Voice = voice,
            Background = adlibs, BackgroundWords = LrcParser.SpreadOver(adlibs, adlibStart, adlibEnd), Rtl = rtl, ExactEnd = true,
        };
    }

    /// <summary>Backing words in order, the last keeping no trailing space.</summary>
    private static List<LyricWord> BackingWords(List<LyricWord> words)
    {
        if (words.Count == 0) return words;
        var sorted = words.OrderBy(w => w.StartMs).ToList();
        sorted[^1] = sorted[^1] with { Text = sorted[^1].Text.TrimEnd() };
        return sorted;
    }

    /// <summary>The singers declared in the head (&lt;ttm:agent type xml:id&gt;).</summary>
    private static Dictionary<string, int> VoicesOf(string xml)
    {
        var person = 0;
        var map = new Dictionary<string, int>();
        foreach (Match m in Agent().Matches(xml))
        {
            var id = Attr(m.Groups[1].Value, "xml:id");
            if (id == null) continue;
            map[id] = Attr(m.Groups[1].Value, "type") == "group" ? Voices.Together : person++ % 2 == 0 ? Voices.Lead : Voices.Second;
        }
        return map;
    }

    private static readonly HashSet<string> RtlLanguages = new() { "ar", "he", "iw", "fa", "ur", "yi", "ps", "sd", "ug", "dv", "ku", "ckb" };

    /// <summary>True for tts:direction="rtl" or a right-to-left xml:lang, false for ltr, null when neither says.</summary>
    public static bool? DirectionOf(string attrs)
    {
        switch (Attr(attrs, "tts:direction"))
        {
            case "rtl": return true;
            case "ltr": return false;
        }
        var lang = Attr(attrs, "xml:lang");
        if (lang == null) return null;
        return RtlLanguages.Contains(lang.Split('-')[0].ToLowerInvariant());
    }

    private static string? Attr(string attrs, string name)
    {
        var m = Regex.Match(attrs, @"\b" + Regex.Escape(name) + @"=""([^""]+)""");
        return m.Success ? m.Groups[1].Value : null;
    }

    /// <summary>"1:02.345", "00:01:02.345", "62.345s" or "62345ms".</summary>
    public static long? Time(string? t)
    {
        if (t == null) return null;
        var inv = CultureInfo.InvariantCulture;
        if (t.EndsWith("ms")) return double.TryParse(t[..^2], NumberStyles.Float, inv, out var ms) ? (long)ms : null;
        if (t.EndsWith("s")) return double.TryParse(t[..^1], NumberStyles.Float, inv, out var s) ? (long)(s * 1000) : null;
        var parts = t.Split(':');
        if (!double.TryParse(parts[^1], NumberStyles.Float, inv, out var sec)) return null;
        long min = parts.Length >= 2 && long.TryParse(parts[^2], out var mm) ? mm : 0;
        long hr = parts.Length >= 3 && long.TryParse(parts[^3], out var hh) ? hh : 0;
        return (hr * 3600 + min * 60) * 1000 + (long)(sec * 1000);
    }

    private static string Unescape(string s) =>
        s.Replace("&apos;", "'").Replace("&#39;", "'").Replace("&quot;", "\"").Replace("&lt;", "<").Replace("&gt;", ">").Replace("&amp;", "&");
}
