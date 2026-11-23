using LocalTune.Core;
using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.UI;

namespace LocalTune.Views;

/// <summary>The bar along the bottom: the song, the controls and the volume.</summary>
public sealed class PlayerBar : Grid
{
    private readonly CoverView cover = new() { Size = 54 };
    private readonly TextBlock title = new() { FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis, Text = "Not playing" };
    private readonly TextBlock artist = new() { FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly SeekBar seek = new();

    public event Action? OpenPlayer;
    public event Action? ToggleQueue;
    public event Action? OpenMini;

    private readonly SolidColorBrush tint = new(Color.FromArgb(255, 40, 40, 40));
    private int tintId;

    /// <summary>After the song's name: the Lossless logo and "Lossless" or "Hi-Res Lossless" (words only for Dolby).</summary>
    private readonly StackPanel badge = new() { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 1, 0, 0) };
    private readonly TextBlock badgeText = new() { FontSize = 11, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };
    private readonly Microsoft.UI.Xaml.Shapes.Path badgeLogo;

    public PlayerBar()
    {
        Height = 88;
        Padding = new Thickness(16, 0, 16, 0);
        ColumnSpacing = 12;
        // Tinted with the playing song's cover colour (see Tint).
        Background = tint;
        artist.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.3, GridUnitType.Star), MaxWidth = 600 });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var song = new Grid { ColumnSpacing = 12 };
        song.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        song.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var secondary = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        badgeText.Foreground = secondary;
        badgeLogo = NowPlayingView.LosslessLogo(secondary);
        badge.Children.Add(badgeLogo);
        badge.Children.Add(badgeText);
        // The name keeps its "…" when long, leaving room for the badge beside it.
        var titleRow = new StackPanel { Orientation = Orientation.Horizontal, Children = { title, badge } };
        var names = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { titleRow, artist } };
        names.SizeChanged += (_, _) => FitTitle(names.ActualWidth);
        badge.SizeChanged += (_, _) => FitTitle(names.ActualWidth);
        SetColumn(names, 1);
        song.Children.Add(cover);
        song.Children.Add(names);
        var songButton = new Button
        {
            Content = song,
            Style = (Style)Application.Current.Resources["TileButton"],
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Center,
            Padding = new Thickness(6),
        };
        ToolTipService.SetToolTip(songButton, "Open the player");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(songButton, "Open the player");
        songButton.Click += (_, _) => OpenPlayer?.Invoke();
        Children.Add(songButton);

        var middle = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { new PlayControls(large: false), seek } };
        SetColumn(middle, 1);
        Children.Add(middle);

        var right = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, Spacing = 4 };
        right.Children.Add(new LikeButton());
        Button addTo = null!;
        addTo = SmallButton("\uECC8", "Add to playlist", () => { if (Player.Current is { } t) PlaylistMenu.Show(addTo, new[] { t }); });
        right.Children.Add(addTo);
        right.Children.Add(SmallButton("", "Playing next", () => ToggleQueue?.Invoke()));
        right.Children.Add(new VolumeBar { Width = 140, Margin = new Thickness(8, 0, 0, 0) });
        // Pops the song out into the mini player.
        right.Children.Add(SmallButton("\uEE49", "Mini player", () => OpenMini?.Invoke()));
        SetColumn(right, 2);
        Children.Add(right);

        Loaded += (_, _) => { Player.TrackChanged += Show; Show(); };
        Unloaded += (_, _) => Player.TrackChanged -= Show;
        Show();
    }

    /// <summary>
    /// The bar takes the cover's colour, kept deep in dark mode and pale in light mode so the
    /// words stay easy to read, and glides to the next song's colour.
    /// </summary>
    private async void Tint(Track? t)
    {
        var id = ++tintId;
        var dark = ActualTheme == ElementTheme.Dark || (ActualTheme == ElementTheme.Default && Application.Current.RequestedTheme == ApplicationTheme.Dark);
        var neutral = dark ? Color.FromArgb(255, 40, 40, 40) : Color.FromArgb(255, 238, 238, 240);
        var hsl = t == null ? null : await Task.Run(() => CoverColour.Of(t));
        if (id != tintId) return;
        var to = hsl is { } c
            ? dark ? CoverColour.Shade(c, 0.7, 0.42, 0.15, 0.22) : CoverColour.Shade(c, 0.6, 0.38, 0.86, 0.9)
            : neutral;
        if (!IsLoaded) { tint.Color = to; return; }
        var anim = new Microsoft.UI.Xaml.Media.Animation.ColorAnimation
        {
            To = to,
            Duration = TimeSpan.FromMilliseconds(700),
            EasingFunction = new Microsoft.UI.Xaml.Media.Animation.CubicEase { EasingMode = Microsoft.UI.Xaml.Media.Animation.EasingMode.EaseInOut },
            EnableDependentAnimation = true,
        };
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTarget(anim, tint);
        Microsoft.UI.Xaml.Media.Animation.Storyboard.SetTargetProperty(anim, "Color");
        new Microsoft.UI.Xaml.Media.Animation.Storyboard { Children = { anim } }.Begin();
    }

    private void FitTitle(double width)
    {
        var room = width - (badge.Visibility == Visibility.Visible ? badge.ActualWidth + badge.Margin.Left : 0);
        title.MaxWidth = Math.Max(40, room);
    }

    private void ShowQuality(Track? t)
    {
        var tier = t?.QualityTier;
        badge.Visibility = tier == null ? Visibility.Collapsed : Visibility.Visible;
        if (tier == null) return;
        badgeText.Text = tier;
        badgeLogo.Visibility = tier == "Dolby Atmos" ? Visibility.Collapsed : Visibility.Visible;
        ToolTipService.SetToolTip(badge, tier + Environment.NewLine + t!.QualityDetail);
    }

    private static Button SmallButton(string glyph, string name, Action onClick)
    {
        var b = new Button
        {
            Content = new FontIcon { Glyph = glyph, FontSize = 16 },
            Style = (Style)Application.Current.Resources["IconButton"],
            Width = 40,
            Height = 40,
        };
        ToolTipService.SetToolTip(b, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, name);
        b.Click += (_, _) => onClick();
        return b;
    }

    private void Show()
    {
        ShowQuality(Player.Current);
        Tint(Player.Current);
        var t = Player.Current;
        cover.Track = t;
        title.Text = t?.Title ?? "Not playing";
        artist.Text = t?.Artist ?? "";
    }

    public void Tick() => seek.Tick();
}

