using LocalTune.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace LocalTune.Views;

/// <summary>Cover: cover, title and artist, album, time. Number: an album's track list. Detailed: the Songs page's columns.</summary>
public enum RowMode { Cover, Number, Detailed }

/// <summary>A song in a list: right-click (or "…") for the song menu.</summary>
public sealed class SongRow : Grid
{
    private readonly TextBlock number = new() { FontSize = 13, VerticalAlignment = VerticalAlignment.Center };
    private readonly FontIcon playing = new() { Glyph = "", FontSize = 14, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly CoverView cover = new() { Size = 40, CornerRadius = new CornerRadius(4) };
    private readonly TextBlock title = new() { TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock artist = new() { FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock album = new() { FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock duration = new() { FontSize = 13, VerticalAlignment = VerticalAlignment.Center };
    private readonly StackPanel names = new() { VerticalAlignment = VerticalAlignment.Center };
    // The Songs page's extra columns.
    private readonly TextBlock artistColumn = new() { FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock genre = new() { FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock plays = new() { FontSize = 13, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Right };
    private readonly HeartIcon heartIcon = new(14);
    private readonly Button heart;
    private readonly Button more;
    private readonly Brush primary = (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
    private readonly Brush secondary = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
    private readonly Brush accent = (Brush)Application.Current.Resources["AccentBrush"];

    public SongRow()
    {
        number.Foreground = secondary;
        artist.Foreground = secondary;
        album.Foreground = secondary;
        duration.Foreground = secondary;
        artistColumn.Foreground = secondary;
        genre.Foreground = secondary;
        plays.Foreground = secondary;
        playing.Foreground = accent;
        heart = new Button { Content = heartIcon, Style = (Style)Application.Current.Resources["IconButton"], Width = 32, Height = 32, HorizontalAlignment = HorizontalAlignment.Center };
        heart.Click += (_, _) =>
        {
            if (Track is not { } t) return;
            if (LibraryStore.IsLiked(t)) LibraryStore.Unlike(t); else LibraryStore.Like(t);
            ShowHeart(animate: true);
        };
        more = new Button { Content = new FontIcon { Glyph = "", FontSize = 14 }, Style = (Style)Application.Current.Resources["IconButton"], Width = 32, Height = 32 };
        ToolTipService.SetToolTip(more, "More");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(more, "More");
        more.Click += (_, _) => { if (Track is { } t) SongMenu.Show(more, t, ListAround()); };
        ColumnSpacing = 14;
        Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        Loaded += (_, _) => { Player.TrackChanged += Highlight; Highlight(); };
        Unloaded += (_, _) => Player.TrackChanged -= Highlight;
        ContextRequested += (_, e) =>
        {
            if (Track is not { } t) return;
            SongMenu.Show(this, t, ListAround(), e.TryGetPosition(this, out var at) ? at : null);
            e.Handled = true;
        };
        Layout();
    }

    public static readonly DependencyProperty TrackProperty =
        DependencyProperty.Register(nameof(Track), typeof(Track), typeof(SongRow), new PropertyMetadata(null, (d, _) => ((SongRow)d).Show()));

    public Track? Track
    {
        get => (Track?)GetValue(TrackProperty);
        set => SetValue(TrackProperty, value);
    }

    public static readonly DependencyProperty ModeProperty =
        DependencyProperty.Register(nameof(Mode), typeof(RowMode), typeof(SongRow), new PropertyMetadata(RowMode.Cover, (d, _) => ((SongRow)d).Layout()));

    public RowMode Mode
    {
        get => (RowMode)GetValue(ModeProperty);
        set => SetValue(ModeProperty, value);
    }

    /// <summary>The playlist whose page is open, so its songs' menus can offer "Remove from this playlist".</summary>
    public static string? OpenPlaylistId { get; set; }

    /// <summary>The Songs page's columns, shared with its heading row so they line up.</summary>
    public static void DetailedColumns(Grid g)
    {
        g.ColumnDefinitions.Clear();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });                     // cover
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(3, GridUnitType.Star) });   // title
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(52) });                     // time
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(2.2, GridUnitType.Star) }); // artist
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(2.6, GridUnitType.Star) }); // album
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.1, GridUnitType.Star) }); // genre
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(72) });                     // favourite
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(48) });                     // plays
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(32) });                     // more
        g.ColumnSpacing = 12;
    }

    private void Layout()
    {
        Children.Clear();
        ColumnDefinitions.Clear();
        names.Children.Clear();
        if (Mode == RowMode.Detailed)
        {
            Height = 48;
            DetailedColumns(this);
            cover.Size = 36;
            Children.Add(cover);
            title.VerticalAlignment = VerticalAlignment.Center;
            SetColumn(title, 1);
            Children.Add(title);
            SetColumn(duration, 2);
            Children.Add(duration);
            SetColumn(artistColumn, 3);
            Children.Add(artistColumn);
            SetColumn(album, 4);
            Children.Add(album);
            SetColumn(genre, 5);
            Children.Add(genre);
            SetColumn(heart, 6);
            Children.Add(heart);
            SetColumn(plays, 7);
            Children.Add(plays);
            SetColumn(more, 8);
            Children.Add(more);
            Show();
            return;
        }
        var withCover = Mode == RowMode.Cover;
        ColumnSpacing = 14;
        Height = withCover ? 56 : 44;
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(withCover ? 40 : 28) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(2, GridUnitType.Star) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(withCover ? 1.4 : 0, GridUnitType.Star) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        if (withCover)
        {
            cover.Size = 40;
            Children.Add(cover);
        }
        else
        {
            Children.Add(number);
            Children.Add(playing);
        }
        names.Children.Add(title);
        if (withCover) names.Children.Add(artist);
        SetColumn(names, 1);
        Children.Add(names);
        if (withCover)
        {
            SetColumn(album, 2);
            Children.Add(album);
        }
        SetColumn(duration, 3);
        Children.Add(duration);
        Show();
    }

    private void Show()
    {
        var t = Track;
        cover.Track = Mode != RowMode.Number ? t : null;
        title.Text = t?.Title ?? "";
        artist.Text = t?.Artist ?? "";
        artistColumn.Text = t?.Artist ?? "";
        album.Text = t?.Album ?? "";
        genre.Text = t?.Genre ?? "";
        duration.Text = t?.DurationText ?? "";
        number.Text = t?.NumberText ?? "";
        if (Mode == RowMode.Detailed)
        {
            plays.Text = t != null ? History.PlayCount(t).ToString() : "";
            ShowHeart();
        }
        Highlight();
    }

    private void ShowHeart(bool animate = false)
    {
        var liked = Track != null && LibraryStore.IsLiked(Track);
        heartIcon.Set(liked, secondary, animate);
        heart.Opacity = liked ? 1 : 0.55;
        var name = liked ? "Remove from Favorites" : "Add to Favorites";
        ToolTipService.SetToolTip(heart, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(heart, name);
    }

    /// <summary>The playing song stands out in the accent.</summary>
    private void Highlight()
    {
        var on = Track != null && Player.Current?.Path == Track.Path;
        title.Foreground = on ? accent : primary;
        if (Mode == RowMode.Number)
        {
            playing.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
            number.Visibility = on ? Visibility.Collapsed : Visibility.Visible;
        }
    }

    /// <summary>The list this row is in (Play and Shuffle in the menu play it from this song).</summary>
    private IReadOnlyList<Track> ListAround()
    {
        DependencyObject? at = this;
        while (at != null && at is not SongList) at = VisualTreeHelper.GetParent(at);
        return (at as SongList)?.Tracks ?? (IReadOnlyList<Track>)(Track != null ? new[] { Track } : Array.Empty<Track>());
    }
}

