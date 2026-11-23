using LocalTune.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;

namespace LocalTune.Views;

/// <summary>
/// A song's cover, or a grey square with a note while there's none. The picture is the fill of
/// the rounded shape itself (an image brush), not a picture inside a rounded box: a box doesn't
/// reliably trim what's in it to its corners, so round artist photos came out with flat sides.
/// </summary>
public sealed class CoverView : Grid
{
    private readonly ImageBrush fill = new() { Stretch = Stretch.UniformToFill, AlignmentX = AlignmentX.Center, AlignmentY = AlignmentY.Center };
    private readonly Border face = new();
    private readonly FontIcon note = new() { Glyph = "\uE8D6" };

    public CoverView()
    {
        Background = (Brush)Application.Current.Resources["ControlFillColorSecondaryBrush"];
        note.Foreground = (Brush)Application.Current.Resources["TextFillColorTertiaryBrush"];
        note.HorizontalAlignment = HorizontalAlignment.Center;
        note.VerticalAlignment = VerticalAlignment.Center;
        face.Background = fill;
        Children.Add(note);
        Children.Add(face);
        // The picture's shape follows the cover's corners (a circle for artists).
        RegisterPropertyChangedCallback(CornerRadiusProperty, (_, _) => face.CornerRadius = CornerRadius);
        CornerRadius = new CornerRadius(6);
        face.CornerRadius = CornerRadius;
    }

    private ImageSource? Shown
    {
        set
        {
            fill.ImageSource = value;
            // The note shows only while there's no picture.
            note.Visibility = value == null ? Visibility.Visible : Visibility.Collapsed;
        }
    }

    public static readonly DependencyProperty TrackProperty =
        DependencyProperty.Register(nameof(Track), typeof(Track), typeof(CoverView), new PropertyMetadata(null, (d, _) => ((CoverView)d).Reload()));

    public Track? Track
    {
        get => (Track?)GetValue(TrackProperty);
        set => SetValue(TrackProperty, value);
    }

    public static readonly DependencyProperty SizeProperty =
        DependencyProperty.Register(nameof(Size), typeof(double), typeof(CoverView), new PropertyMetadata(48.0, (d, _) => ((CoverView)d).Resize()));

    /// <summary>The side, in pixels; 0 to fill the space given.</summary>
    public double Size
    {
        get => (double)GetValue(SizeProperty);
        set => SetValue(SizeProperty, value);
    }

    /// <summary>The size covers are decoded at: a few fixed sizes, so a list doesn't hold one copy per width.</summary>
    public int DecodeSize { get; set; }

    private void Resize()
    {
        if (Size > 0)
        {
            Width = Size;
            Height = Size;
        }
        note.FontSize = Math.Max(14, (Size > 0 ? Size : 120) * 0.36);
        Reload();
    }

    /// <summary>A picture shown in place of the song's cover (an artist's photo, once it's found).</summary>
    public void ShowPicture(ImageSource picture)
    {
        this.picture = picture;
        Shown = picture;
    }

    private ImageSource? picture;

    private async void Reload()
    {
        var track = Track;
        picture = null;
        Shown = null;
        if (track == null) return;
        var px = DecodeSize > 0 ? DecodeSize : Size switch
        {
            <= 0 => 640,
            <= 64 => 96,
            <= 200 => 256,
            _ => 640,
        };
        BitmapImage? bmp = await Artwork.LoadAsync(track, px);
        // The row may have been reused for another song while this one loaded.
        if (Track == track && picture == null) Shown = bmp;
    }
}

/// <summary>An album: cover, title, artist. Opens the album page.</summary>
public sealed class AlbumTile : Button
{
    private readonly CoverView cover = new();
    private readonly TextBlock title = new() { TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 8, 0, 0) };
    private readonly TextBlock artist = new() { TextTrimming = TextTrimming.CharacterEllipsis };

    public AlbumTile()
    {
        Style = (Style)Application.Current.Resources["TileButton"];
        artist.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        artist.FontSize = 13;
        Content = new StackPanel { Children = { cover, title, artist } };
        TileSize = 180;
        Click += (_, _) => { if (Album is { } a) MainWindow.Instance.Navigate(typeof(AlbumPage), a.Key); };
        SizeChanged += (_, _) => FitToWidth();
    }

    public static readonly DependencyProperty FillProperty =
        DependencyProperty.Register(nameof(Fill), typeof(bool), typeof(AlbumTile), new PropertyMetadata(false, (d, _) => ((AlbumTile)d).ApplyFill()));

    /// <summary>
    /// Grows and shrinks with the space it's given (the Albums and Artists pages, where each row's
    /// tiles share its width) rather than keeping a fixed size.
    /// </summary>
    public bool Fill
    {
        get => (bool)GetValue(FillProperty);
        set => SetValue(FillProperty, value);
    }

    private void ApplyFill()
    {
        if (!Fill) return;
        Width = double.NaN;
        HorizontalAlignment = HorizontalAlignment.Stretch;
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
    }

    private void FitToWidth()
    {
        if (!Fill || ActualWidth <= 0) return;
        var side = Math.Floor(ActualWidth - 12); // less the tile's padding either side
        if (side > 0 && Math.Abs(cover.Size - side) > 0.5) cover.Size = side;
    }

    /// <summary>A play button on the cover while the pointer is over the tile (Home's shelves).</summary>
    public void AddHoverPlay() => HoverPlay.Corner(this, cover, () => Album?.Tracks ?? new List<Track>());

    public static readonly DependencyProperty AlbumProperty =
        DependencyProperty.Register(nameof(Album), typeof(Album), typeof(AlbumTile), new PropertyMetadata(null, (d, _) => ((AlbumTile)d).Show()));

    public Album? Album
    {
        get => (Album?)GetValue(AlbumProperty);
        set => SetValue(AlbumProperty, value);
    }

    public double TileSize
    {
        get => cover.Size;
        set
        {
            cover.Size = value;
            Width = value + 12; // the cover plus the tile's padding either side
        }
    }

    private void Show()
    {
        cover.Track = Album?.CoverTrack;
        title.Text = Album?.Title ?? "";
        artist.Text = Album?.Artist ?? "";
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, Album == null ? "" : $"{Album.Title}, {Album.Artist}");
    }
}

