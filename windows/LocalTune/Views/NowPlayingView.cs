using LocalTune.Core;
using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace LocalTune.Views;

/// <summary>
/// The full player, laid out as Apple Music's on Windows: the Flow background (the cover's own
/// colours, moving, as on the phone), the cover with
/// the title and "Artist — Album" centred under it, a thin progress line, small white controls,
/// and the lyrics filling the right half.
/// </summary>
public sealed class NowPlayingView : Grid
{
    private static readonly Color Background0 = Color.FromArgb(255, 31, 31, 31);
    private static readonly SolidColorBrush White = new(Colors.White);
    private static readonly SolidColorBrush Soft = new(Color.FromArgb(150, 255, 255, 255));

    private readonly CoverView cover = new() { Size = 600, DecodeSize = 640, CornerRadius = new CornerRadius(6) };
    private readonly TextBlock title = new() { FontSize = 18, FontWeight = FontWeights.SemiBold, Foreground = White, TextTrimming = TextTrimming.CharacterEllipsis, HorizontalAlignment = HorizontalAlignment.Center };
    /// <summary>"Artist — Album", each name a link; a long line ends in "…" rather than running off both sides.</summary>
    private readonly TextBlock subtitle = new() { FontSize = 17, Foreground = Soft, TextAlignment = TextAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis, TextWrapping = TextWrapping.NoWrap, Margin = new Thickness(0, 2, 0, 0) };
    /// <summary>Apple's quality badge: "Lossless" or "Hi-Res Lossless" with its logo; the bits and kHz on hover.</summary>
    private readonly Border badge = new() { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 0), Padding = new Thickness(6, 2, 6, 2), CornerRadius = new CornerRadius(4), Background = new SolidColorBrush(Colors.Transparent) };
    private readonly TextBlock badgeText = new() { FontSize = 12, FontWeight = FontWeights.SemiBold, Foreground = Soft, VerticalAlignment = VerticalAlignment.Center };
    private readonly Microsoft.UI.Xaml.Shapes.Path badgeLogo = LosslessLogo();
    private readonly TranslateTransform leftShift = new();
    private Microsoft.UI.Xaml.Media.Animation.Storyboard? sliding;
    private readonly LineSeekBar seek = new();
    private readonly FontIcon playIcon = new() { Glyph = "", FontSize = 22, Foreground = White };
    private readonly StackPanel left = new() { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
    private readonly LyricsView lyrics = new();
    private readonly FlowBackground flow = new();
    private readonly Button lyricsButton;
    private bool showLyrics = true;
    /// <summary>Whether the playing song has a lyric file; without one the player sits in the middle and the lyrics button greys out.</summary>
    private bool hasLyrics = true;
    private bool Showing => showLyrics && hasLyrics;
    private Track? shown;

    public event Action? CloseRequested;

    public NowPlayingView()
    {
        Background = new SolidColorBrush(Background0);
        // Always dark, in Windows' light mode too: white words and controls over the cover's
        // colours, as Apple Music's full player.
        RequestedTheme = ElementTheme.Dark;
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.08, GridUnitType.Star) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.92, GridUnitType.Star) });
        SetColumnSpan(flow, 2);
        Children.Add(flow);

        // ---- Left: the cover, the song and the controls ----
        left.Children.Add(cover);
        left.Children.Add(new Border { Height = 34 });
        left.Children.Add(title);
        left.Children.Add(subtitle);
        badge.Child = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 5, Children = { badgeLogo, badgeText } };
        badge.PointerEntered += (_, _) => ((SolidColorBrush)badge.Background).Color = Color.FromArgb(28, 255, 255, 255);
        badge.PointerExited += (_, _) => ((SolidColorBrush)badge.Background).Color = Colors.Transparent;
        left.Children.Add(badge);
        left.Children.Add(new Border { Height = 14 });
        left.Children.Add(seek);
        left.Children.Add(new Border { Height = 12 });

        var controls = new Grid();
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var leftButtons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        var volume = Small("", "Volume", null);
        volume.Flyout = new Flyout { Content = new VolumeBar { Width = 220 }, Placement = FlyoutPlacementMode.Top };
        var more = Small("", "More", null);
        more.Click += (_, _) => More().ShowAt(more);
        leftButtons.Children.Add(volume);
        leftButtons.Children.Add(more);
        controls.Children.Add(leftButtons);

        var transport = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14, VerticalAlignment = VerticalAlignment.Center };
        transport.Children.Add(Small("", "Previous", Player.Previous, 20));
        var play = Small("", "Play", Player.Toggle, 22);
        play.Content = playIcon;
        play.Width = play.Height = 44;
        transport.Children.Add(play);
        transport.Children.Add(Small("", "Next", Player.Next, 20));
        SetColumn(transport, 1);
        controls.Children.Add(transport);

        lyricsButton = Small("", "Lyrics", ToggleLyrics);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(lyricsButton, "Show lyrics");
        // The heart sits just before the lyrics button.
        var rightButtons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 4,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Center,
            Children = { new LikeButton(White, 36), lyricsButton },
        };
        SetColumn(rightButtons, 2);
        controls.Children.Add(rightButtons);
        left.Children.Add(controls);
        left.RenderTransform = leftShift;
        Children.Add(left);

        // ---- Right: the lyrics, from the top of the window to the bottom ----
        lyrics.Margin = new Thickness(0, 0, 40, 0);
        lyrics.OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(350) };
        SetColumn(lyrics, 1);
        Children.Add(lyrics);

        // The full player fills the screen, as Apple Music's does; this (or Esc) leaves it.
        var exit = Small("\uE73F", "Exit full screen (Esc)", () => CloseRequested?.Invoke(), 14);
        exit.HorizontalAlignment = HorizontalAlignment.Right;
        exit.VerticalAlignment = VerticalAlignment.Top;
        exit.Margin = new Thickness(0, 16, 20, 0);
        SetColumn(exit, 1);
        Children.Add(exit);

        SizeChanged += (_, _) =>
        {
            Fit();
            // Lyrics off: the cover stays in the middle as the screen's size changes.
            sliding?.Stop();
            leftShift.X = CenterShift();
        };
        Player.TrackChanged += () => { if (Visibility == Visibility.Visible) ShowTrack(); };
        Player.StateChanged += () =>
        {
            playIcon.Glyph = Player.IsPlaying ? "" : "";
            flow.SetRunning(Visibility == Visibility.Visible, Player.IsPlaying);
        };
        ShowLyricsState();
    }

    /// <summary>The cover as big as fits, as Apple's: a little over half the window's height.</summary>
    private void Fit()
    {
        var column = ActualWidth * 1.08 / 2;
        var side = Math.Max(200, Math.Min(Math.Min(ActualHeight * 0.56, column * 0.68), 720));
        cover.Size = side;
        left.Width = side;
    }

    private static Button Small(string glyph, string name, Action? onClick, double size = 16)
    {
        var b = new Button
        {
            Content = new FontIcon { Glyph = glyph, FontSize = size, Foreground = White },
            Style = (Style)Application.Current.Resources["IconButton"],
            Width = 36,
            Height = 36,
            CornerRadius = new CornerRadius(6),
        };
        ToolTipService.SetToolTip(b, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, name);
        if (onClick != null) b.Click += (_, _) => onClick();
        return b;
    }

    /// <summary>Shuffle, repeat, and the album and artist pages.</summary>
    private MenuFlyout More()
    {
        var menu = new MenuFlyout();
        var shuffle = new ToggleMenuFlyoutItem { Text = "Shuffle", IsChecked = Player.Shuffle, Icon = new FontIcon { Glyph = "" } };
        shuffle.Click += (_, _) => Player.SetShuffle(!Player.Shuffle);
        menu.Items.Add(shuffle);
        var repeat = new MenuFlyoutSubItem { Text = "Repeat", Icon = new FontIcon { Glyph = "" } };
        foreach (var (mode, label) in new[] { (RepeatMode.Off, "Off"), (RepeatMode.All, "All"), (RepeatMode.One, "One") })
        {
            var item = new RadioMenuFlyoutItem { Text = label, GroupName = "repeat", IsChecked = Player.Repeat == mode };
            item.Click += (_, _) => { while (Player.Repeat != mode) Player.CycleRepeat(); };
            repeat.Items.Add(item);
        }
        menu.Items.Add(repeat);
        if (shown is { } song) menu.Items.Add(PlaylistMenu.AddTo(new[] { song }));
        if (shown is { } t)
        {
            menu.Items.Add(new MenuFlyoutSeparator());
            var album = new MenuFlyoutItem { Text = "View album", Icon = new FontIcon { Glyph = "" } };
            album.Click += (_, _) => Go(typeof(AlbumPage), t.AlbumKey);
            menu.Items.Add(album);
            foreach (var name in t.Artists)
            {
                var artist = new MenuFlyoutItem { Text = $"View {name}", Icon = new FontIcon { Glyph = "" } };
                artist.Click += (_, _) => Go(typeof(ArtistPage), name);
                menu.Items.Add(artist);
            }
        }
        return menu;
    }

    private void Go(Type page, string param)
    {
        CloseRequested?.Invoke();
        MainWindow.Instance.Navigate(page, param);
    }

    /// <summary>
    /// Lyrics off: they fade away while the cover and controls glide to the middle of the screen,
    /// as Apple's button does; on again, the reverse.
    /// </summary>
    private void ToggleLyrics()
    {
        if (!hasLyrics) return;
        showLyrics = !showLyrics;
        ShowLyricsState();
        Slide(CenterShift(), 520);
    }

    /// <summary>How far the cover moves to sit in the middle of the screen while the lyrics are off.</summary>
    private double CenterShift() => Showing ? 0 : ActualWidth / 2 - ActualWidth * 1.08 / 2 / 2;

    private void Slide(double to, int ms)
    {
        var anim = new Microsoft.UI.Xaml.Media.Animation.DoubleAnimationUsingKeyFrames();
        anim.KeyFrames.Add(new Microsoft.UI.Xaml.Media.Animation.SplineDoubleKeyFrame
        {
            KeyTime = TimeSpan.FromMilliseconds(ms),
            Value = to,
            KeySpline = new Microsoft.UI.Xaml.Media.Animation.KeySpline { ControlPoint1 = new Windows.Foundation.Point(0.3, 0), ControlPoint2 = new Windows.Foundation.Point(0.2, 1) },
        });
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTarget(anim, leftShift);
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTargetProperty(anim, "X");
        var sb = new Microsoft.UI.Xaml.Media.Animation.Storyboard { Children = { anim } };
        sb.Completed += (_, _) => { if (sliding == sb) { sliding = null; leftShift.X = to; } };
        sliding = sb;
        sb.Begin();
    }

    private void ShowLyricsState()
    {
        lyrics.Opacity = Showing ? 1 : 0;
        lyrics.IsHitTestVisible = Showing;
        lyrics.Active = Showing && Visibility == Visibility.Visible;
        lyricsButton.Background = Showing ? new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)) : new SolidColorBrush(Colors.Transparent);
        // Greyed out when the song has no lyric file.
        lyricsButton.IsEnabled = hasLyrics;
        lyricsButton.Opacity = hasLyrics ? 1 : 0.35;
        var name = hasLyrics ? (showLyrics ? "Hide lyrics" : "Show lyrics") : "No lyrics for this song";
        ToolTipService.SetToolTip(lyricsButton, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(lyricsButton, name);
    }

    public void Open()
    {
        Visibility = Visibility.Visible;
        ShowTrack();
        lyrics.Active = Showing;
        playIcon.Glyph = Player.IsPlaying ? "" : "";
        flow.SetRunning(true, Player.IsPlaying);
        Fit();
        Tick();
    }

    public void Close()
    {
        lyrics.Active = false;
        flow.SetRunning(false, false);
        Visibility = Visibility.Collapsed;
    }

    public void Tick() => seek.Tick();

    private void ShowTrack()
    {
        var t = Player.Current;
        if (t == null || t == shown) return;
        shown = t;
        cover.Track = t;
        flow.Show(t);
        title.Text = t.Title;
        // "Artist — Album": each name opens its page.
        subtitle.Inlines.Clear();
        var names = t.Artists;
        for (var i = 0; i < names.Count; i++)
        {
            if (i > 0) subtitle.Inlines.Add(new Microsoft.UI.Xaml.Documents.Run { Text = ", " });
            var name = names[i];
            subtitle.Inlines.Add(Link(name, () => Go(typeof(ArtistPage), name)));
        }
        if (!string.IsNullOrWhiteSpace(t.Album))
        {
            subtitle.Inlines.Add(new Microsoft.UI.Xaml.Documents.Run { Text = " \u2014 " });
            subtitle.Inlines.Add(Link(t.Album, () => Go(typeof(AlbumPage), t.AlbumKey)));
        }
        ShowQuality(t);
        lyrics.Show(t);
        CheckLyrics(t);
    }

    /// <summary>
    /// With no lyric file the cover and controls move to the middle and the lyrics button greys
    /// out; the next song with lyrics brings them back (as the listener last left them).
    /// </summary>
    private async void CheckLyrics(Track t)
    {
        var has = await LocalTune.Lyrics.LyricFiles.FindAsync(t) != null;
        if (shown != t || has == hasLyrics) return;
        hasLyrics = has;
        ShowLyricsState();
        if (Visibility == Visibility.Visible && ActualWidth > 0) Slide(CenterShift(), 520);
        else
        {
            sliding?.Stop();
            leftShift.X = CenterShift();
        }
    }

    private static Microsoft.UI.Xaml.Documents.Hyperlink Link(string text, Action onClick)
    {
        var link = new Microsoft.UI.Xaml.Documents.Hyperlink { Foreground = Soft, UnderlineStyle = Microsoft.UI.Xaml.Documents.UnderlineStyle.None };
        link.Inlines.Add(new Microsoft.UI.Xaml.Documents.Run { Text = text });
        link.Click += (_, _) => onClick();
        return link;
    }

    /// <summary>The badge for the song's file: its tier, with the waveform logo for the lossless ones.</summary>
    private void ShowQuality(Track t)
    {
        var tier = t.QualityTier;
        if (tier == null)
        {
            badge.Visibility = Visibility.Collapsed;
            return;
        }
        badge.Visibility = Visibility.Visible;
        badgeText.Text = tier;
        // Dolby formats show as words only, with no logo (as on the phone).
        badgeLogo.Visibility = tier == "Dolby Atmos" ? Visibility.Collapsed : Visibility.Visible;
        ToolTipService.SetToolTip(badge, new ToolTip { Content = tier + Environment.NewLine + t.QualityDetail });
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(badge, tier + ", " + t.QualityDetail);
    }

    /// <summary>
    /// Apple's Lossless logo, shown before "Lossless" and "Hi-Res Lossless" as Apple Music does.
    /// From https://commons.wikimedia.org/wiki/File:Apple_Lossless_logo.svg (public domain as a
    /// simple shape; the mark itself is Apple's trademark). Its 15 x 9 outline, drawn in the
    /// badge's own colour.
    /// </summary>
    internal const string LosslessLogoPath = "M8.184,0.35C9.944,0.35 10.703,3.296 11.338,5.238C11.673,3.842 11.497,3.542 11.857,3.542C11.99,3.542 12.126,3.633 12.126,3.798C12.126,3.809 12.123,3.839 12.117,3.883L12.091,4.058C12.02,4.522 11.845,5.494 11.654,6.144C13.198,10.191 14.345,4.861 14.474,3.772C14.493,3.615 14.612,3.542 14.731,3.542C14.891,3.542 15.022,3.662 14.997,3.843C14.72,5.605 14.295,8.35 12.547,8.35C11.582,8.35 11.04,7.595 10.611,6.73C9.54,4.626 9.047,1.093 7.997,1.093C7.66,1.093 7.411,1.444 7.394,1.444C7.362,1.444 7.337,1.301 7.023,0.909C7.322,0.567 7.734,0.35 8.184,0.35ZM2.458,0.354C5.211,0.354 5.456,7.618 7.014,7.618C7.197,7.618 7.394,7.507 7.61,7.256C7.729,7.458 7.851,7.638 7.978,7.796C7.667,8.151 7.28,8.35 6.795,8.35C5.054,8.349 4.306,5.434 3.663,3.466C3.511,4.097 3.432,4.669 3.402,4.925C3.382,5.088 3.263,5.163 3.143,5.163C3.009,5.163 2.874,5.071 2.874,4.908L2.874,4.908L2.877,4.87C2.966,4.223 3.146,3.243 3.347,2.56C3.079,1.858 2.745,1.091 2.252,1.091C1.257,1.091 0.687,3.591 0.527,4.925C0.508,5.088 0.388,5.163 0.268,5.163C0.135,5.163 0,5.071 0,4.908C0,4.896 0.001,4.883 0.002,4.87C0.283,2.836 0.808,0.354 2.458,0.354ZM5.315,0.35C5.809,0.35 6.339,0.608 6.797,1.211C6.822,1.241 7.078,1.639 7.159,1.777C8.277,3.802 8.818,7.627 9.881,7.627C10.065,7.627 10.264,7.513 10.484,7.256C10.604,7.458 10.726,7.638 10.852,7.796C10.542,8.15 10.155,8.35 9.67,8.35C6.933,8.349 6.636,1.09 5.128,1.09C4.788,1.09 4.536,1.444 4.519,1.444C4.487,1.444 4.462,1.301 4.148,0.909C4.455,0.558 4.87,0.35 5.315,0.35Z";

    internal static Microsoft.UI.Xaml.Shapes.Path LosslessLogo(Brush? fill = null) => new()
    {
        Data = (Geometry)Microsoft.UI.Xaml.Markup.XamlBindingHelper.ConvertValue(typeof(Geometry), LosslessLogoPath),
        Fill = fill ?? Soft,
        Stretch = Stretch.Uniform,
        Height = 9,
        Width = 15,
        VerticalAlignment = VerticalAlignment.Center,
    };
}

