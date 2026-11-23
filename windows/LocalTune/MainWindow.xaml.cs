using LocalTune.Core;
using LocalTune.Views;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;
using DispatcherQueueTimer = Microsoft.UI.Dispatching.DispatcherQueueTimer;

namespace LocalTune;

public sealed partial class MainWindow : Window
{
    public static MainWindow Instance { get; private set; } = null!;

    private sealed record Entry(Type Page, object? Param);

    /// <summary>The pages open, most recent last; Back goes down one.</summary>
    private readonly List<Entry> stack = new();
    private readonly DispatcherQueueTimer tick;
    private readonly DispatcherQueueTimer toastTimer;

    public MainWindow()
    {
        Instance = this;
        InitializeComponent();

        ExtendsContentIntoTitleBar = true;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        SetTitleBar(AppTitleBar);
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        var scale = Root.XamlRoot?.RasterizationScale ?? 1.0;
        AppWindow.Resize(new Windows.Graphics.SizeInt32((int)(1280 * scale), (int)(820 * scale)));

        WireChrome();
        // Light or dark, as Windows is: the caption buttons follow, and when Windows switches
        // while the app is open, what was built in the old colours is built again.
        AppWindow.TitleBar.PreferredTheme = TitleBarTheme.UseDefaultAppMode;
        Root.ActualThemeChanged += (_, _) => RebuildForTheme();
        NowPlaying.CloseRequested += ClosePlayer;

        Root.PreviewKeyDown += OnPreviewKey;
        AddShortcuts();

        // The seek bars, a few times a second.
        tick = DispatcherQueue.CreateTimer();
        tick.Interval = TimeSpan.FromMilliseconds(250);
        tick.Tick += (_, _) =>
        {
            Bar.Tick();
            if (NowPlaying.Visibility == Visibility.Visible) NowPlaying.Tick();
        };
        tick.Start();

        toastTimer = DispatcherQueue.CreateTimer();
        toastTimer.Interval = TimeSpan.FromSeconds(3);
        toastTimer.IsRepeating = false;
        toastTimer.Tick += (_, _) => Toast.IsOpen = false;
        Player.Message += ShowToast;

        MusicLibrary.Changed += OnLibraryChanged;
        Closed += (_, _) => Player.Shutdown();

        // Playlists changed (made, renamed, a song added or removed): Home and the playlist's page show it.
        // The sidebar opens and closes with its menu button, and stays as it was left.
        Nav.IsPaneOpen = Settings.Value.SidebarOpen;
        Nav.PaneOpened += (_, _) => { if (!Settings.Value.SidebarOpen) Settings.Update(s => s.SidebarOpen = true); };
        Nav.PaneClosed += (_, _) => { if (Settings.Value.SidebarOpen) Settings.Update(s => s.SidebarOpen = false); };

        FillPlaylists();
        LibraryStore.Changed += () =>
        {
            FillPlaylists();
            MarkSection();
            if (stack.Count > 0 && (stack[^1].Page == typeof(HomePage) || stack[^1].Page == typeof(PlaylistPage) || stack[^1].Page == typeof(LikedPage))) Show();
        };

        Section(Settings.Value.Folders.Count == 0 ? typeof(WelcomePage) : typeof(HomePage));
        // The keyboard starts on the page, not in the search box, so Space plays straight away.
        Root.Loaded += (_, _) => Host.Focus(FocusState.Programmatic);
    }

    private void WireChrome()
    {
        Bar.OpenPlayer += OpenPlayer;
        Bar.OpenMini += OpenMini;
        Bar.ToggleQueue += () =>
        {
            QueuePane.Visibility = QueuePane.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
            QueuePane.Refresh();
        };
        QueuePane.CloseRequested += () => QueuePane.Visibility = Visibility.Collapsed;
    }

    /// <summary>
    /// Windows changed between light and dark. The parts built in code took their colours when
    /// they were made, so the player bar, the queue and the page are made again in the new ones.
    /// (The full player is always dark, so it stays as it is.)
    /// </summary>
    private void RebuildForTheme()
    {
        var barIndex = Root.Children.IndexOf(Bar);
        Root.Children.Remove(Bar);
        Bar = new PlayerBar();
        Grid.SetRow(Bar, 2);
        Root.Children.Insert(barIndex, Bar);

        var queueParent = (Grid)QueuePane.Parent;
        var shown = QueuePane.Visibility;
        queueParent.Children.Remove(QueuePane);
        QueuePane = new QueuePanel { Visibility = shown };
        Grid.SetColumn(QueuePane, 1);
        queueParent.Children.Add(QueuePane);
        QueuePane.Refresh();

        WireChrome();
        Bar.Tick();
        Reload();
    }

    // ---- Pages ----

