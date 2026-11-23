using LocalTune.Core;
using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace LocalTune.Views;

// ---- Playlists: the "Add to playlist" menu and the name dialogs ----

public static class PlaylistMenu
{
    /// <summary>"Add to playlist ›" with "New playlist…" and every playlist.</summary>
    /// <summary>"Add to playlist" as a menu of its own, for a button: New playlist… and every playlist.</summary>
    public static void Show(FrameworkElement target, IReadOnlyList<Track> tracks)
    {
        var sub = AddTo(tracks);
        var items = sub.Items.ToList();
        sub.Items.Clear();
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.Top };
        foreach (var item in items) menu.Items.Add(item);
        menu.ShowAt(target);
    }

    public static MenuFlyoutSubItem AddTo(IReadOnlyList<Track> tracks)
    {
        var sub = new MenuFlyoutSubItem { Text = "Add to playlist", Icon = new FontIcon { Glyph = "" } };
        var create = new MenuFlyoutItem { Text = "New playlist…", Icon = new FontIcon { Glyph = "" } };
        create.Click += async (_, _) =>
        {
            var name = await AskName("New playlist", "Create", "");
            if (name == null) return;
            var p = LibraryStore.Create(name, tracks);
            MainWindow.Instance.ShowToast($"Made “{p.Name}”");
        };
        sub.Items.Add(create);
        if (LibraryStore.Playlists.Count > 0) sub.Items.Add(new MenuFlyoutSeparator());
        foreach (var p in LibraryStore.Playlists)
        {
            var item = new MenuFlyoutItem { Text = p.Name };
            item.Click += (_, _) =>
            {
                var added = LibraryStore.Add(p.Id, tracks);
                MainWindow.Instance.ShowToast(added > 0 ? $"Added to “{p.Name}”" : $"Already in “{p.Name}”");
            };
            sub.Items.Add(item);
        }
        return sub;
    }

    /// <summary>Asks for a playlist's name; null when cancelled.</summary>
    public static async Task<string?> AskName(string title, string action, string current)
    {
        var box = new TextBox { Text = current, PlaceholderText = "Playlist name", MinWidth = 320 };
        box.SelectAll();
        var dialog = new ContentDialog
        {
            Title = title,
            Content = box,
            PrimaryButtonText = action,
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = MainWindow.Instance.Content.XamlRoot,
        };
        // Enter in the box counts as pressing the button.
        var entered = false;
        box.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            entered = true;
            dialog.Hide();
        };
        var result = await dialog.ShowAsync();
        var name = box.Text.Trim();
        return (result == ContentDialogResult.Primary || entered) && name.Length > 0 ? name : null;
    }

    public static async Task<bool> Confirm(string title, string text, string action)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = text,
            PrimaryButtonText = action,
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = MainWindow.Instance.Content.XamlRoot,
        };
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
}

// ---- Play on hover ----

/// <summary>
/// The play button that shows on a cover while the pointer is over its tile, on Home.
/// </summary>
public static class HoverPlay
{
    private static readonly ScalarTransition Fade = new() { Duration = TimeSpan.FromMilliseconds(150) };

    /// <summary>
    /// A round play button at the cover's bottom left (playlists, albums, folders): pressing it
    /// plays at once; the rest of the tile still opens the page.
    /// </summary>
    public static void Corner(Button tile, Panel cover, Func<List<Track>> tracks)
    {
        var play = new Button
        {
            Content = new FontIcon { Glyph = "\uF5B0", FontSize = 14 },
            Style = (Style)Application.Current.Resources["AccentButtonStyle"],
            Width = 40,
            Height = 40,
            Padding = new Thickness(0),
            CornerRadius = new CornerRadius(20),
            Margin = new Thickness(10),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Bottom,
            Opacity = 0,
            OpacityTransition = Fade,
        };
        ToolTipService.SetToolTip(play, "Play");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(play, "Play");
        play.Click += (_, _) =>
        {
            var list = tracks();
            if (list.Count > 0) Player.Play(list, 0, shuffled: false);
        };
        cover.Children.Add(play);
        Show(tile, play);
    }

    /// <summary>A play mark in the middle of a song's cover: the tile itself plays it.</summary>
    public static void Center(Button tile, Panel cover)
    {
        var mark = new Border
        {
            Width = 52,
            Height = 52,
            CornerRadius = new CornerRadius(26),
            Background = new SolidColorBrush(Color.FromArgb(150, 0, 0, 0)),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            IsHitTestVisible = false,
            Opacity = 0,
            OpacityTransition = Fade,
            Child = new FontIcon { Glyph = "\uF5B0", FontSize = 22, Foreground = new SolidColorBrush(Colors.White), Margin = new Thickness(3, 0, 0, 0) },
        };
        cover.Children.Add(mark);
        Show(tile, mark);
    }

