using System.Runtime.InteropServices.WindowsRuntime;
using LocalTune.Core;
using Microsoft.UI;
using Microsoft.UI.Composition;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace LocalTune.Views;

/// <summary>
/// Apple Music's mini player, measured from the user's screenshot of it: a 350 × 152 window that
/// stays above every other, see-through and tinted with the playing song's cover colour. The
/// cover (60 px) at the left, the song centred across the window, the progress line, then the heart
/// and Add to playlist, back / play / next, and lyrics and queue. Lyrics or the queue make it 632 tall with
/// the controls kept at the bottom. Drag it by its top part.
/// </summary>
public sealed class MiniPlayer : Window
{
    private const double WidthDip = 350, CompactDip = 152, TallDip = 632;

    private static readonly SolidColorBrush White = new(Colors.White);
    private static readonly SolidColorBrush Soft = new(Color.FromArgb(178, 255, 255, 255));
    private static readonly SolidColorBrush IconSoft = new(Color.FromArgb(215, 255, 255, 255));

    private readonly double scale;
    private readonly OverlappedPresenter presenter = OverlappedPresenter.Create();
    private readonly CoverTint backdrop = new();
    private readonly CoverView cover = new() { Size = 60, DecodeSize = 120, CornerRadius = new CornerRadius(4) };
    private readonly TextBlock title = new() { FontSize = 14, FontWeight = FontWeights.SemiBold, Foreground = White, TextTrimming = TextTrimming.CharacterEllipsis, HorizontalAlignment = HorizontalAlignment.Center };
    private readonly TextBlock subtitle = new() { FontSize = 14, Foreground = Soft, TextTrimming = TextTrimming.CharacterEllipsis, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 1, 0, 0) };
    private readonly StackPanel badge = new() { Orientation = Orientation.Horizontal, Spacing = 5, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 0) };
    private readonly TextBlock badgeText = new() { FontSize = 11, FontWeight = FontWeights.SemiBold, Foreground = Soft, VerticalAlignment = VerticalAlignment.Center };
    private readonly Microsoft.UI.Xaml.Shapes.Path badgeLogo = NowPlayingView.LosslessLogo(Soft);
    private readonly LineSeekBar seek = new(small: true);
    private readonly FontIcon playIcon = new() { Glyph = "", FontSize = 20, Foreground = White };
    private readonly LyricsView lyrics = new();
    private readonly QueuePanel queue = new();
    private readonly Grid middle = new();
    private readonly Button play, lyricsButton, queueButton;
    private readonly DispatcherQueueTimer tick;
    private enum Extra { None, Lyrics, Queue }
    private Extra extra = Extra.None;
    private int tintId;

    public MiniPlayer(double scale)
    {
        this.scale = scale;
        Title = "LocalTune";
        SystemBackdrop = backdrop;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        ExtendsContentIntoTitleBar = true;

        // Above every other window; not resizable; no title bar (its own small buttons instead).
        presenter.IsAlwaysOnTop = true;
        presenter.IsResizable = false;
        presenter.IsMaximizable = false;
        presenter.SetBorderAndTitleBar(true, false);
        AppWindow.SetPresenter(presenter);

        var root = new Grid { RequestedTheme = ElementTheme.Dark, Padding = new Thickness(17, 15, 17, 17) };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Content = root;

        // ---- The song: the cover at the left, the words centred across the whole window ----
        var info = new Grid { Height = 61 };
        cover.HorizontalAlignment = HorizontalAlignment.Left;
        cover.VerticalAlignment = VerticalAlignment.Top;
        info.Children.Add(cover);
        badge.Children.Add(badgeLogo);
        badge.Children.Add(badgeText);
        info.Children.Add(new StackPanel { Margin = new Thickness(70, -2, 70, 0), Children = { title, subtitle, badge } });
        root.Children.Add(info);

        // The window is dragged by its top part (not the buttons at its right).
        var drag = new Grid { Background = new SolidColorBrush(Colors.Transparent), Margin = new Thickness(-17, -15, 100, 0) };
        root.Children.Add(drag);
        SetTitleBar(drag);

        // Minimise, back to the full window, close: small and soft, at the top right, as Apple's.
        var chrome = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, -16, -10, 0) };
        chrome.Children.Add(Small("", "Minimise", () => presenter.Minimize(), 10, 24, Soft));
        chrome.Children.Add(Small("", "Back to LocalTune", () => MainWindow.Instance.LeaveMini(), 11, 24, Soft));
        chrome.Children.Add(Small("", "Close LocalTune", () => MainWindow.Instance.QuitFromMini(), 10, 24, Soft));
        root.Children.Add(chrome);

        // ---- Lyrics or the queue, when asked for ----
        lyrics.Margin = new Thickness(-17, 10, -17, 6);
        // As Apple's mini player: the active line just under the song, small dots, roomy lines.
        lyrics.MinLyricSize = 20;
        lyrics.Anchor = 0.05;
        lyrics.DotSize = 7;
        lyrics.GapHeight = 34;
        lyrics.LineSpace = 0.75;
        queue.Width = double.NaN;
        queue.Background = new SolidColorBrush(Colors.Transparent);
        queue.BorderThickness = new Thickness(0);
        queue.Margin = new Thickness(-17, 8, -17, 6);
        queue.CloseRequested += () => SetExtra(Extra.None);
        Grid.SetRow(middle, 1);
        root.Children.Add(middle);

        // ---- Progress and controls ----
        seek.Margin = new Thickness(0, 7, 0, 0);
        Grid.SetRow(seek, 2);
        root.Children.Add(seek);

        var controls = new Grid { Height = 30 };
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        // The heart (like the song) and Add to playlist, where Apple has volume and "…" (the user's choice).
        var like = new LikeButton(IconSoft, 30);
        Button add = null!;
        add = Small("\uECC8", "Add to playlist", () => { if (Player.Current is { } t) PlaylistMenu.Show(add, new[] { t }); }, 15, 30, IconSoft);
        controls.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 13, Margin = new Thickness(7, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, Children = { like, add } });

        play = Small("", "Play", Player.Toggle, 20, 34);
        play.Content = playIcon;
        var transport = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 9,
            VerticalAlignment = VerticalAlignment.Center,
            Children = { Small("", "Previous", Player.Previous, 17, 30), play, Small("", "Next", Player.Next, 17, 30) },
        };
        Grid.SetColumn(transport, 1);
        controls.Children.Add(transport);

        lyricsButton = Small("", "Lyrics", () => SetExtra(extra == Extra.Lyrics ? Extra.None : Extra.Lyrics), 15, 30, IconSoft);
        queueButton = Small("", "Playing next", () => SetExtra(extra == Extra.Queue ? Extra.None : Extra.Queue), 15, 30, IconSoft);
        var right = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 17, Margin = new Thickness(0, 0, 3, 0), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, Children = { lyricsButton, queueButton } };
        Grid.SetColumn(right, 2);
        controls.Children.Add(right);
        Grid.SetRow(controls, 3);
        root.Children.Add(controls);

        // ---- Size and place: the bottom right of the screen ----
        Place(CompactDip, keepBottom: false);

        tick = DispatcherQueue.GetForCurrentThread().CreateTimer();
        tick.Interval = TimeSpan.FromMilliseconds(250);
        tick.Tick += (_, _) => seek.Tick();
        tick.Start();
        Player.TrackChanged += OnTrack;
        Player.StateChanged += OnState;
        Closed += (_, _) =>
        {
            // Opens there again next time.
            var at = AppWindow.Position;
            var bottom = at.Y + AppWindow.Size.Height;
            Settings.Update(s => { s.MiniLeft = at.X; s.MiniBottom = bottom; });
            tick.Stop();
            lyrics.Active = false;
            Player.TrackChanged -= OnTrack;
            Player.StateChanged -= OnState;
        };
        OnTrack();
        OnState();
    }

    private void OnTrack()
    {
        var t = Player.Current;
        cover.Track = t;
        title.Text = t?.Title ?? "Not playing";
        subtitle.Text = t == null ? "" : string.IsNullOrWhiteSpace(t.Album) ? t.Artist : $"{t.Artist} — {t.Album}";
        var tier = t?.QualityTier;
        badge.Visibility = tier == null ? Visibility.Collapsed : Visibility.Visible;
        if (tier != null)
        {
            badgeText.Text = tier;
            badgeLogo.Visibility = tier == "Dolby Atmos" ? Visibility.Collapsed : Visibility.Visible;
            ToolTipService.SetToolTip(badge, tier + Environment.NewLine + t!.QualityDetail);
        }
        if (t != null) lyrics.Show(t);
        seek.Tick();
        Tint(t);
    }

    /// <summary>The see-through background takes the cover's colour, as Apple's does.</summary>
    private async void Tint(Track? t)
    {
        var id = ++tintId;
        // Light and greyish, as Apple's (rgb 150,140,145 on a pink cover), white words still clear.
        var color = t == null ? null : await Task.Run(() => Core.CoverColour.Of(t)) is { } c ? Core.CoverColour.Shade(c, 0.55, 0.32, 0.44, 0.56) : (Color?)null;
        if (id != tintId) return;
        backdrop.SetTint(color ?? Color.FromArgb(255, 70, 70, 74));
    }

    private void OnState()
    {
        playIcon.Glyph = Player.IsPlaying ? "" : "";
        var name = Player.IsPlaying ? "Pause" : "Play";
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(play, name);
        ToolTipService.SetToolTip(play, name);
    }

    /// <summary>Lyrics or the queue between the song and the controls: the window grows taller for them.</summary>
    private void SetExtra(Extra next)
    {
        extra = next;
        middle.Children.Clear();
        lyrics.Active = false;
        if (next == Extra.Lyrics)
        {
            middle.Children.Add(lyrics);
            lyrics.Active = true;
        }
        else if (next == Extra.Queue)
        {
            middle.Children.Add(queue);
            queue.Visibility = Visibility.Visible;
            queue.Refresh();
        }
        lyricsButton.Background = new SolidColorBrush(next == Extra.Lyrics ? Color.FromArgb(46, 255, 255, 255) : Colors.Transparent);
        queueButton.Background = new SolidColorBrush(next == Extra.Queue ? Color.FromArgb(46, 255, 255, 255) : Colors.Transparent);
        Place(next == Extra.None ? CompactDip : TallDip, keepBottom: true);
    }

    /// <summary>Sizes the window; growing taller, its bottom edge stays where it is (it starts at the screen's bottom right).</summary>
    private void Place(double heightDip, bool keepBottom)
    {
        var w = (int)Math.Round(WidthDip * scale);
        var h = (int)Math.Round(heightDip * scale);
        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest).WorkArea;
        int x, y;
        if (keepBottom && AppWindow.IsVisible)
        {
            var now = AppWindow.Position;
            var bottom = now.Y + AppWindow.Size.Height;
            x = now.X;
            y = Math.Max(area.Y, bottom - h);
        }
        else if (Settings.Value.MiniLeft is { } left && Settings.Value.MiniBottom is { } savedBottom
                 && left >= area.X - w / 2 && left + w / 2 <= area.X + area.Width && savedBottom - h >= area.Y && savedBottom <= area.Y + area.Height)
        {
            // Where it was left last time, while that's still on this screen.
            x = left;
            y = savedBottom - h;
        }
        else
        {
            // The bottom right corner, clear of the taskbar even when it hides itself (then the
            // work area is the whole screen, and the taskbar comes up over the window).
            var screen = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest).OuterBounds;
            var taskbarHides = area.Height >= screen.Height;
            var margin = (int)(24 * scale);
            x = area.X + area.Width - w - margin;
            y = area.Y + area.Height - h - margin - (taskbarHides ? (int)(48 * scale) : 0);
        }
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(x, y, w, h));
    }

    private static Button Small(string glyph, string name, Action? onClick, double size = 16, double box = 36, Brush? color = null)
    {
        var b = new Button
        {
            Content = new FontIcon { Glyph = glyph, FontSize = size, Foreground = color ?? White },
            Style = (Style)Application.Current.Resources["IconButton"],
            Width = box,
            Height = box,
            Padding = new Thickness(0),
        };
        ToolTipService.SetToolTip(b, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, name);
        if (onClick != null) b.Click += (_, _) => onClick();
        return b;
    }

    /// <summary>
    /// See-through acrylic tinted with a colour that can change (the cover's). It stays
    /// see-through when another window has the focus, as Apple's mini player does, instead of
    /// turning solid.
    /// </summary>
    private sealed class CoverTint : SystemBackdrop
    {
        private DesktopAcrylicController? controller;
        private SystemBackdropConfiguration? config;
        private Color tint = Color.FromArgb(255, 70, 70, 74);

        protected override void OnTargetConnected(ICompositionSupportsSystemBackdrop target, XamlRoot root)
        {
            base.OnTargetConnected(target, root);
            config = new SystemBackdropConfiguration { IsInputActive = true, Theme = SystemBackdropTheme.Dark };
            controller = new DesktopAcrylicController();
            Apply();
            controller.AddSystemBackdropTarget(target);
            controller.SetSystemBackdropConfiguration(config);
        }

        protected override void OnTargetDisconnected(ICompositionSupportsSystemBackdrop target)
        {
            base.OnTargetDisconnected(target);
            controller?.RemoveSystemBackdropTarget(target);
            controller?.Dispose();
            controller = null;
        }

        public void SetTint(Color color)
        {
            tint = color;
            Apply();
        }

        private void Apply()
        {
            if (controller == null) return;
            controller.TintColor = tint;
            controller.TintOpacity = 0.55f;
            controller.LuminosityOpacity = 0.72f;
            controller.FallbackColor = tint;
        }
    }
}