/// <summary>An artist: a round picture and their name. Opens the artist page.</summary>
public sealed class ArtistTile : Button
{
    private readonly CoverView cover = new();
    private readonly TextBlock name = new() { TextTrimming = TextTrimming.CharacterEllipsis, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 10, 0, 0) };

    public ArtistTile()
    {
        Style = (Style)Application.Current.Resources["TileButton"];
        Content = new StackPanel { Children = { cover, name } };
        TileSize = 150;
        Click += (_, _) => { if (Artist is { } a) MainWindow.Instance.Navigate(typeof(ArtistPage), a.Name); };
        SizeChanged += (_, _) => FitToWidth();
    }

    public static readonly DependencyProperty FillProperty =
        DependencyProperty.Register(nameof(Fill), typeof(bool), typeof(ArtistTile), new PropertyMetadata(false, (d, _) => ((ArtistTile)d).ApplyFill()));

    /// <summary>
    /// Grows and shrinks with the space it's given (the Albums and Artists pages, where each row's
    /// tiles share its width) rather than keeping a fixed size.
    /// </summary>
    public bool Fill
    {
        get => (bool)GetValue(FillProperty);
        set => SetValue(FillProperty, value);
    }

    private void ApplyFill()
    {
        if (!Fill) return;
        Width = double.NaN;
        HorizontalAlignment = HorizontalAlignment.Stretch;
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
    }

    private void FitToWidth()
    {
        if (!Fill || ActualWidth <= 0) return;
        var side = Math.Floor(ActualWidth - 12); // less the tile's padding either side
        if (side > 0 && Math.Abs(cover.Size - side) > 0.5) { cover.Size = side; cover.CornerRadius = new CornerRadius(side / 2); }
    }

    public static readonly DependencyProperty ArtistProperty =
        DependencyProperty.Register(nameof(Artist), typeof(Artist), typeof(ArtistTile), new PropertyMetadata(null, (d, _) => ((ArtistTile)d).Show()));

    public Artist? Artist
    {
        get => (Artist?)GetValue(ArtistProperty);
        set => SetValue(ArtistProperty, value);
    }

    public double TileSize
    {
        get => cover.Size;
        set
        {
            cover.Size = value;
            cover.CornerRadius = new CornerRadius(value / 2);
            Width = value + 12; // the picture plus the tile's padding either side
        }
    }

    private async void LoadPicture()
    {
        if (Artist is not { } a) return;
        var picture = await ArtistImages.LoadAsync(a, 256);
        if (picture != null && Artist == a) cover.ShowPicture(picture);
    }

    private void Show()
    {
        // Their newest album's cover until their own picture (from their songs, or Deezer) is found.
        cover.Track = Artist?.CoverTrack;
        name.Text = Artist?.Name ?? "";
        LoadPicture();
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, Artist?.Name ?? "");
    }
}

public static class Shelves
{
    /// <summary>A heading and a row of albums that scrolls sideways.</summary>
    public static UIElement Albums(string heading, IEnumerable<Album> albums)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 18 };
        foreach (var a in albums) row.Children.Add(new AlbumTile { Album = a, TileSize = 160 });
        Ui.PassVerticalWheel(row);
        return new StackPanel
        {
            Margin = new Thickness(0, 26, 0, 0),
            Children =
            {
                new TextBlock { Text = heading, Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], Margin = new Thickness(0, 0, 0, 10) },
                new ScrollViewer
                {
                    Content = row,
                    HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden,
                    HorizontalScrollMode = ScrollMode.Enabled,
                    VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
                    VerticalScrollMode = ScrollMode.Disabled,
                    Padding = new Thickness(0, 0, 0, 12),
                },
            },
        };
    }
}