    private static void Show(Button tile, UIElement overlay)
    {
        tile.PointerEntered += (_, _) => overlay.Opacity = 1;
        tile.PointerExited += (_, _) => overlay.Opacity = 0;
        tile.PointerCanceled += (_, _) => overlay.Opacity = 0;
    }
}

// ---- Tiles for Home's shelves ----

/// <summary>A song as a tile: cover, title, artist. Plays it with the rest of its shelf after it.</summary>
public sealed class SongTile : Button
{
    public SongTile(Track t, IReadOnlyList<Track> shelf, int index, double size = 160)
    {
        Style = (Style)Application.Current.Resources["TileButton"];
        Width = size + 12;
        var title = new TextBlock { Text = t.Title, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 8, 0, 0) };
        var artist = new TextBlock { Text = t.Artist, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis, Foreground = Ui.Secondary };
        var cover = new CoverView { Track = t, Size = size, CornerRadius = new CornerRadius(8) };
        Content = new StackPanel { Children = { cover, title, artist } };
        HoverPlay.Center(this, cover);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, $"{t.Title}, {t.Artist}");
        Click += (_, _) => Player.Play(shelf.ToList(), index, shuffled: false);
        ContextRequested += (_, e) =>
        {
            SongMenu.Show(this, t, shelf, e.TryGetPosition(this, out var at) ? at : null);
            e.Handled = true;
        };
    }
}

/// <summary>A playlist: its first song's cover (or a note while it's empty), its name and size.</summary>
public sealed class PlaylistTile : Button
{
    public PlaylistTile(LocalPlaylist p, Library lib, double size = 160)
    {
        Style = (Style)Application.Current.Resources["TileButton"];
        Width = size + 12;
        var tracks = p.Tracks(lib);
        var cover = new CoverView { Track = tracks.FirstOrDefault(), Size = size, CornerRadius = new CornerRadius(8) };
        Content = new StackPanel
        {
            Children =
            {
                cover,
                new TextBlock { Text = p.Name, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 8, 0, 0) },
                new TextBlock { Text = Ui.Count(tracks.Count, "song"), FontSize = 13, Foreground = Ui.Secondary },
            },
        };
        HoverPlay.Corner(this, cover, () => p.Tracks(MusicLibrary.Current));
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, p.Name);
        Click += (_, _) => MainWindow.Instance.Navigate(typeof(PlaylistPage), p.Id);
    }
}

/// <summary>Liked songs, first among the playlists: a heart on the accent.</summary>
public sealed class LikedTile : Button
{
    public LikedTile(int count, double size = 160)
    {
        Style = (Style)Application.Current.Resources["TileButton"];
        Width = size + 12;
        var face = new Grid
        {
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(8),
            Background = new LinearGradientBrush
            {
                StartPoint = new Windows.Foundation.Point(0, 0),
                EndPoint = new Windows.Foundation.Point(1, 1),
                GradientStops =
                {
                    new GradientStop { Color = Color.FromArgb(255, 0xFA, 0x2D, 0x48), Offset = 0 },
                    new GradientStop { Color = Color.FromArgb(255, 0x8E, 0x1B, 0x6B), Offset = 1 },
                },
            },
            Children = { new FontIcon { Glyph = "\uEB52", FontSize = 52, Foreground = new SolidColorBrush(Colors.White) } },
        };
        Content = new StackPanel
        {
            Children =
            {
                face,
                new TextBlock { Text = "Liked songs", Margin = new Thickness(0, 8, 0, 0) },
                new TextBlock { Text = Ui.Count(count, "song"), FontSize = 13, Foreground = Ui.Secondary },
            },
        };
        HoverPlay.Corner(this, face, () => LibraryStore.LikedTracks(MusicLibrary.Current));
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, "Liked songs");
        Click += (_, _) => MainWindow.Instance.Navigate(typeof(LikedPage));
    }
}

/// <summary>A tile that does something: a big glyph in place of a cover ("New playlist").</summary>
public sealed class ActionTile : Button
{
    public ActionTile(string glyph, string label, Action onClick, double size = 160)
    {
        Style = (Style)Application.Current.Resources["TileButton"];
        Width = size + 12;
        var face = new Grid
        {
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(8),
            // A card with an outline: visible on light Mica as on dark.
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
            BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
            BorderThickness = new Thickness(1),
            Children = { new FontIcon { Glyph = glyph, FontSize = 36, Foreground = Ui.Accent } },
        };
        Content = new StackPanel { Children = { face, new TextBlock { Text = label, Margin = new Thickness(0, 8, 0, 0) } } };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, label);
        Click += (_, _) => onClick();
    }
}