/// <summary>
/// Apple's progress line: the time played, a thin line that thickens under the mouse, and the
/// time left. Click or drag anywhere on it to move through the song.
/// </summary>
public sealed class LineSeekBar : Grid
{
    private readonly TextBlock elapsed = new() { FontSize = 13, Width = 52, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock remaining = new() { FontSize = 13, Width = 52, TextAlignment = TextAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
    private readonly Grid bar = new() { Height = 22, Background = new SolidColorBrush(Colors.Transparent), VerticalAlignment = VerticalAlignment.Center };
    private readonly Border track = new() { Height = 4, CornerRadius = new CornerRadius(2), VerticalAlignment = VerticalAlignment.Center, Background = new SolidColorBrush(Color.FromArgb(56, 255, 255, 255)) };
    private readonly Border fill = new() { Height = 4, CornerRadius = new CornerRadius(2), VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Left, Background = new SolidColorBrush(Color.FromArgb(230, 255, 255, 255)) };
    private double? dragging;
    private readonly double line, hovered;

    /// <param name="small">The mini player's: a 3 px line and smaller times, as Apple's.</param>
    public LineSeekBar(bool small = false)
    {
        line = small ? 3 : 4;
        hovered = small ? 6 : 7;
        track.Height = fill.Height = line;
        track.CornerRadius = fill.CornerRadius = new CornerRadius(line / 2);
        if (small)
        {
            elapsed.FontSize = remaining.FontSize = 12;
            elapsed.Width = remaining.Width = 54;
        }
        var soft = new SolidColorBrush(Color.FromArgb(150, 255, 255, 255));
        elapsed.Foreground = soft;
        remaining.Foreground = soft;
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bar.Children.Add(track);
        bar.Children.Add(fill);
        SetColumn(bar, 1);
        SetColumn(remaining, 2);
        Children.Add(elapsed);
        Children.Add(bar);
        Children.Add(remaining);

        bar.PointerEntered += (_, _) => Thick(true);
        bar.PointerExited += (_, _) => { if (dragging == null) Thick(false); };
        bar.PointerPressed += (_, e) =>
        {
            bar.CapturePointer(e.Pointer);
            dragging = At(e);
            Show(dragging.Value);
            e.Handled = true;
        };
        bar.PointerMoved += (_, e) =>
        {
            if (dragging == null) return;
            dragging = At(e);
            Show(dragging.Value);
        };
        bar.PointerReleased += (_, e) => Let(e);
        bar.PointerCaptureLost += (_, e) => Let(e);
        bar.SizeChanged += (_, _) => Tick();
    }

    private void Thick(bool on)
    {
        track.Height = fill.Height = on ? hovered : line;
        track.CornerRadius = fill.CornerRadius = new CornerRadius((on ? hovered : line) / 2);
    }

    private double At(PointerRoutedEventArgs e) => Math.Clamp(e.GetCurrentPoint(bar).Position.X / Math.Max(1, bar.ActualWidth), 0, 1);

    private void Let(PointerRoutedEventArgs e)
    {
        if (dragging is not { } at) return;
        dragging = null;
        bar.ReleasePointerCaptures();
        Player.SeekTo((long)(at * Player.DurationMs));
        Thick(false);
    }

    private void Show(double fraction)
    {
        var d = Player.DurationMs;
        fill.Width = Math.Max(0, bar.ActualWidth * fraction);
        var p = (long)(fraction * d);
        elapsed.Text = Format.Time(p);
        remaining.Text = "-" + Format.Time(Math.Max(0, d - p));
    }

    public void Tick()
    {
        if (dragging != null) return;
        var d = Player.DurationMs;
        Show(d > 0 ? Math.Clamp((double)Player.PositionMs / d, 0, 1) : 0);
    }
}
