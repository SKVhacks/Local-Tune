using LocalTune.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace LocalTune.Views;

/// <summary>Bits every page uses.</summary>
internal static class Ui
{
    public static Brush Secondary => (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
    public static Brush Accent => (Brush)Application.Current.Resources["AccentBrush"];

    public static TextBlock Title(string text) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["TitleTextBlockStyle"],
        Margin = new Thickness(12, 28, 12, 14),
    };

    public static TextBlock Shelf(string text) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"],
        Margin = new Thickness(12, 26, 0, 10),
    };

    /// <summary>
    /// A shelf that scrolls sideways passes the ordinary mouse wheel to the page, so the page
    /// scrolls up and down wherever the pointer is; Shift + wheel (or a sideways scroll) moves
    /// the shelf. Without this, a shelf took the wheel for itself and the page wouldn't move.
    /// </summary>
    public static void PassVerticalWheel(UIElement row)
    {
        row.PointerWheelChanged += (sender, e) =>
        {
            var point = e.GetCurrentPoint(row);
            if (point.Properties.IsHorizontalMouseWheel) return;
            var shift = Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(Windows.System.VirtualKey.Shift)
                .HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
            if (shift) return;
            // The page: the nearest scroller above the shelf's own that scrolls up and down.
            DependencyObject? at = VisualTreeHelper.GetParent(row);
            var passedShelf = false;
            while (at != null)
            {
                if (at is ScrollViewer sv)
                {
                    if (passedShelf && sv.VerticalScrollMode != ScrollMode.Disabled && sv.ScrollableHeight > 0)
                    {
                        sv.ChangeView(null, sv.VerticalOffset - point.Properties.MouseWheelDelta, null);
                        e.Handled = true;
                        return;
                    }
                    passedShelf = true;
                }
                at = VisualTreeHelper.GetParent(at);
            }
        };
    }

    /// <summary>The element, placed in a grid column.</summary>
    public static T InColumn<T>(T e, int column) where T : FrameworkElement
    {
        Microsoft.UI.Xaml.Controls.Grid.SetColumn(e, column);
        return e;
    }

    /// <summary>"1 song", "2 songs".</summary>
    public static string Count(int n, string thing) => n == 1 ? $"1 {thing}" : $"{n} {thing}s";

    public static TextBlock Note(string text) => new() { Text = text, Foreground = Secondary, FontSize = 13, TextWrapping = TextWrapping.Wrap };

    /// <summary>Play and Shuffle.</summary>
    public static StackPanel PlayButtons(List<Track> tracks)
    {
        Button Make(string glyph, string label, Action a)
        {
            var b = new Button
            {
                Content = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    Spacing = 8,
                    Children = { new FontIcon { Glyph = glyph, FontSize = 14, Foreground = Accent }, new TextBlock { Text = label, FontWeight = FontWeights.SemiBold, Foreground = Accent } },
                },
                Padding = new Thickness(18, 7, 20, 7),
                IsEnabled = tracks.Count > 0,
            };
            b.Click += (_, _) => a();
            return b;
        }
        return new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 10,
            Children =
            {
                Make("", "Play", () => Player.Play(tracks, 0, shuffled: false)),
                Make("", "Shuffle", () => Player.Play(tracks, Random.Shared.Next(tracks.Count), shuffled: true)),
            },
        };
    }

    public static GridView Grid(object items, string template, UIElement header) => new()
    {
        ItemsSource = items,
        ItemTemplate = (DataTemplate)Application.Current.Resources[template],
        SelectionMode = ListViewSelectionMode.None,
        IsItemClickEnabled = false,
        Header = header,
        Padding = new Thickness(20, 0, 20, 24),
    };
}