/// <summary>A folder: its first song's cover, its name and how many songs.</summary>
public sealed class FolderTile : Button
{
    private readonly CoverView cover = new() { Size = 160, CornerRadius = new CornerRadius(8) };
    private readonly TextBlock name = new() { TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 8, 0, 0) };
    private readonly TextBlock count = new() { FontSize = 13 };

    public FolderTile()
    {
        Style = (Style)Application.Current.Resources["TileButton"];
        Width = 172;
        count.Foreground = Ui.Secondary;
        Content = new StackPanel { Children = { cover, name, count } };
        Click += (_, _) => { if (Folder is { } f) MainWindow.Instance.Navigate(typeof(FolderPage), f.Path); };
        SizeChanged += (_, _) =>
        {
            if (!Fill || ActualWidth <= 0) return;
            var side = Math.Floor(ActualWidth - 12);
            if (side > 0 && Math.Abs(cover.Size - side) > 0.5) cover.Size = side;
        };
    }

    /// <summary>Home's shelf: a fixed size, with the hover play button.</summary>
    public FolderTile(MusicFolder f, double size = 160) : this()
    {
        Folder = f;
        cover.Size = size;
        Width = size + 12;
        HoverPlay.Corner(this, cover, () => Folder?.Tracks ?? new List<Track>());
    }

    public static readonly DependencyProperty FolderProperty =
        DependencyProperty.Register(nameof(Folder), typeof(MusicFolder), typeof(FolderTile), new PropertyMetadata(null, (d, _) => ((FolderTile)d).Show()));

    public MusicFolder? Folder
    {
        get => (MusicFolder?)GetValue(FolderProperty);
        set => SetValue(FolderProperty, value);
    }

    public static readonly DependencyProperty FillProperty =
        DependencyProperty.Register(nameof(Fill), typeof(bool), typeof(FolderTile), new PropertyMetadata(false, (d, _) =>
        {
            var t = (FolderTile)d;
            if (!t.Fill) return;
            t.Width = double.NaN;
            t.HorizontalAlignment = HorizontalAlignment.Stretch;
            t.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        }));

    /// <summary>Grows and shrinks to share its row (the Folders page).</summary>
    public bool Fill
    {
        get => (bool)GetValue(FillProperty);
        set => SetValue(FillProperty, value);
    }

    private void Show()
    {
        var f = Folder;
        cover.Track = f?.Tracks.FirstOrDefault();
        name.Text = f?.Name ?? "";
        count.Text = f == null ? "" : Ui.Count(f.Tracks.Count, "song");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, f?.Name ?? "");
        ToolTipService.SetToolTip(this, f?.Path);
    }
}

// ---- Home ----

/// <summary>
/// Home: Recommended (from what's been played), Recently played, Playlists, Albums, Folders, and
/// the way into Replay; as the phone's Home, from this PC's own library and history.
/// </summary>
public sealed class HomePage : Grid
{
    private const int ShelfMax = 20;

    /// <summary>Chosen when the app starts: the suggested albums stay put until it's opened again.</summary>
    private static readonly int SessionSeed = Random.Shared.Next();

    private readonly Border recommendedSlot = new();
    private readonly Border recentSlot = new();

    public HomePage(object? _)
    {
        var lib = MusicLibrary.Current;
        var page = new StackPanel { Padding = new Thickness(20, 0, 20, 32) };
        page.Children.Add(Ui.Title(Greeting()));

        // What's been played drives these two; they're filled again as each new listen counts.
        page.Children.Add(recommendedSlot);
        page.Children.Add(recentSlot);
        FillListening();
        Loaded += (_, _) => History.Changed += FillListening;
        Unloaded += (_, _) => History.Changed -= FillListening;

        // Playlists: Liked songs first, then the ones made here or brought from the phone, then a new one.
        var playlistRow = new List<UIElement> { new LikedTile(LibraryStore.LikedTracks(lib).Count) };
        foreach (var p in LibraryStore.Playlists.Take(ShelfMax)) playlistRow.Add(new PlaylistTile(p, lib));
        playlistRow.Add(new ActionTile("\uE710", "New playlist", async () =>
        {
            var name = await PlaylistMenu.AskName("New playlist", "Create", "");
            if (name == null) return;
            var p = LibraryStore.Create(name);
            MainWindow.Instance.Navigate(typeof(PlaylistPage), p.Id);
        }));
        page.Children.Add(Shelf("Playlists", playlistRow));

        // Suggested albums, right under Recommended (the user's choice): up to fifteen, picked at random once per run of the app: the same
        // each time Home is opened, a new pick when LocalTune is closed and opened again.
        var pick = new Random(SessionSeed);
        var albums = lib.Albums.OrderBy(_ => pick.Next()).Take(15).Select(a =>
        {
            var tile = new AlbumTile { Album = a, TileSize = 160 };
            tile.AddHoverPlay();
            return (UIElement)tile;
        }).ToList();
        if (albums.Count > 0) page.Children.Insert(page.Children.IndexOf(recommendedSlot) + 1, Shelf("Suggested albums", albums, () => MainWindow.Instance.Navigate(typeof(AlbumsPage))));


        // Artists: those with the most songs here first, with their own pictures where found.
        var artists = lib.Artists.OrderByDescending(a => a.Tracks.Count).ThenBy(a => a.Name).Take(ShelfMax)
            .Select(a => (UIElement)new ArtistTile { Artist = a, TileSize = 150 }).ToList();
        if (artists.Count > 0) page.Children.Add(Shelf("Artists", artists, () => MainWindow.Instance.Navigate(typeof(ArtistsPage))));

        var folders = lib.Folders.Take(ShelfMax).Select(f => (UIElement)new FolderTile(f)).ToList();
        if (folders.Count > 0) page.Children.Add(Shelf("Folders", folders, () => MainWindow.Instance.Navigate(typeof(FoldersPage))));

        page.Children.Add(ReplayCard());
        Children.Add(new ScrollViewer { Content = page });
    }

