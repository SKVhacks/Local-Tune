using System.Globalization;
using LocalTune.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace LocalTune.Views;

/// <summary>
/// The bar under a library page's title: a "Filter by name" box, then a menu for each kind of
/// filter and one for the order. Each change applies at once, in place: the page isn't rebuilt,
/// so typing in the box keeps the keyboard there.
/// </summary>
internal sealed class ListToolbar : StackPanel
{
    private readonly Action changed;

    public ListToolbar(Action changed, string filterText, Action<string> setFilterText)
    {
        this.changed = changed;
        Orientation = Orientation.Horizontal;
        Spacing = 8;
        Margin = new Thickness(12, 0, 12, 12);
        var box = new TextBox { PlaceholderText = "Filter by name", Width = 240, Text = filterText };
        box.TextChanged += (_, _) =>
        {
            setFilterText(box.Text);
            changed();
        };
        Children.Add(box);
    }

    /// <summary>A menu of choices, its button reading "Label: choice".</summary>
    public void Choice(string label, IReadOnlyList<string> options, Func<int> get, Action<int> set, string glyph = "")
    {
        var button = new DropDownButton();
        void Caption() => button.Content = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
            Children =
            {
                new FontIcon { Glyph = glyph, FontSize = 13 },
                new TextBlock { Text = $"{label}: {options[Math.Clamp(get(), 0, options.Count - 1)]}" },
            },
        };
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedLeft };
        var group = label + Guid.NewGuid().ToString("N");
        for (var i = 0; i < options.Count; i++)
        {
            var index = i;
            var item = new RadioMenuFlyoutItem { Text = options[i], GroupName = group, IsChecked = get() == i };
            item.Click += (_, _) =>
            {
                set(index);
                Caption();
                changed();
            };
            menu.Items.Add(item);
        }
        button.Flyout = menu;
        Caption();
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, label);
        Children.Add(button);
    }

    public static readonly IComparer<string> Collator =
        StringComparer.Create(CultureInfo.CurrentCulture, CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace);

    public static bool Matches(string text, string filter) =>
        filter.Length == 0 || text.Contains(filter, StringComparison.CurrentCultureIgnoreCase);

    /// <summary>"All genres" then the library's genres, most songs first.</summary>
    public static List<string> Genres(IEnumerable<Track> tracks) =>
        new[] { "All genres" }.Concat(tracks.Where(t => t.Genre.Length > 0).GroupBy(t => t.Genre, StringComparer.OrdinalIgnoreCase)
            .OrderByDescending(g => g.Count()).Select(g => g.First().Genre)).ToList();
}

/// <summary>A heading and the count under the toolbar: "48 of 1,315 songs".</summary>
internal static class Counted
{
    public static string Line(int shown, int total, string thing, string extra = "") =>
        (shown == total ? $"{total:N0} {Plural(total, thing)}" : $"{shown:N0} of {total:N0} {Plural(total, thing)}") + extra;

    private static string Plural(int n, string thing) => n == 1 ? thing : thing + "s";
}

// ---- Songs ----

/// <summary>
/// Every song, with the details as columns: title, time, artist, album, genre, favourite, plays.
/// Filter by name, genre, quality or favourites; sort by any column (its heading or the menu).
/// </summary>
public sealed class SongsPage : Grid
{
    internal enum SortBy { Title, Time, Artist, Album, Genre, Favorite, Plays }

    private static readonly string[] SortNames = { "Title", "Time", "Artist", "Album", "Genre", "Favorites", "Plays" };
    private static readonly string[] Qualities = { "All", "Hi-Res Lossless", "Lossless", "Dolby Atmos", "Lossy" };

    // Kept while the app is open, so the page comes back as it was left.
    private static SortBy sort = SortBy.Title;
    private static bool descending;
    private static string filter = "";
    private static string genreFilter = "";
    private static int quality;
    private static bool favoritesOnly;

