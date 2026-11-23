using System.Numerics;
using LocalTune.Core;
using LocalTune.Lyrics;
using Microsoft.UI;
using Microsoft.UI.Composition;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Shapes;
using Windows.UI;

namespace LocalTune.Views;

/// <summary>
/// The playing song's lyrics, lit as they're sung. Follows the phone's rules
/// (LyricsView.kt): a line still being sung stays lit while the next starts,
/// only word-timed lines sweep letter by letter, and the dots of an
/// instrumental break take no room until they're on.
/// </summary>
public sealed class LyricsView : Grid
{
    /// <summary>How long a line must go on past the next one's start to keep the view on it.</summary>
    private const long HoldMs = 1_000;
    /// <summary>How far ahead of its timestamp a line lights up.</summary>
    private const long LineLeadMs = 150;
    /// <summary>How long the glide to the next line takes, and so how early it sets off.</summary>
    private const int GlideMs = 650;
    /// <summary>Where the active line sits, down from the top, as a share of the height.</summary>
    /// <summary>Where the active line sits, down from the top, as a share of the height (the mini player: near the top, as Apple's).</summary>
    public double Anchor { get; set; } = 0.40;
    /// <summary>After the listener scrolls, how long before the view follows the song again.</summary>
    private static readonly TimeSpan ResumeFollow = TimeSpan.FromSeconds(3);

    private const float Dim = 0.3f;
    private const float Unsung = 0.35f;
    /// <summary>The soft edge of the sweep, in letters.</summary>
    private const float Edge = 2f;
    /// <summary>The instrumental-break dots: their size, and the room they take while showing.</summary>
    public double DotSize { get; set; } = 12;
    public double GapHeight { get; set; } = 52;
    /// <summary>Room above and below each line, as a share of the lyric size.</summary>
    public double LineSpace { get; set; } = 0.3;

    private readonly StackPanel stack = new();
    private readonly Compositor compositor;
    /// <summary>
    /// Where the lines are moved to. A render transform, animated by a storyboard: that still runs
    /// on the compositor, smooth at the screen's own rate, and clicks follow it. (Moving the lines
    /// with the compositor's own Translation instead left clicks landing on whichever line would
    /// have been there unmoved, so tapping a line played a different one.)
    /// </summary>
    private readonly TranslateTransform shift = new();
    private Storyboard? gliding;
    private readonly TextBlock message = new()
    {
        HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Center,
        FontSize = 20,
        FontWeight = FontWeights.SemiBold,
        Foreground = new SolidColorBrush(Color.FromArgb(150, 255, 255, 255)),
    };
    private readonly ScrollViewer plainScroll = new() { Visibility = Visibility.Collapsed, Padding = new Thickness(14, 40, 14, 40) };
    private readonly DispatcherQueueTimer pausedTick;
    private readonly DispatcherQueueTimer resumeTimer;

    private Track? track;
    private List<Row> rows = new();
    private List<int> lit = new(4);
    private List<int> spare = new(4);
    private int lead = -1;
    /// <summary>Lines above this row that aren't lit any more have been sung, and are hidden.</summary>
    private int pastEdge;
    private int lastCurrent = -1;
    /// <summary>How long a line stays (dimmed) after its highlight finishes, before it fades away.</summary>
    private static readonly TimeSpan HideDelay = TimeSpan.FromMilliseconds(300);
    /// <summary>Lines finished and waiting out <see cref="HideDelay"/> before they hide.</summary>
    private readonly List<int> hiding = new();
    private bool following = true;
    private double translation;
    private bool active;
    private readonly DispatcherQueueTimer frames;
    private int loadId;