    /// <summary>Recommended and Recently played, from the listening history as it is now.</summary>
    private List<Track>? shownRecommended, shownRecent;
    private int swapId;

    /// <summary>
    /// Recommended and Recently played, filled again as each listen counts. The first time they
    /// simply appear; after that a shelf whose songs changed swaps them with a soft animation:
    /// the old tiles fade and drift left, then the new ones rise in one after another.
    /// </summary>
    private void FillListening()
    {
        var lib = MusicLibrary.Current;
        var recommended = Suggestions.Recommended(lib);
        var recent = History.Recents(lib, ShelfMax);
        var first = shownRecommended == null;
        var id = ++swapId;
        if (first || !Same(shownRecommended!, recommended))
            Swap(recommendedSlot, recommended.Count > 0 ? SongShelf("Recommended songs for you", recommended) : null, animate: !first, id);
        if (first || !Same(shownRecent!, recent))
            Swap(recentSlot, recent.Count > 0 ? SongShelf("Recently played", recent) : null, animate: !first, id);
        shownRecommended = recommended;
        shownRecent = recent;
    }

    private static bool Same(List<Track> a, List<Track> b) => a.Select(t => t.Path).SequenceEqual(b.Select(t => t.Path));

    private async void Swap(Border slot, UIElement? next, bool animate, int id)
    {
        if (!animate || slot.Child == null || next == null)
        {
            slot.Child = next;
            if (animate && next != null) Arrive(next);
            return;
        }
        // Out: the shown tiles fade and drift a little to the left.
        if (Tiles(slot.Child) is { } old)
        {
            foreach (UIElement tile in old.Children)
            {
                tile.StartAnimation(Fade(tile, to: 0, ms: 200, delayMs: 0));
                tile.StartAnimation(Drift(tile, from: 0, to: -18, ms: 200, delayMs: 0));
            }
        }
        await Task.Delay(190);
        if (id != swapId) return; // a newer refill took over
        slot.Child = next;
        Arrive(next);
    }

    /// <summary>In: each tile fades in and rises from the right, a little after the one before.</summary>
    private static void Arrive(UIElement shelf)
    {
        if (Tiles(shelf) is not { } row) return;
        var i = 0;
        foreach (UIElement tile in row.Children)
        {
            var delay = Math.Min(i++, 10) * 45;
            tile.Opacity = 0;
            tile.StartAnimation(Fade(tile, to: 1, ms: 420, delayMs: delay));
            tile.StartAnimation(Drift(tile, from: 28, to: 0, ms: 520, delayMs: delay));
        }
    }

    private static StackPanel? Tiles(UIElement shelf) =>
        (shelf as StackPanel)?.Children.OfType<ScrollViewer>().FirstOrDefault()?.Content as StackPanel;

    private static Microsoft.UI.Composition.ScalarKeyFrameAnimation Fade(UIElement e, float to, int ms, int delayMs)
    {
        var c = Microsoft.UI.Xaml.Hosting.ElementCompositionPreview.GetElementVisual(e).Compositor;
        var a = c.CreateScalarKeyFrameAnimation();
        a.Target = "Opacity";
        a.InsertKeyFrame(1f, to, c.CreateCubicBezierEasingFunction(new System.Numerics.Vector2(0.2f, 0f), new System.Numerics.Vector2(0f, 1f)));
        a.Duration = TimeSpan.FromMilliseconds(ms);
        a.DelayTime = TimeSpan.FromMilliseconds(delayMs);
        return a;
    }