    private readonly List<Track> all = MusicLibrary.Current.Songs;
    private readonly SongList list;
    private readonly TextBlock countLine = new() { FontSize = 13, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 14, 0, 0) };
    private readonly Grid headings = new() { Margin = new Thickness(12, 4, 12, 6), Height = 32 };
    private readonly StackPanel playButtons = new();

    public SongsPage(object? _)
    {
        var head = new StackPanel();
        var top = new Grid { Margin = new Thickness(0, 0, 12, 0) };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition());
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.Children.Add(Ui.Title("Songs"));
        countLine.Foreground = Ui.Secondary;
        top.Children.Add(Ui.InColumn(countLine, 1));
        playButtons.VerticalAlignment = VerticalAlignment.Center;
        playButtons.Margin = new Thickness(0, 14, 0, 0);
        top.Children.Add(Ui.InColumn(playButtons, 2));
        head.Children.Add(top);

        var genres = ListToolbar.Genres(all);
        var bar = new ListToolbar(Refresh, filter, v => filter = v.Trim());
        bar.Choice("Genre", genres, () => Math.Max(0, genres.FindIndex(g => string.Equals(g, genreFilter, StringComparison.OrdinalIgnoreCase))),
            i => genreFilter = i == 0 ? "" : genres[i]);
        bar.Choice("Quality", Qualities, () => quality, i => quality = i);
        bar.Choice("Show", new[] { "All songs", "Favorites only" }, () => favoritesOnly ? 1 : 0, i => favoritesOnly = i == 1, "");
        bar.Choice("Sort by", SortNames, () => (int)sort, i =>
        {
            descending = (SortBy)i is SortBy.Plays or SortBy.Favorite;
            sort = (SortBy)i;
        }, "");
        head.Children.Add(bar);

        SongRow.DetailedColumns(headings);
        head.Children.Add(headings);

        list = new SongList(new List<Track>(), RowMode.Detailed) { Header = head };
        Children.Add(list);
        Refresh();
    }

    private void Refresh()
    {
        var shown = Sorted(all.Where(Keep)).ToList();
        list.Replace(shown);
        var hours = shown.Sum(t => t.DurationMs) / 3_600_000.0;
        countLine.Text = Counted.Line(shown.Count, all.Count, "song", " · " + (hours >= 1 ? $"{hours:0} hours" : $"{hours * 60:0} minutes"));
        playButtons.Children.Clear();
        playButtons.Children.Add(Ui.PlayButtons(shown));
        Headings();
    }

    private bool Keep(Track t)
    {
        if (filter.Length > 0 && !(ListToolbar.Matches(t.Title, filter) || ListToolbar.Matches(t.Artist, filter) || ListToolbar.Matches(t.Album, filter))) return false;
        if (genreFilter.Length > 0 && !string.Equals(t.Genre, genreFilter, StringComparison.OrdinalIgnoreCase)) return false;
        if (favoritesOnly && !LibraryStore.IsLiked(t)) return false;
        return quality switch
        {
            1 => t.QualityTier == "Hi-Res Lossless",
            2 => t.QualityTier == "Lossless",
            3 => t.QualityTier == "Dolby Atmos",
            4 => t.QualityTier == null,
            _ => true,
        };
    }

    /// <summary>The column headings: each sorts by its column, again to reverse.</summary>
    private void Headings()
    {
        headings.Children.Clear();
        void Heading(string text, SortBy by, int column, HorizontalAlignment align = HorizontalAlignment.Left)
        {
            var on = sort == by;
            var label = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            label.Children.Add(new TextBlock { Text = text, FontSize = 12, FontWeight = FontWeights.SemiBold, Foreground = on ? Ui.Accent : Ui.Secondary });
            if (on) label.Children.Add(new FontIcon { Glyph = descending ? "" : "", FontSize = 9, Foreground = Ui.Accent });
            var b = new Button
            {
                Content = label,
                Padding = new Thickness(0, 4, 4, 4),
                Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
                BorderThickness = new Thickness(0),
                HorizontalAlignment = align,
                VerticalAlignment = VerticalAlignment.Center,
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, "Sort by " + text);
            b.Click += (_, _) =>
            {
                descending = sort == by ? !descending : by is SortBy.Plays or SortBy.Favorite;
                sort = by;
                // The Sort by menu shows the column too: the page is made again with it.
                MainWindow.Instance.Reload();
            };
            headings.Children.Add(Ui.InColumn(b, column));
        }
        Heading("Title", SortBy.Title, 1);
        Heading("Time", SortBy.Time, 2);
        Heading("Artist", SortBy.Artist, 3);
        Heading("Album", SortBy.Album, 4);
        Heading("Genre", SortBy.Genre, 5);
        Heading("Favorites", SortBy.Favorite, 6, HorizontalAlignment.Center);
        Heading("Plays", SortBy.Plays, 7, HorizontalAlignment.Right);
    }

    private static IEnumerable<Track> Sorted(IEnumerable<Track> songs)
    {
        var c = ListToolbar.Collator;
        IOrderedEnumerable<Track> ordered = sort switch
        {
            SortBy.Time => descending ? songs.OrderByDescending(t => t.DurationMs) : songs.OrderBy(t => t.DurationMs),
            SortBy.Artist => descending ? songs.OrderByDescending(t => t.Artist, c) : songs.OrderBy(t => t.Artist, c),
            SortBy.Album => descending ? songs.OrderByDescending(t => t.Album, c) : songs.OrderBy(t => t.Album, c),
            SortBy.Genre => descending ? songs.OrderByDescending(t => t.Genre, c) : songs.OrderBy(t => t.Genre, c),
            SortBy.Favorite => descending ? songs.OrderByDescending(LibraryStore.IsLiked) : songs.OrderBy(LibraryStore.IsLiked),
            SortBy.Plays => descending ? songs.OrderByDescending(History.PlayCount) : songs.OrderBy(History.PlayCount),
            _ => descending ? songs.OrderByDescending(t => t.Title, c) : songs.OrderBy(t => t.Title, c),
        };
        return ordered.ThenBy(t => t.Title, c);
    }
}