public sealed class AlbumPage : Grid
{
    public AlbumPage(object? key)
    {
        var library = MusicLibrary.Current;
        var album = library.Album(key as string ?? "");
        if (album == null) { Children.Add(Ui.Title("This isn't in your library any more.")); return; }

        var head = new Grid { Margin = new Thickness(12, 28, 12, 20), ColumnSpacing = 28 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.Children.Add(new CoverView { Track = album.CoverTrack, Size = 230, CornerRadius = new CornerRadius(10) });
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Spacing = 2 };
        words.Children.Add(new TextBlock { Text = album.Title, FontSize = 30, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap, MaxLines = 2 });
        var artist = new HyperlinkButton { Content = new TextBlock { Text = album.Artist, FontSize = 22 }, Padding = new Thickness(0), Foreground = Ui.Accent };
        artist.Click += (_, _) => MainWindow.Instance.Navigate(typeof(ArtistPage), album.Artist);
        words.Children.Add(artist);
        var minutes = album.Tracks.Sum(t => t.DurationMs) / 60_000;
        var meta = new[] { album.Year > 0 ? album.Year.ToString() : null, Ui.Count(album.Tracks.Count, "song"), $"{minutes} min" }.Where(s => s != null);
        words.Children.Add(new TextBlock { Text = string.Join(" · ", meta), Foreground = Ui.Secondary, FontSize = 13, Margin = new Thickness(0, 4, 0, 16) });
        words.Children.Add(Ui.PlayButtons(album.Tracks));
        SetColumn(words, 1);
        head.Children.Add(words);

        // More by each of the album's artists, one shelf apiece.
        var more = new StackPanel { Margin = new Thickness(12, 0, 12, 0) };
        foreach (var name in album.Tracks.SelectMany(t => t.Artists).DistinctBy(ArtistNames.Key))
        {
            var others = library.Artist(name)?.Albums.Where(a => a.Key != album.Key).ToList();
            if (others is { Count: > 0 }) more.Children.Add(Shelves.Albums($"More by {name}", others));
        }
        Children.Add(new SongList(album.Tracks, RowMode.Number) { Header = head, Footer = more });
    }
}

public sealed class ArtistPage : Grid
{
    private static async Task ShowPicture(CoverView face, Artist artist)
    {
        var picture = await ArtistImages.LoadAsync(artist, 360);
        if (picture != null) face.ShowPicture(picture);
    }

    public ArtistPage(object? name)
    {
        var artist = MusicLibrary.Current.Artist(name as string ?? "");
        if (artist == null) { Children.Add(Ui.Title("This isn't in your library any more.")); return; }

        var head = new StackPanel();
        var top = new Grid { Margin = new Thickness(12, 28, 12, 8), ColumnSpacing = 28 };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var face = new CoverView { Track = artist.CoverTrack, Size = 180, CornerRadius = new CornerRadius(90) };
        top.Children.Add(face);
        // Their own picture (from their songs, or Deezer) in place of the album cover, once found.
        _ = ShowPicture(face, artist);
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 4 };
        words.Children.Add(new TextBlock { Text = artist.Name, FontSize = 40, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap });
        words.Children.Add(new TextBlock { Text = Ui.Count(artist.Tracks.Count, "song"), Foreground = Ui.Secondary, FontSize = 13, Margin = new Thickness(0, 0, 0, 12) });
        words.Children.Add(Ui.PlayButtons(artist.Tracks));
        SetColumn(words, 1);
        top.Children.Add(words);
        head.Children.Add(top);
        if (artist.Albums.Count > 0)
            head.Children.Add(new Border { Margin = new Thickness(12, 0, 12, 0), Child = Shelves.Albums("Albums", artist.Albums) });
        head.Children.Add(Ui.Shelf("Songs"));
        Children.Add(new SongList(artist.Tracks) { Header = head });
    }
}