    public void Navigate(Type page, object? param = null)
    {
        if (NowPlaying.Visibility == Visibility.Visible) ClosePlayer();
        var last = stack.LastOrDefault();
        if (last != null && last.Page == page && Equals(last.Param, param)) return;
        stack.Add(new Entry(page, param));
        Show();
    }

    private void Section(Type page, object? param = null)
    {
        stack.Clear();
        Navigate(page, param);
    }

    /// <summary>The sidebar's Playlists: Liked songs, the user's playlists, then New playlist.</summary>
    private void FillPlaylists()
    {
        var items = Nav.MenuItems;
        var from = items.IndexOf(PlaylistsHeader) + 1;
        while (items.Count > from) items.RemoveAt(items.Count - 1);
        NavigationViewItem Item(string text, string tag, string glyph, bool selects = true)
        {
            var i = new NavigationViewItem
            {
                Content = text,
                Tag = tag,
                SelectsOnInvoked = selects,
                Icon = new FontIcon { Glyph = glyph, Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["AccentBrush"] },
            };
            ToolTipService.SetToolTip(i, text);
            return i;
        }
        items.Add(Item("Liked songs", "liked", ""));
        foreach (var p in LibraryStore.Playlists) items.Add(Item(p.Name, "playlist:" + p.Id, ""));
        items.Add(Item("New playlist", "newplaylist", "", selects: false));
    }

    /// <summary>Builds the page shown again, with the library as it is now.</summary>
    public void Reload()
    {
        if (stack.Count == 0) return;
        var top = stack[^1];
        // The welcome page gives way to the songs once there's a folder, and comes back if they're all removed.
        if (top.Page == typeof(WelcomePage) && Settings.Value.Folders.Count > 0) { Section(typeof(HomePage)); return; }
        if (top.Page != typeof(SettingsPage) && Settings.Value.Folders.Count == 0) { Section(typeof(WelcomePage)); return; }
        Show();
    }

    private void Show()
    {
        var top = stack[^1];
        Host.Content = (UIElement)Activator.CreateInstance(top.Page, top.Param)!;
        Nav.IsBackEnabled = stack.Count > 1;
        // The back button shows only when there's somewhere to go back to; the page moves down
        // a little to make room for it.
        var canGoBack = stack.Count > 1;
        BackButton.Visibility = canGoBack ? Visibility.Visible : Visibility.Collapsed;
        Host.Margin = new Thickness(0, canGoBack ? 22 : 0, 0, 0);
        MarkSection();
    }

    /// <summary>The sidebar marks the section the page belongs to.</summary>
    private void MarkSection()
    {
        if (stack.Count == 0) return;
        var section = stack[0];
        if (section.Page == typeof(SettingsPage)) { Nav.SelectedItem = Nav.SettingsItem; return; }
        var tag = section.Page == typeof(AlbumsPage) ? "albums"
            : section.Page == typeof(ArtistsPage) ? "artists"
            : section.Page == typeof(FoldersPage) ? "folders"
            : section.Page == typeof(SongsPage) ? "songs"
            : section.Page == typeof(HomePage) ? "home"
            : section.Page == typeof(LikedPage) ? "liked"
            : section.Page == typeof(PlaylistPage) ? "playlist:" + section.Param
            : null;
        Nav.SelectedItem = Nav.MenuItems.OfType<NavigationViewItem>().FirstOrDefault(i => (string)i.Tag == tag);
    }

    /// <summary>Back one page, as the back button does (a deleted playlist's page closing).</summary>
    public void GoBack() => Back();

    private bool Back()
    {
        if (stack.Count <= 1) return false;
        stack.RemoveAt(stack.Count - 1);
        if (stack[^1].Page != typeof(SearchPage)) SearchBox.Text = "";
        Show();
        return true;
    }

    /// <summary>
    /// A sidebar item clicked: its section, from the top. Every click counts, even on the section
    /// already selected, so clicking Home from a playlist's page goes back to Home.
    /// </summary>
    private void OnNavInvoked(NavigationView sender, NavigationViewItemInvokedEventArgs args)
    {
        SearchBox.Text = "";
        if (args.IsSettingsInvoked) { Section(typeof(SettingsPage)); return; }
        var tag = args.InvokedItemContainer?.Tag as string;
        if (tag == "liked") { Section(typeof(LikedPage)); return; }
        if (tag?.StartsWith("playlist:") == true) { Section(typeof(PlaylistPage), tag["playlist:".Length..]); return; }
        if (tag == "newplaylist") { NewPlaylist(); return; }
        Section(tag switch
        {
            _ when Settings.Value.Folders.Count == 0 => typeof(WelcomePage),
            "albums" => typeof(AlbumsPage),
            "artists" => typeof(ArtistsPage),
            "folders" => typeof(FoldersPage),
            "songs" => typeof(SongsPage),
            _ => typeof(HomePage),
        });
    }