    private static Microsoft.UI.Composition.Vector3KeyFrameAnimation Drift(UIElement e, float from, float to, int ms, int delayMs)
    {
        var c = Microsoft.UI.Xaml.Hosting.ElementCompositionPreview.GetElementVisual(e).Compositor;
        var a = c.CreateVector3KeyFrameAnimation();
        a.Target = "Translation";
        a.InsertKeyFrame(0f, new System.Numerics.Vector3(from, 0, 0));
        a.InsertKeyFrame(1f, new System.Numerics.Vector3(to, 0, 0), c.CreateCubicBezierEasingFunction(new System.Numerics.Vector2(0.2f, 0f), new System.Numerics.Vector2(0f, 1f)));
        a.Duration = TimeSpan.FromMilliseconds(ms);
        a.DelayTime = TimeSpan.FromMilliseconds(delayMs);
        a.DelayBehavior = Microsoft.UI.Composition.AnimationDelayBehavior.SetInitialValueBeforeDelay;
        return a;
    }

    private static string Greeting()
    {
        var h = DateTime.Now.Hour;
        return h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    private static UIElement SongShelf(string heading, List<Track> tracks) =>
        Shelf(heading, tracks.Select((t, i) => (UIElement)new SongTile(t, tracks, i)).ToList(), playAll: tracks);

    /// <summary>A heading (with "See all" or "Play" where there's somewhere to go) and a row that scrolls sideways.</summary>
    private static UIElement Shelf(string heading, List<UIElement> items, Action? seeAll = null, List<Track>? playAll = null)
    {
        var head = new Grid { Margin = new Thickness(12, 26, 12, 10) };
        head.Children.Add(new TextBlock { Text = heading, Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"] });
        if (seeAll != null || playAll != null)
        {
            var go = new HyperlinkButton
            {
                Content = seeAll != null ? "See all" : "Play all",
                HorizontalAlignment = HorizontalAlignment.Right,
                Padding = new Thickness(6, 2, 6, 2),
            };
            go.Click += (_, _) => { if (seeAll != null) seeAll(); else Player.Play(playAll!, 0, shuffled: false); };
            head.Children.Add(go);
        }
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
        foreach (var item in items) row.Children.Add(item);
        Ui.PassVerticalWheel(row);
        return new StackPanel
        {
            Children =
            {
                head,
                new ScrollViewer
                {
                    Content = row,
                    Padding = new Thickness(6, 0, 6, 12),
                    HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden,
                    HorizontalScrollMode = ScrollMode.Enabled,
                    VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
                    VerticalScrollMode = ScrollMode.Disabled,
                },
            },
        };
    }

    /// <summary>The way into Replay, with this year's minutes on it.</summary>
    private static UIElement ReplayCard()
    {
        var year = Replay.Summarize(History.Records, new DateTime(DateTime.Now.Year, 1, 1));
        var line = year.IsEmpty ? "What you've played most will show here" : $"{year.Minutes:N0} minutes and {Ui.Count(year.Songs, "song")} this year";
        var card = new Button
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Margin = new Thickness(12, 34, 12, 0),
            Padding = new Thickness(24, 22, 24, 22),
            CornerRadius = new CornerRadius(14),
            BorderThickness = new Thickness(0),
            Background = new LinearGradientBrush
            {
                StartPoint = new Windows.Foundation.Point(0, 0),
                EndPoint = new Windows.Foundation.Point(1, 1),
                GradientStops =
                {
                    new GradientStop { Color = Color.FromArgb(255, 0xFA, 0x2D, 0x48), Offset = 0 },
                    new GradientStop { Color = Color.FromArgb(255, 0x8E, 0x1B, 0x6B), Offset = 1 },
                },
            },
            Content = new Grid
            {
                ColumnDefinitions = { new ColumnDefinition(), new ColumnDefinition { Width = GridLength.Auto } },
                Children =
                {
                    new StackPanel
                    {
                        Children =
                        {
                            new TextBlock { Text = "Replay", FontSize = 26, FontWeight = FontWeights.Bold, Foreground = new SolidColorBrush(Colors.White) },
                            new TextBlock { Text = line, Foreground = new SolidColorBrush(Color.FromArgb(220, 255, 255, 255)), Margin = new Thickness(0, 4, 0, 0) },
                        },
                    },
                    Ui.InColumn(new FontIcon { Glyph = "", FontSize = 34, Foreground = new SolidColorBrush(Colors.White), VerticalAlignment = VerticalAlignment.Center }, 1),
                },
            },
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(card, "Replay stats");
        card.Click += (_, _) => MainWindow.Instance.Navigate(typeof(ReplayPage));
        return card;
    }
}

// ---- A playlist, a folder ----

public sealed class PlaylistPage : Grid
{
    public PlaylistPage(object? id)
    {
        var p = LibraryStore.Playlist(id as string ?? "");
        if (p == null) { Children.Add(Ui.Title("This playlist isn't here any more.")); return; }
        var lib = MusicLibrary.Current;
        var tracks = p.Tracks(lib);
        var missing = p.Songs.Count - tracks.Count;

        var head = new Grid { Margin = new Thickness(12, 28, 12, 20), ColumnSpacing = 28 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition());
        head.Children.Add(new CoverView { Track = tracks.FirstOrDefault(), Size = 230, CornerRadius = new CornerRadius(10) });
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Spacing = 2 };
        words.Children.Add(new TextBlock { Text = "Playlist", Foreground = Ui.Secondary, FontSize = 13 });
        words.Children.Add(new TextBlock { Text = p.Name, FontSize = 30, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap, MaxLines = 2 });
        var minutes = tracks.Sum(t => t.DurationMs) / 60_000;
        var meta = $"{Ui.Count(tracks.Count, "song")} · {minutes} min" + (missing > 0 ? $" · {missing} not on this PC" : "");
        words.Children.Add(new TextBlock { Text = meta, Foreground = Ui.Secondary, FontSize = 13, Margin = new Thickness(0, 4, 0, 16) });
        var buttons = Ui.PlayButtons(tracks);
        var rename = new Button { Content = new FontIcon { Glyph = "", FontSize = 14 }, Padding = new Thickness(10, 8, 10, 8) };
        ToolTipService.SetToolTip(rename, "Rename");
        rename.Click += async (_, _) =>
        {
            var name = await PlaylistMenu.AskName("Rename playlist", "Rename", p.Name);
            if (name != null) LibraryStore.Rename(p.Id, name);
        };
        var delete = new Button { Content = new FontIcon { Glyph = "", FontSize = 14 }, Padding = new Thickness(10, 8, 10, 8) };
        ToolTipService.SetToolTip(delete, "Delete playlist");
        delete.Click += async (_, _) =>
        {
            if (!await PlaylistMenu.Confirm("Delete playlist?", $"“{p.Name}” will be deleted. The songs stay on your PC.", "Delete")) return;
            LibraryStore.Delete(p.Id);
            MainWindow.Instance.GoBack();
        };
        buttons.Children.Add(rename);
        buttons.Children.Add(delete);
        words.Children.Add(buttons);
        Grid.SetColumn(words, 1);
        head.Children.Add(words);

        UIElement? empty = tracks.Count == 0
            ? new TextBlock { Text = "Add songs with right-click › Add to playlist.", Foreground = Ui.Secondary, Margin = new Thickness(12, 0, 0, 0) }
            : null;
        Children.Add(new SongList(tracks) { Header = head, Footer = empty });
        Loaded += (_, _) => SongRow.OpenPlaylistId = p.Id;
        Unloaded += (_, _) => { if (SongRow.OpenPlaylistId == p.Id) SongRow.OpenPlaylistId = null; };
    }
}

public sealed class LikedPage : Grid
{
    public LikedPage(object? _)
    {
        var lib = MusicLibrary.Current;
        var tracks = LibraryStore.LikedTracks(lib);
        var missing = LibraryStore.Doc.Liked.Count - tracks.Count;
        var head = new StackPanel { Margin = new Thickness(12, 28, 12, 20), Spacing = 4 };
        head.Children.Add(new TextBlock { Text = "Liked songs", FontSize = 30, FontWeight = FontWeights.Bold });
        head.Children.Add(new TextBlock
        {
            Text = Ui.Count(tracks.Count, "song") + (missing > 0 ? $" · {missing} not on this PC" : ""),
            Foreground = Ui.Secondary,
            FontSize = 13,
            Margin = new Thickness(0, 0, 0, 12),
        });
        head.Children.Add(Ui.PlayButtons(tracks));
        UIElement? empty = tracks.Count == 0
            ? new TextBlock { Text = "Like a song with right-click › Like.", Foreground = Ui.Secondary, Margin = new Thickness(12, 0, 0, 0) }
            : null;
        Children.Add(new SongList(tracks) { Header = head, Footer = empty });
    }
}

public sealed class FolderPage : Grid
{
    public FolderPage(object? path)
    {
        var f = MusicLibrary.Current.Folder(path as string ?? "");
        if (f == null) { Children.Add(Ui.Title("This folder isn't in your library any more.")); return; }
        var head = new StackPanel { Margin = new Thickness(12, 28, 12, 20), Spacing = 4 };
        head.Children.Add(new TextBlock { Text = f.Name, FontSize = 30, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap });
        head.Children.Add(new TextBlock { Text = f.Path, Foreground = Ui.Secondary, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis });
        head.Children.Add(new TextBlock { Text = Ui.Count(f.Tracks.Count, "song"), Foreground = Ui.Secondary, FontSize = 13, Margin = new Thickness(0, 0, 0, 12) });
        head.Children.Add(Ui.PlayButtons(f.Tracks));
        Children.Add(new SongList(f.Tracks) { Header = head });
    }
}

// ---- Replay ----

/// <summary>Replay: what's been played most this month, this year or ever, as the phone's.</summary>
public sealed class ReplayPage : Grid
{
    private readonly StackPanel body = new();
    private static int period = 1; // 0 month, 1 year, 2 all time

    public ReplayPage(object? _)
    {
        var page = new StackPanel { Padding = new Thickness(32, 0, 32, 40), MaxWidth = 1100, HorizontalAlignment = HorizontalAlignment.Left };
        page.Children.Add(new TextBlock { Text = "Replay", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], Margin = new Thickness(0, 28, 0, 12) });
        var bar = new SelectorBar();
        foreach (var label in new[] { "This month", "This year", "All time" }) bar.Items.Add(new SelectorBarItem { Text = label });
        bar.SelectedItem = bar.Items[period];
        bar.SelectionChanged += (_, _) =>
        {
            period = bar.Items.IndexOf(bar.SelectedItem);
            Fill();
        };
        page.Children.Add(bar);
        page.Children.Add(body);
        Children.Add(new ScrollViewer { Content = page });
        Fill();
    }

    private void Fill()
    {
        body.Children.Clear();
        var now = DateTime.Now;
        var since = period switch
        {
            0 => new DateTime(now.Year, now.Month, 1),
            1 => new DateTime(now.Year, 1, 1),
            _ => DateTime.MinValue.AddYears(1970),
        };
        var s = Replay.Summarize(History.Records, since);
        if (s.IsEmpty)
        {
            body.Children.Add(new TextBlock
            {
                Text = "Nothing played in this time yet. Play some music (or import your phone's history in Settings) and your Replay builds up here.",
                Foreground = Ui.Secondary,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 24, 0, 0),
            });
            return;
        }
        var lib = MusicLibrary.Current;

        // The headline numbers.
        var numbers = new Grid { ColumnSpacing = 14, Margin = new Thickness(0, 22, 0, 0) };
        var stats = new[] { (s.Minutes.ToString("N0"), "minutes listened"), (s.Plays.ToString("N0"), "plays"), (s.Songs.ToString("N0"), "songs"), (s.Artists.ToString("N0"), "artists") };
        for (var i = 0; i < stats.Length; i++)
        {
            numbers.ColumnDefinitions.Add(new ColumnDefinition());
            numbers.Children.Add(Ui.InColumn(Card(new StackPanel
            {
                Children =
                {
                    new TextBlock { Text = stats[i].Item1, FontSize = i == 0 ? 36 : 28, FontWeight = FontWeights.Bold, Foreground = i == 0 ? Ui.Accent : (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"] },
                    new TextBlock { Text = stats[i].Item2, Foreground = Ui.Secondary },
                },
            }), i));
        }
        body.Children.Add(numbers);

        // Top songs and top artists side by side.
        var tops = new Grid { ColumnSpacing = 14, Margin = new Thickness(0, 14, 0, 0) };
        tops.ColumnDefinitions.Add(new ColumnDefinition());
        tops.ColumnDefinitions.Add(new ColumnDefinition());
        tops.Children.Add(Card(TopList("Top songs", s.TopSongs, lib, songs: true)));
        tops.Children.Add(Ui.InColumn(Card(TopList("Top artists", s.TopArtists, lib, songs: false)), 1));
        body.Children.Add(tops);

        // When you listen: the part of the day, and minutes by hour.
        var (clockTitle, clockLine) = Replay.Describe(s.Clock);
        body.Children.Add(Card(new StackPanel
        {
            Spacing = 4,
            Children =
            {
                new TextBlock { Text = clockTitle, FontSize = 22, FontWeight = FontWeights.Bold },
                new TextBlock { Text = $"{clockLine}. Most of all around {HourName(s.PeakHour)}.", Foreground = Ui.Secondary },
                Bars(s.Hours.Select((m, h) => (h % 6 == 0 ? HourName(h) : "", m)).ToList(), 90),
            },
        }, top: 14));

        // The little facts.
        var facts = new Grid { ColumnSpacing = 14, Margin = new Thickness(0, 14, 0, 0) };
        var cards = new List<UIElement>();
        if (s.TopAlbum is { } album) cards.Add(Fact("Top album", album.Title, $"{album.Subtitle} · {Ui.Count(album.Plays, "play")}", Find(album, lib)));
        if (s.OnRepeat is { } rep) cards.Add(Fact("On repeat", rep.Title, $"{Ui.Count(rep.Plays, "play")} in one day", Find(rep, lib)));
        if (s.BusiestDay is { } day) cards.Add(Fact("Busiest day", day.ToString("dddd d MMMM"), $"{s.BusiestDayMinutes} minutes", null));
        if (s.StreakDays > 0) cards.Add(Fact("Longest streak", Ui.Count(s.StreakDays, "day"), "in a row with music", null));
        for (var i = 0; i < cards.Count; i++)
        {
            facts.ColumnDefinitions.Add(new ColumnDefinition());
            facts.Children.Add(Ui.InColumn((FrameworkElement)cards[i], i));
        }
        body.Children.Add(facts);

        // The last twelve months.
        body.Children.Add(Card(new StackPanel
        {
            Spacing = 4,
            Children =
            {
                new TextBlock { Text = "Your year in minutes", FontSize = 18, FontWeight = FontWeights.SemiBold },
                Bars(s.Months, 110),
            },
        }, top: 14));
    }

    private static Track? Find(Replay.Entry e, Library lib) => History.TrackOf(e.First, lib);

    private static string HourName(int h) => h == 0 ? "12 am" : h < 12 ? $"{h} am" : h == 12 ? "12 pm" : $"{h - 12} pm";

    private static UIElement TopList(string heading, List<Replay.Entry> entries, Library lib, bool songs)
    {
        var list = new StackPanel { Spacing = 10 };
        list.Children.Add(new TextBlock { Text = heading, FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 4) });
        for (var i = 0; i < entries.Count; i++)
        {
            var e = entries[i];
            var track = Find(e, lib);
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(22) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition());
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(new TextBlock { Text = $"{i + 1}", FontWeight = FontWeights.Bold, Foreground = Ui.Accent, VerticalAlignment = VerticalAlignment.Center });
            row.Children.Add(Ui.InColumn(new CoverView { Track = track, Size = 44, CornerRadius = new CornerRadius(songs ? 4 : 22) }, 1));
            row.Children.Add(Ui.InColumn(new StackPanel
            {
                VerticalAlignment = VerticalAlignment.Center,
                Children =
                {
                    new TextBlock { Text = e.Title, TextTrimming = TextTrimming.CharacterEllipsis },
                    new TextBlock { Text = e.Subtitle, FontSize = 12, Foreground = Ui.Secondary, TextTrimming = TextTrimming.CharacterEllipsis },
                },
            }, 2));
            row.Children.Add(Ui.InColumn(new TextBlock { Text = Ui.Count(e.Plays, "play"), FontSize = 12, Foreground = Ui.Secondary, VerticalAlignment = VerticalAlignment.Center }, 3));
            list.Children.Add(row);
        }
        return list;
    }