/// <summary>What's playing next, beside the library.</summary>
public sealed class QueuePanel : Grid
{
    private readonly ListView list = new() { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };

    public event Action? CloseRequested;

    public QueuePanel()
    {
        Width = 340;
        Background = (Brush)Application.Current.Resources["LayerOnMicaBaseAltFillColorDefaultBrush"];
        BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"];
        BorderThickness = new Thickness(1, 0, 0, 0);
        RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        var head = new Grid { Padding = new Thickness(18, 12, 8, 8) };
        head.Children.Add(new TextBlock { Text = "Playing next", Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center });
        var close = new Button { Content = new FontIcon { Glyph = "", FontSize = 14 }, Style = (Style)Application.Current.Resources["IconButton"], Width = 36, Height = 36, HorizontalAlignment = HorizontalAlignment.Right };
        close.Click += (_, _) => CloseRequested?.Invoke();
        head.Children.Add(close);
        Children.Add(head);
        SetRow(list, 1);
        Children.Add(list);
        list.ItemClick += (_, e) => { if (e.ClickedItem is FrameworkElement { Tag: int i }) Player.PlayAt(i); };
        Loaded += (_, _) => { Player.QueueChanged += Refresh; Player.TrackChanged += Refresh; };
        Unloaded += (_, _) => { Player.QueueChanged -= Refresh; Player.TrackChanged -= Refresh; };
    }

    public void Refresh()
    {
        if (Visibility != Visibility.Visible) return;
        list.Items.Clear();
        var q = Player.Queue;
        for (var i = Player.Index; i < q.Count && i < Player.Index + 300; i++)
        {
            var t = q[i];
            var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6), Tag = i };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.Children.Add(new CoverView { Track = t, Size = 40, CornerRadius = new CornerRadius(4) });
            var names = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            names.Children.Add(new TextBlock
            {
                Text = t.Title,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Foreground = i == Player.Index ? (Brush)Application.Current.Resources["AccentBrush"] : (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"],
            });
            names.Children.Add(new TextBlock { Text = t.Artist, FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
            SetColumn(names, 1);
            row.Children.Add(names);
            list.Items.Add(row);
        }
    }
}