/// <summary>A list of songs: click plays one, with the rest of the list after it.</summary>
public sealed class SongList : ListView
{
    private readonly List<Track> tracks;

    public IReadOnlyList<Track> Tracks => tracks;

    /// <summary>Shows other songs in the same list (a filter or the order changed).</summary>
    public void Replace(List<Track> songs)
    {
        tracks.Clear();
        tracks.AddRange(songs);
        ItemsSource = null;
        ItemsSource = tracks;
    }

    public SongList(List<Track> tracks, RowMode mode = RowMode.Cover)
    {
        this.tracks = tracks;
        SelectionMode = ListViewSelectionMode.None;
        IsItemClickEnabled = true;
        Padding = new Thickness(20, 0, 20, 24);
        ItemTemplate = (DataTemplate)Application.Current.Resources[mode switch
        {
            RowMode.Number => "SongNumberRow",
            RowMode.Detailed => "SongDetailedRow",
            _ => "SongCoverRow",
        }];
        ItemContainerStyle = new Style(typeof(ListViewItem))
        {
            BasedOn = (Style)Application.Current.Resources["DefaultListViewItemStyle"],
            Setters = { new Setter(HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch) },
        };
        ItemsSource = tracks;
        ItemClick += (_, e) =>
        {
            if (e.ClickedItem is Track t) Player.Play(this.tracks, this.tracks.IndexOf(t), shuffled: false);
        };
    }
}