public sealed class SearchPage : Grid
{
    public SearchPage(object? query)
    {
        var q = (query as string ?? "").Trim();
        var library = MusicLibrary.Current;
        bool Has(string s) => s.Contains(q, StringComparison.CurrentCultureIgnoreCase);
        var songs = library.Songs.Where(t => Has(t.Title) || Has(t.Artist) || Has(t.Album)).Take(200).ToList();
        var albums = library.Albums.Where(a => Has(a.Title) || Has(a.Artist)).Take(30).ToList();
        var artists = library.Artists.Where(a => Has(a.Name)).Take(30).ToList();

        var head = new StackPanel();
        head.Children.Add(Ui.Title($"Results for “{q}”"));
        if (artists.Count > 0)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 18 };
            foreach (var a in artists) row.Children.Add(new ArtistTile { Artist = a, TileSize = 120 });
            Ui.PassVerticalWheel(row);
            head.Children.Add(Ui.Shelf("Artists"));
            head.Children.Add(new ScrollViewer
            {
                Content = row,
                Margin = new Thickness(12, 0, 12, 0),
                HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden,
                HorizontalScrollMode = ScrollMode.Enabled,
                VerticalScrollMode = ScrollMode.Disabled,
                VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            });
        }
        if (albums.Count > 0) head.Children.Add(new Border { Margin = new Thickness(12, 0, 12, 0), Child = Shelves.Albums("Albums", albums) });
        if (songs.Count > 0) head.Children.Add(Ui.Shelf("Songs"));
        if (songs.Count + albums.Count + artists.Count == 0) head.Children.Add(new TextBlock { Text = "Nothing found", Foreground = Ui.Secondary, Margin = new Thickness(12, 0, 0, 0) });
        Children.Add(new SongList(songs) { Header = head });
    }
}

/// <summary>The first start: no folders chosen yet.</summary>
public sealed class WelcomePage : Grid
{
    public WelcomePage(object? _)
    {
        var button = new Button
        {
            Content = "Choose music folder",
            Style = (Style)Application.Current.Resources["AccentButtonStyle"],
            HorizontalAlignment = HorizontalAlignment.Center,
            Padding = new Thickness(20, 8, 20, 8),
        };
        button.Click += async (_, _) => await SettingsPage.AddFolderAsync();
        Children.Add(new StackPanel
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Spacing = 10,
            Children =
            {
                new TextBlock { Text = "Welcome to LocalTune", FontSize = 30, FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center },
                new TextBlock { Text = "Choose the folder your music is in. Lyric files and covers beside the songs are found too.", Foreground = Ui.Secondary, HorizontalAlignment = HorizontalAlignment.Center },
                new Border { Height = 10 },
                button,
            },
        });
    }
}

public sealed class SettingsPage : Grid
{
    private const string DeveloperPortfolio = "https://github.com/SKVhacks";
    private const string SourceUrl = "https://github.com/SKVhacks/Local-Tune";
    private const string UpstreamUrl = "https://github.com/justshivv/opentune";

    public SettingsPage(object? _)
    {
        var page = new StackPanel { Padding = new Thickness(32, 0, 32, 32), MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Left };
        page.Children.Add(new TextBlock { Text = "Settings", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], Margin = new Thickness(0, 28, 0, 8) });

