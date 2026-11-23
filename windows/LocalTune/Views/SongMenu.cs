using LocalTune.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;

namespace LocalTune.Views;

/// <summary>
/// A song's right-click menu, laid out as the user's reference player: four big buttons across
/// the top (Play, Shuffle, Play next, Add to queue), then Add to playlist, Favourite, Snooze for
/// a month and Metadata; then the album and artist pages.
/// </summary>
public static class SongMenu
{
    public static void Show(FrameworkElement target, Track t, IReadOnlyList<Track> list, Windows.Foundation.Point? at = null)
    {
        var tracks = list.Count > 0 ? list.ToList() : new List<Track> { t };
        var index = Math.Max(0, tracks.FindIndex(x => x.Path == t.Path));
        var flyout = new Flyout
        {
            FlyoutPresenterStyle = new Style(typeof(FlyoutPresenter))
            {
                Setters =
                {
                    new Setter(Control.PaddingProperty, new Thickness(6)),
                    new Setter(Control.CornerRadiusProperty, new CornerRadius(10)),
                    new Setter(FrameworkElement.MinWidthProperty, 0.0),
                },
            },
        };
        void Do(Action a)
        {
            flyout.Hide();
            a();
        }

        var panel = new StackPanel { Width = 264, Spacing = 1 };

        // The four big buttons.
        var top = new Grid { ColumnSpacing = 6, Margin = new Thickness(0, 0, 0, 4) };
        var big = new (string Glyph, string Label, Action Act)[]
        {
            ("", "Play", () => Player.Play(tracks, index, shuffled: false)),
            ("", "Shuffle", () => Player.Play(tracks, index, shuffled: true)),
            ("", "Play Next", () => Player.PlayNext(t)),
            ("", "Add to Queue", () => Player.AddToQueue(t)),
        };
        for (var i = 0; i < big.Length; i++)
        {
            var (glyph, label, act) = big[i];
            top.ColumnDefinitions.Add(new ColumnDefinition());
            var b = new Button
            {
                HorizontalAlignment = HorizontalAlignment.Stretch,
                Padding = new Thickness(2, 8, 2, 6),
                CornerRadius = new CornerRadius(8),
                BorderThickness = new Thickness(0),
                Background = (Brush)Application.Current.Resources["ControlFillColorDefaultBrush"],
                Content = new StackPanel
                {
                    Spacing = 4,
                    Children =
                    {
                        new FontIcon { Glyph = glyph, FontSize = 16, HorizontalAlignment = HorizontalAlignment.Center },
                        new TextBlock { Text = label, FontSize = 11, TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center },
                    },
                },
            };
            b.Click += (_, _) => Do(act);
            Grid.SetColumn(b, i);
            top.Children.Add(b);
        }
        panel.Children.Add(top);
        panel.Children.Add(Divider());

        // Add to playlist: the playlists open beside the menu.
        var addTo = Row("", "Add to Playlist", null, chevron: true);
        addTo.Click += (_, _) =>
        {
            var sub = PlaylistMenu.AddTo(new[] { t });
            var menu = new MenuFlyout { Placement = FlyoutPlacementMode.RightEdgeAlignedTop };
            foreach (var item in sub.Items.ToList())
            {
                sub.Items.Remove(item);
                if (item is MenuFlyoutItem mi) mi.Click += (_, _) => flyout.Hide();
                menu.Items.Add(item);
            }
            menu.ShowAt(addTo);
        };
        panel.Children.Add(addTo);

        var liked = LibraryStore.IsLiked(t);
        panel.Children.Add(Row(liked ? "" : "", liked ? "Remove from Favorites" : "Favorites",
            () => Do(() => { if (liked) LibraryStore.Unlike(t); else LibraryStore.Like(t); }), accent: liked));

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var snoozed = Settings.Value.Snoozed.TryGetValue(t.Path, out var until) && until > now;
        panel.Children.Add(Row("", snoozed ? "Stop snoozing" : "Snooze for a Month", () => Do(() =>
        {
            Settings.Update(s =>
            {
                if (snoozed) s.Snoozed.Remove(t.Path);
                else s.Snoozed[t.Path] = now + 30L * 24 * 3600 * 1000;
            });
            MainWindow.Instance.ShowToast(snoozed ? $"“{t.Title}” can be suggested again" : $"“{t.Title}” won't be suggested for a month");
        })));
        panel.Children.Add(Row("", "Metadata", () => Do(() => _ = ShowMetadata(t))));

        panel.Children.Add(Divider());
        if (SongRow.OpenPlaylistId is { } pid && LibraryStore.Playlist(pid) != null)
            panel.Children.Add(Row("", "Remove from this Playlist", () => Do(() => LibraryStore.Remove(pid, t))));
        panel.Children.Add(Row("", "View Album", () => Do(() => MainWindow.Instance.Navigate(typeof(AlbumPage), t.AlbumKey))));
        // One View artist entry per name.
        foreach (var name in t.Artists)
            panel.Children.Add(Row("", $"View {name}", () => Do(() => MainWindow.Instance.Navigate(typeof(ArtistPage), name))));

        flyout.Content = panel;
        flyout.ShowAt(target, new FlyoutShowOptions { Position = at, Placement = at == null ? FlyoutPlacementMode.BottomEdgeAlignedRight : FlyoutPlacementMode.Auto });
    }