    private async void NewPlaylist()
    {
        var name = await PlaylistMenu.AskName("New playlist", "Create", "");
        if (name == null) { MarkSection(); return; }
        var p = LibraryStore.Create(name);
        Section(typeof(PlaylistPage), p.Id);
    }

    private void OnBackRequested(NavigationView sender, NavigationViewBackRequestedEventArgs args) => Back();

    private void OnBackClick(object sender, RoutedEventArgs e) => Back();

    private void OnSearchChanged(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args)
    {
        if (args.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
        var q = sender.Text.Trim();
        var onSearch = stack.Count > 0 && stack[^1].Page == typeof(SearchPage);
        if (q.Length == 0)
        {
            if (onSearch) Back();
            return;
        }
        // Typing changes the results in place rather than stacking a page per letter.
        if (onSearch) stack[^1] = new Entry(typeof(SearchPage), q);
        else stack.Add(new Entry(typeof(SearchPage), q));
        Show();
    }

    private void OnLibraryChanged()
    {
        ScanText.Visibility = MusicLibrary.Scanning ? Visibility.Visible : Visibility.Collapsed;
        if (!MusicLibrary.Scanning) Reload();
    }

    // ---- The full player ----

    /// <summary>
    /// The full player takes the whole screen, as Apple Music's does: no title bar, no window
    /// buttons, no resizing. Leaving it puts the window back as it was (maximised or not).
    /// </summary>
    private MiniPlayer? mini;

    /// <summary>
    /// Apple Music's mini player: a small window that stays above every other, while this one
    /// steps aside. Its expand button brings this window back.
    /// </summary>
    public void OpenMini()
    {
        if (Player.Current == null) { ShowToast("Play something first"); return; }
        if (NowPlaying.Visibility == Visibility.Visible) ClosePlayer();
        if (mini == null)
        {
            var m = new MiniPlayer(Root.XamlRoot?.RasterizationScale ?? 1.0);
            // Closed some other way (Alt+F4): this window comes back rather than staying hidden.
            m.Closed += (_, _) =>
            {
                if (mini == m) mini = null;
                if (!quitting && !AppWindow.IsVisible) { AppWindow.Show(); Activate(); }
            };
            mini = m;
        }
        mini.Activate();
        AppWindow.Hide();
    }

    /// <summary>Back from the mini player to this window.</summary>
    public void LeaveMini()
    {
        var m = mini;
        mini = null;
        AppWindow.Show();
        Activate();
        m?.Close();
    }

    /// <summary>The mini player's close button: LocalTune closes, as Apple Music's does.</summary>
    public void QuitFromMini()
    {
        quitting = true;
        var m = mini;
        mini = null;
        Close();
        m?.Close();
    }

    private bool quitting;

    private void OpenPlayer()
    {
        if (Player.Current == null) return;
        AppWindow.SetPresenter(AppWindowPresenterKind.FullScreen);
        NowPlaying.Open();
    }

    private void ClosePlayer()
    {
        NowPlaying.Close();
        if (AppWindow.Presenter.Kind == AppWindowPresenterKind.FullScreen)
            AppWindow.SetPresenter(AppWindowPresenterKind.Overlapped);
    }

    // ---- Keys ----

    /// <summary>
    /// Space plays and pauses, Esc closes the player or goes back, Ctrl+Left/Right change song.
    /// They work wherever the keyboard is, except Space while typing in the search box.
    /// </summary>
    private void AddShortcuts()
    {
        void Add(VirtualKey key, VirtualKeyModifiers mods, Func<bool> action)
        {
            var a = new KeyboardAccelerator { Key = key, Modifiers = mods };
            a.Invoked += (_, e) => e.Handled = action();
            Root.KeyboardAccelerators.Add(a);
        }
        Add(VirtualKey.Space, VirtualKeyModifiers.None, () =>
        {
            if (Typing()) return false;
            Player.Toggle();
            return true;
        });
        Add(VirtualKey.Escape, VirtualKeyModifiers.None, () =>
        {
            if (NowPlaying.Visibility == Visibility.Visible) { ClosePlayer(); return true; }
            return Back();
        });
        Add(VirtualKey.Right, VirtualKeyModifiers.Control, () => { Player.Next(); return true; });
        Add(VirtualKey.Left, VirtualKeyModifiers.Control, () => { Player.Previous(); return true; });
        Root.KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;
    }

    private bool Typing() => FocusManager.GetFocusedElement(Root.XamlRoot) is TextBox or AutoSuggestBox or PasswordBox;

    /// <summary>A focused button would take Space as a click; here it plays and pauses instead, as in Apple Music.</summary>
    private void OnPreviewKey(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Space || Typing()) return;
        Player.Toggle();
        e.Handled = true;
    }

    public void ShowToast(string text)
    {
        Toast.Message = text;
        Toast.IsOpen = true;
        toastTimer.Stop();
        toastTimer.Start();
    }
}