    public LyricsView()
    {
        Background = new SolidColorBrush(Colors.Transparent);
        stack.VerticalAlignment = VerticalAlignment.Top;
        Children.Add(stack);
        Children.Add(plainScroll);
        Children.Add(message);
        stack.RenderTransform = shift;
        compositor = ElementCompositionPreview.GetElementVisual(stack).Compositor;

        // Sixty updates a second while playing. Not every screen refresh: on a 144 Hz laptop screen
        // that kept a core a third busy, and the sweep looks no smoother for it. The glide and the
        // fades are the compositor's own animations, so they still run at the screen's full rate.
        frames = DispatcherQueue.GetForCurrentThread().CreateTimer();
        frames.Interval = TimeSpan.FromMilliseconds(16);
        frames.Tick += (_, _) => Update();
        pausedTick = DispatcherQueue.GetForCurrentThread().CreateTimer();
        pausedTick.Interval = TimeSpan.FromMilliseconds(120);
        pausedTick.Tick += (_, _) => Update();
        resumeTimer = DispatcherQueue.GetForCurrentThread().CreateTimer();
        resumeTimer.Interval = ResumeFollow;
        resumeTimer.IsRepeating = false;
        resumeTimer.Tick += (_, _) => { Follow(true); ScrollToLead(animate: true); };

        SizeChanged += (_, e) =>
        {
            Clip = new RectangleGeometry { Rect = new Windows.Foundation.Rect(0, 0, ActualWidth, ActualHeight) };
            if (Math.Abs(e.NewSize.Width - e.PreviousSize.Width) > 0.5)
            {
                foreach (var r in rows) Size(r);
                SizeCredit();
            }
            // The new line heights are only known after layout.
            DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () => ScrollToLead(animate: false));
        };
        PointerWheelChanged += (_, e) =>
        {
            if (rows.Count == 0) return;
            var delta = e.GetCurrentPoint(this).Properties.MouseWheelDelta;
            Follow(false);
            resumeTimer.Stop();
            resumeTimer.Start();
            MoveTo(translation + delta * 0.9, GlideMs / 4);
            e.Handled = true;
        };
        Player.StateChanged += SyncClock;
        Player.Seeked += OnSeeked;
        Settings.Changed += OnSettings;
    }

    private double lastScale = Settings.Value.LyricsScale;

    private void OnSettings()
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (Math.Abs(lastScale - Settings.Value.LyricsScale) < 0.001) return;
            lastScale = Settings.Value.LyricsScale;
            var t = track;
            track = null;
            if (t != null) Show(t);
        });
    }

    /// <summary>On while the full player is shown: only then do the lyrics follow the song frame by frame.</summary>
    public bool Active
    {
        get => active;
        set
        {
            active = value;
            if (value)
            {
                Update();
                ScrollToLead(animate: false);
            }
            SyncClock();
        }
    }

    /// <summary>Frame by frame while shown and playing; a few times a second while paused, to follow seeks.</summary>
    private void SyncClock()
    {
        var wantFrames = active && Player.IsPlaying && rows.Count > 0;
        if (wantFrames && !frames.IsRunning) frames.Start();
        if (!wantFrames && frames.IsRunning) frames.Stop();
        if (active && !Player.IsPlaying && rows.Count > 0) pausedTick.Start(); else pausedTick.Stop();
    }


    // ---- Loading a song's lyrics ----

    public async void Show(Track t)
    {
        if (track == t) return;
        track = t;
        var id = ++loadId;
        Clear();
        var doc = await LyricFiles.FindAsync(t);
        if (id != loadId) return;
        var writtenBy = LyricFiles.Writers(t);
        switch (doc)
        {
            case SyncedLyrics s:
                Build(s.Lines, writtenBy);
                break;
            case PlainLyrics p:
                var plain = new StackPanel();
                plain.Children.Add(new TextBlock
                {
                    Text = p.Text,
                    TextWrapping = TextWrapping.Wrap,
                    FontSize = 24 * Settings.Value.LyricsScale,
                    LineHeight = 34 * Settings.Value.LyricsScale,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = new SolidColorBrush(Color.FromArgb(220, 255, 255, 255)),
                });
                if (Credit(writtenBy) is { } plainCredit)
                {
                    plainCredit.FontSize = 15 * Settings.Value.LyricsScale;
                    plainCredit.Margin = new Thickness(0, 28, 0, 0);
                    plain.Children.Add(plainCredit);
                }
                plainScroll.Content = plain;
                plainScroll.Visibility = Visibility.Visible;
                break;
            default:
                message.Text = "No lyrics available";
                message.Visibility = Visibility.Visible;
                break;
        }
        SyncClock();
    }

    private void Clear()
    {
        rows = new();
        lit.Clear();
        lead = -1;
        pastEdge = 0;
        lastCurrent = -1;
        hiding.Clear();
        following = true;
        stack.Children.Clear();
        credit = null;
        plainScroll.Content = null;
        plainScroll.Visibility = Visibility.Collapsed;
        message.Visibility = Visibility.Collapsed;
        translation = 0;
        gliding?.Stop();
        shift.Y = 0;
        SyncClock();
    }

    // ---- Rows: the lines, with a row for each instrumental break ----

    private sealed class Row
    {
        public required long StartMs;
        public LyricLine? Line;
        public LyricGap? Gap;
        public required FrameworkElement Element;
        public Sweep? Main;
        public Sweep? Backing;
        public Ellipse[]? Dots;
        public TextBlock? MainText;
        public TextBlock? BackText;
        public bool GapOpen;
        public bool IsLit;
        /// <summary>Sung and gone: hidden until the listener scrolls back to look.</summary>
        public bool Hidden;
        /// <summary>When the line is due to hide; null when it isn't waiting to.</summary>
        public DateTime? HideAt;
        /// <summary>When the line's singing ends (its own words or its backing vocals); 0 for a line without word times.</summary>
        public long Sung;
    }

    /// <summary>A line's letters as runs of their own, each with a brush the sweep sets.</summary>
    private sealed class Sweep
    {
        public required (LyricWord Word, int K, int N, SolidColorBrush Brush)[] Letters;
        public required byte[] Shown;
    }

    /// <summary>"Written by: …" after the last line, small and dim, as Apple Music shows it.</summary>
    private TextBlock? credit;

    private static TextBlock? Credit(IReadOnlyList<string> names) => names.Count == 0 ? null : new TextBlock
    {
        Text = "Written by: " + string.Join(", ", names),
        TextWrapping = TextWrapping.Wrap,
        FontFamily = LyricFont,
        FontWeight = FontWeights.SemiBold,
        Foreground = new SolidColorBrush(Color.FromArgb(130, 255, 255, 255)),
        IsHitTestVisible = false,
    };

    private void SizeCredit()
    {
        if (credit == null) return;
        var size = LyricSize;
        credit.FontSize = Math.Max(14, size * 0.36);
        credit.Margin = new Thickness(14, size * 0.9, 14, 0);
    }

    private void Build(IReadOnlyList<LyricLine> lines, IReadOnlyList<string> writtenBy)
    {
        var scale = Settings.Value.LyricsScale;
        var gaps = lines.InstrumentalGaps().ToDictionary(g => g.AfterIndex);
        // A long intro before the first line is a break too.
        if (lines.Count > 0 && lines[0].StartMs >= 5_000) rows.Add(GapRow(new LyricGap(-1, 0, lines[0].StartMs)));
        for (var i = 0; i < lines.Count; i++)
        {
            rows.Add(LineRow(lines[i], scale));
            if (gaps.TryGetValue(i, out var g)) rows.Add(GapRow(g));
        }
        foreach (var r in rows) stack.Children.Add(r.Element);
        credit = Credit(writtenBy);
        if (credit != null)
        {
            SizeCredit();
            stack.Children.Add(credit);
        }
        // Room below the last line, so it can come up to the active place too.
        stack.Children.Add(new Border { Height = 2000 });
        // The lines' heights are needed to know where to scroll, so they're measured now rather
        // than at the next layout pass: otherwise opening the player partway through a song,
        // paused, left the lyrics at the top with nothing lit.
        stack.UpdateLayout();
        lead = -1;
        Update();
        HidePast(lastCurrent, all: true, now: true);
        ScrollToLead(animate: false);
    }

    /// <summary>The lyric size: set by the column's width, as Apple's is, so a long line wraps onto two.</summary>
    private double LyricSize => Math.Clamp((ActualWidth > 0 ? ActualWidth : 900) * 0.056, MinLyricSize, 64) * Settings.Value.LyricsScale;

    /// <summary>The smallest the lyrics go: smaller in the mini player, whose column is narrow.</summary>
    public double MinLyricSize { get; set; } = 26;

    private static readonly FontFamily LyricFont = new("Segoe UI Variable Display");

    private void Size(Row r)
    {
        var size = LyricSize;
        if (r.MainText is { } m)
        {
            m.FontSize = size;
            m.LineHeight = size * 1.2;
        }
        if (r.BackText is { } b)
        {
            b.FontSize = size * 0.6;
            b.LineHeight = size * 0.6 * 1.25;
            b.Margin = new Thickness(0, size * 0.25, 0, 0);
        }
        if (r.Line != null) ((StackPanel)r.Element).Padding = new Thickness(14, size * LineSpace, 14, size * LineSpace);
    }

    private Row LineRow(LyricLine line, double scale)
    {
        var endSide = line.Voice == Voices.Second; // a duet's second voice is set on the right, as Apple does
        var flow = line.ReadsRightToLeft ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        var align = endSide ? TextAlignment.Right : TextAlignment.Left;
        var main = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            LineStackingStrategy = LineStackingStrategy.BlockLineHeight,
            FontFamily = LyricFont,
            FontWeight = FontWeights.Bold,
            TextAlignment = align,
            FlowDirection = flow,
            Foreground = new SolidColorBrush(Colors.White),
        };
        var panel = new StackPanel
        {
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(Colors.Transparent),
            Children = { main },
            Opacity = Dim,
            OpacityTransition = FadeIn,
            TranslationTransition = new Vector3Transition { Duration = TimeSpan.FromMilliseconds(750) },
            ScaleTransition = new Vector3Transition { Duration = TimeSpan.FromMilliseconds(750) },
        };
        panel.SizeChanged += (_, _) => panel.CenterPoint = new Vector3(endSide ? (float)panel.ActualWidth : 0, (float)panel.ActualHeight / 2, 0);
        var row = new Row { StartMs = line.StartMs, Line = line, Element = panel, MainText = main, Sung = SungTo(line) };
        // Only word-timed lines sweep; a line-timed one lights all at once.
        if (line.WordSynced) row.Main = Letters(main, line.Words);
        else main.Text = line.Text;
        if (line.Background is { } bg)
        {
            var back = new TextBlock
            {
                TextWrapping = TextWrapping.Wrap,
                LineStackingStrategy = LineStackingStrategy.BlockLineHeight,
                FontFamily = LyricFont,
                FontWeight = FontWeights.SemiBold,
                TextAlignment = align,
                FlowDirection = flow,
                Foreground = new SolidColorBrush(Color.FromArgb(217, 255, 255, 255)),
            };
            // Backing vocals are shown without their brackets, as Apple shows them.
            if (line.BackgroundWords.Count > 0) row.Backing = Letters(back, Unbracketed(line.BackgroundWords));
            else back.Text = bg.Trim().TrimStart('(').TrimEnd(')').Trim();
            panel.Children.Add(back);
            row.BackText = back;
        }
        Size(row);
        panel.PointerEntered += (_, _) => panel.Background = new SolidColorBrush(Color.FromArgb(14, 255, 255, 255));
        panel.PointerExited += (_, _) => panel.Background = new SolidColorBrush(Colors.Transparent);
        panel.Tapped += (_, _) =>
        {
            Player.SeekTo(line.StartMs);
        };
        return row;
    }

    /// <summary>The words with the bracket before the first and after the last taken off.</summary>
    private static List<LyricWord> Unbracketed(IReadOnlyList<LyricWord> words)
    {
        var list = words.ToList();
        list[0] = list[0] with { Text = list[0].Text.TrimStart().TrimStart('(') };
        list[^1] = list[^1] with { Text = list[^1].Text.TrimEnd().TrimEnd(')') };
        return list.Where(w => w.Text.Length > 0).ToList();
    }

    private static Sweep Letters(TextBlock block, IReadOnlyList<LyricWord> words)
    {
        var letters = new List<(LyricWord, int, int, SolidColorBrush)>();
        foreach (var w in words)
        {
            for (var k = 0; k < w.Text.Length; k++)
            {
                var brush = new SolidColorBrush(Colors.White);
                block.Inlines.Add(new Run { Text = w.Text[k].ToString(), Foreground = brush });
                letters.Add((w, k, w.Text.Length, brush));
            }
        }
        var shown = new byte[letters.Count];
        Array.Fill(shown, (byte)255);
        return new Sweep { Letters = letters.ToArray(), Shown = shown };
    }

    private Row GapRow(LyricGap gap)
    {
        var dots = Enumerable.Range(0, 3).Select(_ => new Ellipse
        {
            Width = DotSize,
            Height = DotSize,
            Fill = new SolidColorBrush(Color.FromArgb(77, 255, 255, 255)),
        }).ToArray();
        var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = DotSize * 0.85, Margin = new Thickness(16, (GapHeight - DotSize) / 2, 0, (GapHeight - DotSize) / 2), VerticalAlignment = VerticalAlignment.Top };
        foreach (var d in dots) line.Children.Add(d);
        var holder = new Grid { Height = 0, Opacity = 0, Children = { line }, OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(420) } };
        // The dots breathe while they're on.
        var v = ElementCompositionPreview.GetElementVisual(line);
        line.SizeChanged += (_, _) => v.CenterPoint = new Vector3(0, (float)line.ActualHeight / 2, 0);
        var breathe = compositor.CreateVector3KeyFrameAnimation();
        breathe.InsertKeyFrame(0f, new Vector3(0.88f, 0.88f, 1));
        breathe.InsertKeyFrame(0.5f, new Vector3(1.08f, 1.08f, 1));
        breathe.InsertKeyFrame(1f, new Vector3(0.88f, 0.88f, 1));
        breathe.Duration = TimeSpan.FromMilliseconds(3000);
        breathe.IterationBehavior = AnimationIterationBehavior.Forever;
        v.StartAnimation("Scale", breathe);
        return new Row { StartMs = gap.StartMs, Gap = gap, Element = holder, Dots = dots };
    }

    // ---- Following the song ----

    private static long SungTo(LyricLine line) => Math.Max(
        line.BackgroundWords.Count > 0 ? line.BackgroundWords.Max(w => w.EndMs) : 0,
        line.WordSynced && line.Words.Count > 0 ? line.Words.Max(w => w.EndMs) : 0);

    /// <summary>True when the row, a line, is still being sung more than HoldMs after a later line starts at laterStart.</summary>
    private bool HoldsOver(int r, long laterStart) =>
        r >= 0 && r < rows.Count && rows[r].Line != null && rows[r].Sung - laterStart > HoldMs;

    private void Update()
    {
        if (rows.Count == 0) return;
        var p = Player.PositionMs;

        // The row playing now.
        int lo = 0, hi = rows.Count - 1, current = -1;
        while (lo <= hi)
        {
            var mid = (lo + hi) >> 1;
            if (rows[mid].StartMs <= p + LineLeadMs) { current = mid; lo = mid + 1; }
            else hi = mid - 1;
        }

        // Lit: the current line, and an earlier one still being sung under it.
        var nowLit = spare;
        nowLit.Clear();
        if (current >= 0)
        {
            var from = Math.Max(0, current - 2);
            for (var r = from; r <= current; r++)
            {
                var row = rows[r];
                if (row.Line == null) { if (r == current) nowLit.Add(r); continue; }
                var singing = r == current ? !row.Line.WordSynced || row.Sung > p : row.Sung > p;
                if (singing) nowLit.Add(r);
            }
            if (nowLit.Count == 0)
            {
                // Nothing sung right now: the line whose singing went on longest stays lit.
                int best = current;
                long bestSung = long.MinValue;
                for (var r = from; r <= current; r++)
                    if (rows[r].Line != null && rows[r].Sung > bestSung) { best = r; bestSung = rows[r].Sung; }
                nowLit.Add(best);
            }
        }

        foreach (var r in lit) if (!nowLit.Contains(r)) Paint(r, false, p, current);
        foreach (var r in nowLit) Paint(r, true, p, current);
        spare = lit;
        lit = nowLit;
        lastCurrent = current;
        HidePast(current);

        // The row the view keeps in place: the earliest lit line that goes on well past the latest one's start.
        var focus = current;
        if (current >= 0)
        {
            var start = rows[current].StartMs;
            foreach (var r in nowLit) // in order, earliest first
                if (r == current || HoldsOver(r, start)) { focus = r; break; }
        }
        // Where the list is heading: the next row, from a moment before it's due, so it arrives as it's sung.
        int heading;
        if (focus < 0) heading = 0;
        else if (focus < current) heading = focus;
        else if (focus + 1 >= rows.Count) heading = focus;
        else if (HoldsOver(focus, rows[focus + 1].StartMs)) heading = focus;
        else if (p + LineLeadMs + GlideMs >= rows[focus + 1].StartMs) heading = focus + 1;
        else heading = focus;

        if (heading != lead)
        {
            lead = heading;
            if (following) ScrollToLead(animate: true);
        }
    }

    /// <summary>
    /// Every line before the one being sung that isn't lit any more is hidden: once a line's
    /// highlight is done, it's gone. While the listener scrolls to look around, they all show
    /// again (dimmed), so an earlier line can still be read or clicked.
    /// </summary>
    private void HidePast(int current, bool all = false, bool now = false)
    {
        var edge = following ? Math.Max(0, current) : 0;
        var from = all ? 0 : Math.Min(edge, pastEdge);
        var to = all ? rows.Count : Math.Min(rows.Count, Math.Max(edge, pastEdge));
        pastEdge = edge;
        for (var r = from; r < to; r++)
        {
            var row = rows[r];
            if (row.Line == null) continue;
            if (r < edge && !row.IsLit)
            {
                if (now) Hide(row);
                else HideSoon(r);
            }
            else if (row.Hidden || row.HideAt != null) Show(row, row.IsLit);
        }
        // Lines whose moment has passed go now.
        if (hiding.Count == 0) return;
        var t = DateTime.UtcNow;
        for (var i = hiding.Count - 1; i >= 0; i--)
        {
            var r = hiding[i];
            var row = rows[r];
            if (row.HideAt is not { } due) { hiding.RemoveAt(i); continue; }
            if (due > t) continue;
            hiding.RemoveAt(i);
            row.HideAt = null;
            if (following && !row.IsLit && r < pastEdge) Hide(row);
        }
    }

    /// <summary>The line hides <see cref="HideDelay"/> from now, unless it's lit again or shown first.</summary>
    private void HideSoon(int r)
    {
        var row = rows[r];
        if (row.Hidden || row.HideAt != null) return;
        row.HideAt = DateTime.UtcNow + HideDelay;
        hiding.Add(r);
    }

    private static readonly ScalarTransition FadeOut = new() { Duration = TimeSpan.FromMilliseconds(750) };
    private static readonly ScalarTransition FadeIn = new() { Duration = TimeSpan.FromMilliseconds(380) };
    private static readonly Vector3 GoneDrift = new(0, -14, 0);
    private static readonly Vector3 GoneScale = new(0.96f, 0.96f, 1);

    /// <summary>A finished line leaves: fading over three quarters of a second while it lifts a little and shrinks.</summary>
    private static void Hide(Row row)
    {
        row.HideAt = null;
        if (row.Hidden) return;
        row.Hidden = true;
        row.Element.OpacityTransition = FadeOut;
        row.Element.Opacity = 0;
        row.Element.Translation = GoneDrift;
        row.Element.Scale = GoneScale;
        row.Element.IsHitTestVisible = false;
    }

    private static void Show(Row row, bool lit, bool instant = false)
    {
        row.HideAt = null;
        row.Element.OpacityTransition = instant ? null : FadeIn;
        row.Hidden = false;
        row.Element.Opacity = lit ? 1 : Dim;
        row.Element.Translation = Vector3.Zero;
        row.Element.Scale = Vector3.One;
        row.Element.IsHitTestVisible = true;
    }

    /// <summary>
    /// After a jump (a tapped line, the progress bar, the keys), the view goes straight to the new
    /// place: following the song again even if the listener had scrolled, the lines jumped past
    /// gone without waiting out the usual moment, and the right lines lit at once.
    /// </summary>
    private void OnSeeked()
    {
        if (rows.Count == 0) return;
        resumeTimer.Stop();
        following = true;
        lead = -1;
        Update();
        HidePast(lastCurrent, all: true, now: true);
    }

    private void Follow(bool on)
    {
        if (following == on) return;
        following = on;
        HidePast(lastCurrent, all: true, now: true);
    }

    private void Paint(int r, bool on, long p, int current)
    {
        var row = rows[r];
        if (row.Gap is { } gap)
        {
            var open = r == current && p < gap.EndMs;
            if (open != row.GapOpen)
            {
                row.GapOpen = open;
                AnimateHeight(row.Element, open ? GapHeight : 0);
                row.Element.Opacity = open ? 1 : 0;
            }
            if (open)
            {
                var progress = Math.Clamp((double)(p - gap.StartMs) / Math.Max(1, gap.EndMs - gap.StartMs), 0, 1);
                for (var i = 0; i < 3; i++)
                {
                    var a = Math.Clamp(progress * 3 - i, 0, 1);
                    ((SolidColorBrush)row.Dots![i].Fill).Color = Color.FromArgb((byte)(77 + 178 * a), 255, 255, 255);
                }
            }
            return;
        }
        if (on != row.IsLit)
        {
            row.IsLit = on;
            // A line that's just finished, above the one being sung, goes away a moment later.
            // A word-timed line turns bright at once: its letters start out dim ("not sung yet")
            // the moment it's lit, so fading the line up as well darkened it for a moment first,
            // a blink before every line. Lit, its unsung letters (35%) look as it did dimmed (30%).
            if (on) Show(row, lit: true, instant: row.Main != null && !row.Hidden);
            else
            {
                row.Element.OpacityTransition = FadeIn;
                row.Element.Opacity = row.Hidden ? 0 : Dim;
                if (following && r < pastEdge) HideSoon(r);
            }
        }
        if (row.Main != null) Sweeping(row.Main, on, p);
        if (row.Backing != null) Sweeping(row.Backing, on, p);
    }

    /// <summary>
    /// Letters wholly sung are white, those not yet reached dim, with one soft edge where the
    /// singing is: the edge belongs to the line, not to each word, so a word not yet sung stays
    /// wholly dim.
    /// </summary>
    private static void Sweeping(Sweep s, bool on, long p)
    {
        // How far through the line the singing has got, in letters.
        double front = 0;
        if (on)
        {
            for (var i = 0; i < s.Letters.Length; i++)
            {
                var (w, k, n, _) = s.Letters[i];
                if (p <= w.StartMs) continue;
                var done = Math.Clamp((double)(p - w.StartMs) / Math.Max(1, w.EndMs - w.StartMs), 0, 1) * n;
                front = Math.Max(front, i - k + done);
            }
        }
        for (var i = 0; i < s.Letters.Length; i++)
        {
            var brush = s.Letters[i].Brush;
            byte alpha = 255;
            if (on)
            {
                // A letter is half lit as the singing passes its middle.
                var litness = front <= 0 ? 0 : Math.Clamp((front - (i + 0.5)) / Edge + 0.5, 0, 1);
                alpha = (byte)(255 * (Unsung + (1 - Unsung) * (float)(Math.Round(litness * 20) / 20)));
            }
            if (s.Shown[i] == alpha) continue;
            s.Shown[i] = alpha;
            brush.Color = Color.FromArgb(alpha, 255, 255, 255);
        }
    }

    private static void AnimateHeight(FrameworkElement e, double to)
    {
        var anim = new DoubleAnimation
        {
            To = to,
            Duration = TimeSpan.FromMilliseconds(450),
            EnableDependentAnimation = true,
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        };
        Storyboard.SetTarget(anim, e);
        Storyboard.SetTargetProperty(anim, "Height");
        var sb = new Storyboard { Children = { anim } };
        sb.Begin();
    }

    /// <summary>The row's top as it will be once any break that's opening or closing has finished.</summary>
    private double TopOf(int r)
    {
        double y = 0;
        for (var i = 0; i < r && i < rows.Count; i++)
            y += rows[i].Gap != null ? (rows[i].GapOpen ? GapHeight : 0) : rows[i].Element.ActualHeight;
        return y;
    }

    private void ScrollToLead(bool animate)
    {
        if (rows.Count == 0 || !following) return;
        var target = ActualHeight * Anchor - TopOf(Math.Max(0, lead));
        if (animate) MoveTo(target, GlideMs);
        else MoveTo(target, 0);
    }

    private void MoveTo(double y, int ms)
    {
        var total = TopOf(rows.Count);
        y = Math.Clamp(y, ActualHeight * Anchor - total, ActualHeight * Anchor);
        translation = y;
        if (ms <= 0)
        {
            gliding?.Stop();
            shift.Y = y;
            return;
        }
        // From wherever the lines are now (mid-glide too) to the new place, on the same ease-out
        // curve as before: (0.25, 0.1) (0.25, 1).
        // A glide started while another is under way carries on from where the lines are,
        // rather than stopping the old one first, which would snap them back.
        var anim = new DoubleAnimationUsingKeyFrames { EnableDependentAnimation = false };
        anim.KeyFrames.Add(new SplineDoubleKeyFrame
        {
            KeyTime = TimeSpan.FromMilliseconds(ms),
            Value = y,
            KeySpline = new KeySpline { ControlPoint1 = new Windows.Foundation.Point(0.25, 0.1), ControlPoint2 = new Windows.Foundation.Point(0.25, 1) },
        });
        Storyboard.SetTarget(anim, shift);
        Storyboard.SetTargetProperty(anim, "Y");
        var sb = new Storyboard { Children = { anim } };
        sb.Completed += (_, _) => { if (gliding == sb) { gliding = null; shift.Y = y; } };
        gliding = sb;
        sb.Begin();
    }
}