    private static Border Divider() => new()
    {
        Height = 1,
        Margin = new Thickness(8, 4, 8, 4),
        Background = (Brush)Application.Current.Resources["DividerStrokeColorDefaultBrush"],
    };

    private static Button Row(string glyph, string text, Action? onClick, bool chevron = false, bool accent = false)
    {
        var content = new Grid { ColumnSpacing = 12 };
        content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        content.ColumnDefinitions.Add(new ColumnDefinition());
        content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        content.Children.Add(new FontIcon
        {
            Glyph = glyph,
            FontSize = 15,
            Foreground = accent ? Ui.Accent : (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        });
        content.Children.Add(Ui.InColumn(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center }, 1));
        if (chevron) content.Children.Add(Ui.InColumn(new FontIcon { Glyph = "", FontSize = 11, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] }, 2));
        var b = new Button
        {
            Content = content,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(12, 8, 12, 8),
            BorderThickness = new Thickness(0),
            CornerRadius = new CornerRadius(6),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, text);
        if (onClick != null) b.Click += (_, _) => onClick();
        return b;
    }

    /// <summary>The song's file and what's in it.</summary>
    private static async Task ShowMetadata(Track t)
    {
        var info = new FileInfo(t.Path);
        var rows = new (string, string)[]
        {
            ("Title", t.Title),
            ("Artist", t.Artist),
            ("Album", t.Album),
            ("Album artist", t.AlbumArtist),
            ("Genre", t.Genre),
            ("Year", t.Year > 0 ? t.Year.ToString() : ""),
            ("Track", t.TrackNumber > 0 ? (t.DiscNumber > 0 ? $"{t.DiscNumber}-{t.TrackNumber}" : t.TrackNumber.ToString()) : ""),
            ("Length", t.DurationText),
            ("Format", t.QualityDetail),
            ("Quality", t.QualityTier ?? "Lossy"),
            ("Plays", History.PlayCount(t).ToString()),
            ("Size", info.Exists ? $"{info.Length / 1048576.0:0.0} MB" : ""),
            ("File", t.Path),
        };
        var grid = new Grid { ColumnSpacing = 16, RowSpacing = 6, MaxWidth = 520 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        var r = 0;
        foreach (var (label, value) in rows)
        {
            if (string.IsNullOrWhiteSpace(value)) continue;
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var l = new TextBlock { Text = label, Foreground = Ui.Secondary };
            var v = new TextBlock { Text = value, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
            Grid.SetRow(l, r);
            Grid.SetRow(v, r);
            grid.Children.Add(l);
            grid.Children.Add(Ui.InColumn(v, 1));
            r++;
        }
        var dialog = new ContentDialog
        {
            Title = "Metadata",
            Content = new ScrollViewer { Content = grid, MaxHeight = 480 },
            PrimaryButtonText = "Show in folder",
            CloseButtonText = "Close",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = MainWindow.Instance.Content.XamlRoot,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary && info.Exists)
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{t.Path}\"");
    }
}