// ---- Pages of tiles ----

/// <summary>
/// A page of tiles that share each row's width (as many columns as fit, each growing to fill its
/// share), with the toolbar and a count under the title.
/// </summary>
internal sealed class TilePage
{
    public readonly StackPanel Head = new();
    public readonly TextBlock CountLine = new() { FontSize = 13, Margin = new Thickness(12, -8, 0, 12) };
    public readonly ItemsRepeater Repeater;
    public readonly ScrollViewer View;

    public TilePage(string title, string template, double minTile)
    {
        CountLine.Foreground = Ui.Secondary;
        Repeater = new ItemsRepeater
        {
            ItemTemplate = (DataTemplate)Application.Current.Resources[template],
            Layout = new UniformGridLayout
            {
                MinItemWidth = minTile,
                MinColumnSpacing = 14,
                MinRowSpacing = 14,
                ItemsStretch = UniformGridLayoutItemsStretch.Fill,
            },
        };
        Head.Children.Add(Ui.Title(title));
        Head.Children.Add(CountLine);
        View = new ScrollViewer { Content = new StackPanel { Padding = new Thickness(20, 0, 20, 24), Children = { Head, Repeater } } };
    }
}

public sealed class AlbumsPage : Grid
{
    private static readonly string[] SortNames = { "Title", "Artist", "Year (newest)", "Most songs", "Recently played" };
    private static readonly string[] Types = { "All", "Albums", "Singles & EPs" };

    private static int sort;
    private static string filter = "";
    private static string genreFilter = "";
    private static int type;

    private readonly List<Album> all = MusicLibrary.Current.Albums;
    private readonly TilePage page = new("Albums", "AlbumFillTemplate", 180);

    public AlbumsPage(object? _)
    {
        var genres = ListToolbar.Genres(MusicLibrary.Current.Songs);
        var bar = new ListToolbar(Refresh, filter, v => filter = v.Trim());
        bar.Choice("Genre", genres, () => Math.Max(0, genres.FindIndex(g => string.Equals(g, genreFilter, StringComparison.OrdinalIgnoreCase))),
            i => genreFilter = i == 0 ? "" : genres[i]);
        bar.Choice("Type", Types, () => type, i => type = i);
        bar.Choice("Sort by", SortNames, () => sort, i => sort = i, "");
        page.Head.Children.Insert(1, bar);
        Children.Add(page.View);
        Refresh();
    }

    /// <summary>
    /// "… - Single" and "… - EP" (as Apple Music names them) are singles and EPs; everything else
    /// is an album. Not by how many songs are here: a soundtrack with only two of its songs in
    /// the library is still an album.
    /// </summary>
    private static bool IsSingle(Album a) =>
        a.Title.EndsWith(" - Single", StringComparison.OrdinalIgnoreCase) || a.Title.EndsWith(" - EP", StringComparison.OrdinalIgnoreCase);