    private static FrameworkElement Fact(string label, string value, string detail, Track? cover)
    {
        var text = new StackPanel
        {
            Spacing = 2,
            Children =
            {
                new TextBlock { Text = label, Foreground = Ui.Secondary, FontSize = 13 },
                new TextBlock { Text = value, FontSize = 18, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, MaxLines = 2 },
                new TextBlock { Text = detail, Foreground = Ui.Secondary, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis },
            },
        };
        if (cover == null) return Card(text);
        var g = new Grid { ColumnSpacing = 12 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.Children.Add(new CoverView { Track = cover, Size = 56, CornerRadius = new CornerRadius(6), VerticalAlignment = VerticalAlignment.Top });
        g.Children.Add(Ui.InColumn(text, 1));
        return Card(g);
    }

    /// <summary>A simple bar chart: one bar per value, labelled underneath where there's a label.</summary>
    private static UIElement Bars(List<(string Label, long Value)> values, double height)
    {
        var max = Math.Max(1, values.Max(v => v.Value));
        var grid = new Grid { ColumnSpacing = 4, Margin = new Thickness(0, 12, 0, 0) };
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(height) });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        for (var i = 0; i < values.Count; i++)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition());
            var bar = new Border
            {
                Height = Math.Max(3, height * values[i].Value / max),
                VerticalAlignment = VerticalAlignment.Bottom,
                CornerRadius = new CornerRadius(3),
                Background = values[i].Value == max ? Ui.Accent : (Brush)Application.Current.Resources["ControlStrongFillColorDisabledBrush"],
            };
            ToolTipService.SetToolTip(bar, $"{values[i].Value} min");
            grid.Children.Add(Ui.InColumn(bar, i));
            if (values[i].Label.Length > 0)
            {
                var label = new TextBlock { Text = values[i].Label, FontSize = 11, Foreground = Ui.Secondary, Margin = new Thickness(0, 6, 0, 0) };
                Grid.SetRow(label, 1);
                grid.Children.Add(Ui.InColumn(label, i));
            }
        }
        return grid;
    }

    private static Border Card(UIElement child, double top = 0) => new()
    {
        Child = child,
        Padding = new Thickness(20, 18, 20, 18),
        Margin = new Thickness(0, top, 0, 0),
        CornerRadius = new CornerRadius(12),
        Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
        BorderThickness = new Thickness(1),
    };
}