        // ---- Music folders ----
        page.Children.Add(Section("Music folders"));
        var folders = new StackPanel { Spacing = 4 };
        foreach (var folder in Settings.Value.Folders)
        {
            var remove = new Button { Content = new FontIcon { Glyph = "", FontSize = 12 }, Style = (Style)Application.Current.Resources["IconButton"], Width = 32, Height = 32 };
            ToolTipService.SetToolTip(remove, "Remove");
            remove.Click += (_, _) =>
            {
                Settings.Update(s => s.Folders.Remove(folder));
                MusicLibrary.Rescan();
                MainWindow.Instance.Reload();
            };
            folders.Children.Add(Card(new Grid
            {
                ColumnSpacing = 12,
                ColumnDefinitions = { new ColumnDefinition { Width = GridLength.Auto }, new ColumnDefinition(), new ColumnDefinition { Width = GridLength.Auto } },
                Children =
                {
                    new FontIcon { Glyph = "", Foreground = Ui.Accent },
                    Col(new TextBlock { Text = folder, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center }, 1),
                    Col(remove, 2),
                },
            }));
        }
        if (Settings.Value.Folders.Count == 0) folders.Children.Add(Ui.Note("No folders yet."));
        page.Children.Add(folders);
        var add = new Button { Content = "Add a folder…", Margin = new Thickness(0, 10, 10, 0) };
        add.Click += async (_, _) => await AddFolderAsync();
        var rescan = new Button { Content = "Look again", Margin = new Thickness(0, 10, 0, 0), IsEnabled = !MusicLibrary.Scanning };
        rescan.Click += (_, _) => { LocalTune.Lyrics.LyricFiles.Forget(); MusicLibrary.Rescan(); };
        page.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Children = { add, rescan } });
        var lib = MusicLibrary.Current;
        page.Children.Add(new TextBlock
        {
            Text = MusicLibrary.Scanning ? "Reading your music…" : $"{Ui.Count(lib.Songs.Count, "song")} · {Ui.Count(lib.Albums.Count, "album")} · {Ui.Count(lib.Artists.Count, "artist")}",
            Foreground = Ui.Secondary,
            FontSize = 13,
            Margin = new Thickness(0, 10, 0, 0),
        });

        // ---- Backup: the same file on the phone and on any PC ----
        page.Children.Add(Section("Backup (phone and PC)"));
        var import = new Button { Content = "Import a backup…" };
        import.Click += async (_, _) => await ImportBackupAsync();
        var export = new Button { Content = "Export a backup…" };
        export.Click += async (_, _) => await ExportBackupAsync();
        page.Children.Add(Card(new StackPanel
        {
            Spacing = 10,
            Children =
            {
                Ui.Note("A backup holds your playlists, liked songs and listening history in one localtune-backup-….json file. It's the LocalTune phone app's own format: export here and import it on the phone (Settings › Music and storage › Backup › Import) or in LocalTune on another PC; export on the phone and import it here. Songs are matched to each device's own files by title and artist. Importing adds to what's here, and the same file twice adds nothing twice."),
                new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { import, export } },
            },
        }));

        // ---- Artist pictures ----
        page.Children.Add(Section("Artist pictures"));
        var online = new ToggleSwitch { Header = "Fetch artist pictures from the internet", IsOn = Settings.Value.OnlineArtistPictures };
        online.Toggled += (_, _) =>
        {
            Settings.Update(st => st.OnlineArtistPictures = online.IsOn);
            ArtistImages.Forget();
        };
        page.Children.Add(Card(new StackPanel
        {
            Children =
            {
                online,
                Ui.Note("An artist's picture comes from their songs' own tags when one is there. Otherwise, with this on, it's looked up on Deezer: only the artist's name is sent, once per artist."),
            },
        }));

        // ---- Storage ----
        page.Children.Add(Section("Storage"));
        page.Children.Add(StorageCard());

        // ---- Your data ----
        page.Children.Add(Section("Your data"));
        var clear = new Button { Content = "Clear my data…", Margin = new Thickness(0, 10, 0, 0) };
        clear.Click += async (_, _) =>
        {
            if (!await PlaylistMenu.Confirm("Clear your data?",
                    "Your playlists, liked songs and listening history (and so Replay and recommendations) will be removed from LocalTune on this PC. Your songs and music folders aren't touched. Export a backup first if you might want them back.",
                    "Clear")) return;
            History.Clear();
            LibraryStore.Clear();
            MainWindow.Instance.ShowToast("Cleared your playlists, liked songs and listening history.");
            MainWindow.Instance.Reload();
        };
        page.Children.Add(Card(new StackPanel
        {
            Children =
            {
                Ui.Note("Removes LocalTune's own data on this PC: playlists, liked songs and listening history. Your music files and folders stay exactly as they are."),
                clear,
            },
        }));

        // ---- Lyrics ----
        page.Children.Add(Section("Lyrics"));
        var size = new Slider { Minimum = 0.7, Maximum = 1.5, StepFrequency = 0.05, Value = Settings.Value.LyricsScale, Header = "Text size", MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left, Width = 420 };
        size.ValueChanged += (_, e) => Settings.Update(s => s.LyricsScale = Math.Round(e.NewValue, 2));
        page.Children.Add(Card(new StackPanel
        {
            Spacing = 8,
            Children =
            {
                size,
                Ui.Note("Lyric files are found beside each song, with the same name: Song.ttml, Song.lrc or Song.txt, in the song's folder or a Lyrics folder inside it."),
            },
        }));

        // ---- About ----
        page.Children.Add(Section("About"));
        var version = typeof(SettingsPage).Assembly.GetName().Version;
        page.Children.Add(Card(new StackPanel
        {
            Children =
            {
                new TextBlock { Text = "LocalTune for Windows", FontWeight = FontWeights.SemiBold },
                Ui.Note($"Version {version?.ToString(3)}"),
                Link("Made by SKVhacks", DeveloperPortfolio),
                Link("Source code", SourceUrl),
                Link("Based on OpenTune by justshivv (GPL-3.0)", UpstreamUrl),
            },
        }));

        Children.Add(new ScrollViewer { Content = page });
    }

    private static FrameworkElement Col(FrameworkElement e, int c)
    {
        SetColumn(e, c);
        return e;
    }

    /// <summary>
    /// How much space things take: the music and its lyric files (read, never written), and
    /// LocalTune's own folder, item by item. Worked out in the background when Settings opens.
    /// </summary>
    private static Border StorageCard()
    {
        var rows = new Grid { ColumnSpacing = 16, RowSpacing = 6 };
        rows.ColumnDefinitions.Add(new ColumnDefinition());
        rows.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var values = new Dictionary<string, TextBlock>();
        void Row(string key, string label, bool strong = false)
        {
            var r = rows.RowDefinitions.Count;
            rows.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var top = strong && r > 0 ? 12 : 0;
            var weight = strong ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal;
            var name = new TextBlock { Text = label, Margin = new Thickness(strong ? 0 : 16, top, 0, 0), FontWeight = weight, TextWrapping = TextWrapping.Wrap };
            var value = new TextBlock { Text = "…", HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, top, 0, 0), FontWeight = weight };
            if (!strong) { name.Foreground = Ui.Secondary; value.Foreground = Ui.Secondary; }
            Microsoft.UI.Xaml.Controls.Grid.SetRow(name, r);
            Microsoft.UI.Xaml.Controls.Grid.SetRow(value, r);
            Microsoft.UI.Xaml.Controls.Grid.SetColumn(value, 1);
            rows.Children.Add(name);
            rows.Children.Add(value);
            values[key] = value;
        }
        Row("music", "Your music", strong: true);
        Row("songs", "Songs");
        Row("lyrics", "Lyric files");
        Row("app", "LocalTune's data on this PC", strong: true);
        Row("pictures", "Artist pictures (cache: fetched again when needed)");
        Row("index", "Library index (cache: rebuilt by Rescan)");
        Row("history", "Listening history");
        Row("store", "Playlists and liked songs");
        Row("settings", "Settings");

        var open = new Button { Content = "Open LocalTune's folder", Margin = new Thickness(0, 14, 0, 0) };
        open.Click += (_, _) => System.Diagnostics.Process.Start("explorer.exe", AppDir.Root);
        var card = Card(new StackPanel { Children = { rows, open } });

        var songs = MusicLibrary.Current.Songs.Select(t => t.Path).ToList();
        var ui = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
        _ = Task.Run(() =>
        {
            static long SizeOf(string file) { try { return new FileInfo(file).Length; } catch { return 0; } }
            static (int Files, long Bytes) Folder(string dir)
            {
                try
                {
                    var files = new DirectoryInfo(dir).EnumerateFiles("*", SearchOption.AllDirectories).ToList();
                    return (files.Count, files.Sum(f => f.Length));
                }
                catch { return (0, 0); }
            }
            var music = songs.Sum(SizeOf);
            var lyricFiles = songs
                .SelectMany(p =>
                {
                    var dir = Path.GetDirectoryName(p) ?? "";
                    var name = Path.GetFileNameWithoutExtension(p);
                    return new[] { dir, Path.Combine(dir, "Lyrics") }
                        .SelectMany(d => new[] { "ttml", "lrc", "elrc", "txt" }.Select(e => Path.Combine(d, name + "." + e)));
                })
                .Distinct(StringComparer.OrdinalIgnoreCase).Where(File.Exists).ToList();
            var lyrics = lyricFiles.Sum(SizeOf);
            var all = Folder(AppDir.Root);
            var pictures = Folder(Path.Combine(AppDir.Root, "artist-pictures"));
            long Own(string n) => SizeOf(Path.Combine(AppDir.Root, n));
            var text = new Dictionary<string, string>
            {
                ["music"] = Bytes(music + lyrics),
                ["songs"] = $"{Ui.Count(songs.Count, "song")} · {Bytes(music)}",
                ["lyrics"] = $"{Ui.Count(lyricFiles.Count, "file")} · {Bytes(lyrics)}",
                ["app"] = Bytes(all.Bytes),
                ["pictures"] = $"{Ui.Count(pictures.Files, "picture")} · {Bytes(pictures.Bytes)}",
                ["index"] = Bytes(Own("library.json")),
                ["history"] = Bytes(Own("history.json")),
                ["store"] = Bytes(Own("library-store.json")),
                ["settings"] = Bytes(Own("settings.json")),
            };
            ui.TryEnqueue(() => { foreach (var (k, v) in text) values[k].Text = v; });
        });
        return card;
    }

    /// <summary>"616 KB", "129 MB", "38.2 GB".</summary>
    private static string Bytes(long n) => n switch
    {
        < 1024 => $"{n} bytes",
        < 1024 * 1024 => $"{n / 1024.0:0} KB",
        < 1024L * 1024 * 1024 => $"{n / 1048576.0:0.#} MB",
        _ => $"{n / 1073741824.0:0.0#} GB",
    };

    private static TextBlock Section(string text) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"],
        Margin = new Thickness(0, 28, 0, 8),
    };

    private static Border Card(UIElement child) => new()
    {
        Child = child,
        Padding = new Thickness(16, 12, 16, 12),
        CornerRadius = new CornerRadius(8),
        Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
        BorderThickness = new Thickness(1),
    };

    private static HyperlinkButton Link(string text, string url) => new()
    {
        Content = text,
        NavigateUri = new Uri(url),
        Padding = new Thickness(0, 6, 0, 6),
    };

    /// <summary>Asks for a phone backup with Windows' own picker and brings its playlists, likes and history in.</summary>
    public static async Task ImportBackupAsync()
    {
        var picker = new Windows.Storage.Pickers.FileOpenPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.Downloads };
        picker.FileTypeFilter.Add(".json");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(MainWindow.Instance));
        var file = await picker.PickSingleFileAsync();
        if (file == null) return;
        try
        {
            // Read in the background; added here, on the UI thread, so the pages can show it.
            var parts = await Task.Run(() => Backup.Read(file.Path));
            var r = Backup.Apply(parts);
            MainWindow.Instance.ShowToast($"Imported {Ui.Count(r.Playlists, "playlist")}, {Ui.Count(r.Liked, "liked song")} and {Ui.Count(r.Listens, "listen")}.");
            MainWindow.Instance.Reload();
        }
        catch (InvalidDataException e) { MainWindow.Instance.ShowToast("Import failed: " + e.Message); }
        catch (Exception e) { MainWindow.Instance.ShowToast("Import failed: " + e.Message); }
    }

    /// <summary>Writes a backup where the listener chooses, ready for the phone or another PC.</summary>
    public static async Task ExportBackupAsync()
    {
        var picker = new Windows.Storage.Pickers.FileSavePicker
        {
            SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.Downloads,
            SuggestedFileName = Backup.SuggestedName(),
        };
        picker.FileTypeChoices.Add("LocalTune backup", new List<string> { ".json" });
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(MainWindow.Instance));
        var file = await picker.PickSaveFileAsync();
        if (file == null) return;
        try
        {
            History.Save();
            var path = file.Path;
            await Task.Run(() => Backup.Write(path));
            MainWindow.Instance.ShowToast($"Exported {Ui.Count(LibraryStore.Playlists.Count, "playlist")}, {Ui.Count(LibraryStore.Doc.Liked.Count, "liked song")} and {Ui.Count(History.Records.Count, "listen")}.");
        }
        catch (Exception e) { MainWindow.Instance.ShowToast("Export failed: " + e.Message); }
    }

    /// <summary>Asks for a folder with Windows' own picker and adds it to the library.</summary>
    public static async Task AddFolderAsync()
    {
        var picker = new Windows.Storage.Pickers.FolderPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.MusicLibrary };
        picker.FileTypeFilter.Add("*");
        // A plain desktop app has to tell the picker which window it belongs to.
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(MainWindow.Instance));
        var folder = await picker.PickSingleFolderAsync();
        if (folder == null) return;
        if (!Settings.Value.Folders.Contains(folder.Path, StringComparer.OrdinalIgnoreCase))
            Settings.Update(s => s.Folders.Add(folder.Path));
        MusicLibrary.Rescan();
        MainWindow.Instance.Reload();
    }
}