    private void Refresh()
    {
        var c = ListToolbar.Collator;
        var shown = all.Where(a =>
            (filter.Length == 0 || ListToolbar.Matches(a.Title, filter) || ListToolbar.Matches(a.Artist, filter))
            && (genreFilter.Length == 0 || a.Tracks.Any(t => string.Equals(t.Genre, genreFilter, StringComparison.OrdinalIgnoreCase)))
            && (type == 0 || (type == 2) == IsSingle(a)));
        IEnumerable<Album> ordered = sort switch
        {
            1 => shown.OrderBy(a => a.Artist, c).ThenBy(a => a.Title, c),
            2 => shown.OrderByDescending(a => a.Year).ThenBy(a => a.Title, c),
            3 => shown.OrderByDescending(a => a.Tracks.Count).ThenBy(a => a.Title, c),
            4 => RecentlyPlayed(shown.ToList()),
            _ => shown.OrderBy(a => a.Title, c),
        };
        var list = ordered.ToList();
        page.Repeater.ItemsSource = list;
        page.CountLine.Text = Counted.Line(list.Count, all.Count, "album");
    }

    /// <summary>Albums played lately first (most recent listen first), then the rest by title.</summary>
    private static IEnumerable<Album> RecentlyPlayed(List<Album> albums)
    {
        var order = History.Recents(MusicLibrary.Current, 1000).Select(t => t.AlbumKey).Distinct()
            .Select((key, i) => (key, i)).ToDictionary(x => x.key, x => x.i);
        return albums.OrderBy(a => order.GetValueOrDefault(a.Key, int.MaxValue)).ThenBy(a => a.Title, ListToolbar.Collator);
    }
}

public sealed class ArtistsPage : Grid
{
    private static readonly string[] SortNames = { "Name", "Most songs", "Most albums" };

    private static int sort;
    private static string filter = "";
    private static string genreFilter = "";

    private readonly List<Artist> all = MusicLibrary.Current.Artists;
    private readonly TilePage page = new("Artists", "ArtistFillTemplate", 160);

    public ArtistsPage(object? _)
    {
        var genres = ListToolbar.Genres(MusicLibrary.Current.Songs);
        var bar = new ListToolbar(Refresh, filter, v => filter = v.Trim());
        bar.Choice("Genre", genres, () => Math.Max(0, genres.FindIndex(g => string.Equals(g, genreFilter, StringComparison.OrdinalIgnoreCase))),
            i => genreFilter = i == 0 ? "" : genres[i]);
        bar.Choice("Sort by", SortNames, () => sort, i => sort = i, "");
        page.Head.Children.Insert(1, bar);
        Children.Add(page.View);
        Refresh();
    }

    private void Refresh()
    {
        var c = ListToolbar.Collator;
        var shown = all.Where(a =>
            ListToolbar.Matches(a.Name, filter)
            && (genreFilter.Length == 0 || a.Tracks.Any(t => string.Equals(t.Genre, genreFilter, StringComparison.OrdinalIgnoreCase))));
        var list = (sort switch
        {
            1 => shown.OrderByDescending(a => a.Tracks.Count).ThenBy(a => a.Name, c),
            2 => shown.OrderByDescending(a => a.Albums.Count).ThenBy(a => a.Name, c),
            _ => shown.OrderBy(a => a.Name, c),
        }).ToList();
        page.Repeater.ItemsSource = list;
        page.CountLine.Text = Counted.Line(list.Count, all.Count, "artist");
    }
}

/// <summary>Every folder holding songs, as on Home's Folders shelf.</summary>
public sealed class FoldersPage : Grid
{
    private static readonly string[] SortNames = { "Name", "Most songs", "Location" };

    private static int sort;
    private static string filter = "";

    private readonly List<MusicFolder> all = MusicLibrary.Current.Folders;
    private readonly TilePage page = new("Folders", "FolderFillTemplate", 180);

    public FoldersPage(object? _)
    {
        var bar = new ListToolbar(Refresh, filter, v => filter = v.Trim());
        bar.Choice("Sort by", SortNames, () => sort, i => sort = i, "");
        page.Head.Children.Insert(1, bar);
        Children.Add(page.View);
        Refresh();
    }

    private void Refresh()
    {
        var c = ListToolbar.Collator;
        var shown = all.Where(f => ListToolbar.Matches(f.Name, filter) || ListToolbar.Matches(f.Path, filter));
        var list = (sort switch
        {
            1 => shown.OrderByDescending(f => f.Tracks.Count).ThenBy(f => f.Name, c),
            2 => shown.OrderBy(f => f.Path, c),
            _ => shown.OrderBy(f => f.Name, c),
        }).ToList();
        page.Repeater.ItemsSource = list;
        page.CountLine.Text = Counted.Line(list.Count, all.Count, "folder");
    }
}
