using Microsoft.UI.Xaml.Shapes;
using Microsoft.UI.Xaml.Hosting;
using System.Numerics;
using LocalTune.Core;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace LocalTune.Views;

/// <summary>Shuffle, back, play, next, repeat.</summary>
public sealed class PlayControls : StackPanel
{
    private readonly Button shuffle, previous, play, next, repeat;
    private readonly FontIcon playIcon, shuffleIcon, repeatIcon;
    private readonly bool large;
    private readonly Brush tint;

    public PlayControls(bool large, Brush? tint = null)
    {
        this.large = large;
        this.tint = tint ?? (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
        Orientation = Orientation.Horizontal;
        HorizontalAlignment = HorizontalAlignment.Center;
        Spacing = large ? 22 : 6;

        shuffleIcon = Icon("\uE8B1", 16);
        repeatIcon = Icon("\uE8EE", 16);
        playIcon = Icon("\uF5B0", large ? 34 : 20);
        shuffle = Round(shuffleIcon, 40, "Shuffle", () => Player.SetShuffle(!Player.Shuffle));
        previous = Round(Icon("\uE892", large ? 26 : 16), large ? 56 : 40, "Previous", Player.Previous);
        play = Round(playIcon, large ? 72 : 44, "Play", Player.Toggle);
        next = Round(Icon("\uE893", large ? 26 : 16), large ? 56 : 40, "Next", Player.Next);
        repeat = Round(repeatIcon, 40, "Repeat", Player.CycleRepeat);
        Children.Add(shuffle);
        Children.Add(previous);
        Children.Add(play);
        Children.Add(next);
        Children.Add(repeat);

        Loaded += (_, _) => { Player.StateChanged += Refresh; Refresh(); };
        Unloaded += (_, _) => Player.StateChanged -= Refresh;
    }

    private FontIcon Icon(string glyph, double size) => new() { Glyph = glyph, FontSize = size, Foreground = tint };

    private static Button Round(UIElement content, double size, string name, Action onClick)
    {
        var b = new Button
        {
            Content = content,
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(size / 2),
            Padding = new Thickness(0),
            Style = (Style)Application.Current.Resources["IconButton"],
        };
        ToolTipService.SetToolTip(b, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, name);
        b.Click += (_, _) => onClick();
        return b;
    }

    private void Refresh()
    {
        playIcon.Glyph = Player.IsPlaying ? "\uE769" : "\uF5B0";
        ToolTipService.SetToolTip(play, Player.IsPlaying ? "Pause" : "Play");
        var accent = (Brush)Application.Current.Resources["AccentBrush"];
        var dim = new SolidColorBrush(((tint as SolidColorBrush)?.Color ?? Colors.White) with { A = 150 });
        shuffleIcon.Foreground = Player.Shuffle ? accent : dim;
        repeatIcon.Foreground = Player.Repeat == RepeatMode.Off ? dim : accent;
        repeatIcon.Glyph = Player.Repeat == RepeatMode.One ? "\uE8ED" : "\uE8EE";
    }
}

/// <summary>The time played, a bar to drag, and the song's length.</summary>
public sealed class SeekBar : Grid
{
    private readonly Slider slider = new() { Minimum = 0, Maximum = 1, StepFrequency = 0.0005, IsThumbToolTipEnabled = false, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock elapsed = new() { FontSize = 11, VerticalAlignment = VerticalAlignment.Center, Width = 44 };
    private readonly TextBlock total = new() { FontSize = 11, VerticalAlignment = VerticalAlignment.Center, Width = 44, TextAlignment = TextAlignment.Right };
    private bool dragging;
    private bool setting;

    public SeekBar(Brush? tint = null)
    {
        var dim = tint ?? (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        elapsed.Foreground = dim;
        total.Foreground = dim;
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        ColumnSpacing = 8;
        SetColumn(slider, 1);
        SetColumn(total, 2);
        Children.Add(elapsed);
        Children.Add(slider);
        Children.Add(total);

        // While the thumb is held the bar follows the mouse, not the song; letting go seeks.
        slider.AddHandler(PointerPressedEvent, new PointerEventHandler((_, _) => dragging = true), true);
        slider.AddHandler(PointerReleasedEvent, new PointerEventHandler((_, _) => Commit()), true);
        slider.AddHandler(PointerCaptureLostEvent, new PointerEventHandler((_, _) => Commit()), true);
        slider.ValueChanged += (_, e) =>
        {
            if (setting) return;
            if (dragging) elapsed.Text = Format.Time((long)(e.NewValue * Player.DurationMs));
            else Player.SeekTo((long)(e.NewValue * Player.DurationMs)); // a key press or a click on the bar
        };
    }

    private void Commit()
    {
        if (!dragging) return;
        dragging = false;
        Player.SeekTo((long)(slider.Value * Player.DurationMs));
    }

    /// <summary>Called a few times a second by the window while it's shown.</summary>
    public void Tick()
    {
        if (dragging) return;
        var d = Player.DurationMs;
        var p = Player.PositionMs;
        setting = true;
        slider.Value = d > 0 ? Math.Clamp((double)p / d, 0, 1) : 0;
        setting = false;
        elapsed.Text = Format.Time(p);
        total.Text = Format.Time(d);
    }
}

/// <summary>A speaker and a volume slider.</summary>
public sealed class VolumeBar : Grid
{
    private readonly Slider slider = new() { Minimum = 0, Maximum = 100, StepFrequency = 1, VerticalAlignment = VerticalAlignment.Center };
    private readonly FontIcon icon = new() { FontSize = 16, VerticalAlignment = VerticalAlignment.Center };
    private bool setting;

    public VolumeBar(Brush? tint = null)
    {
        icon.Foreground = tint ?? (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        ColumnSpacing = 10;
        SetColumn(slider, 1);
        Children.Add(icon);
        Children.Add(slider);
        slider.ValueChanged += (_, e) => { if (!setting) Player.SetVolume((int)e.NewValue, save: false); };
        // Saved once the slider is let go, not on every step.
        slider.AddHandler(PointerReleasedEvent, new PointerEventHandler((_, _) => Player.SetVolume((int)slider.Value, save: true)), true);
        slider.AddHandler(PointerCaptureLostEvent, new PointerEventHandler((_, _) => Player.SetVolume((int)slider.Value, save: true)), true);
        Loaded += (_, _) => { Player.StateChanged += Refresh; Refresh(); };
        Unloaded += (_, _) => Player.StateChanged -= Refresh;
    }

    private void Refresh()
    {
        setting = true;
        slider.Value = Player.Volume;
        setting = false;
        icon.Glyph = Player.Volume switch
        {
            0 => "\uE74F",
            < 34 => "\uE993",
            < 67 => "\uE994",
            _ => "\uE995",
        };
    }
}

/// <summary>
/// A heart for the song that's playing: filled in the accent when it's liked. Pressing it likes
/// or unlikes the song (Liked songs on Home).
/// </summary>
/// <summary>
/// A heart, outline or filled. Liking pops it the way Apple's does: it dips, swells past its size
/// and settles, while a thin ring flashes out from it. Unliking shrinks it a little and back.
/// The motion runs on the compositor, so it stays smooth and costs the app nothing.
/// </summary>
public sealed class HeartIcon : Grid
{
    private readonly FontIcon glyph;
    private readonly Ellipse ring;
    private readonly float half;
    private bool? shown;

    public HeartIcon(double size)
    {
        Width = Height = size;
        half = (float)size / 2;
        glyph = new FontIcon { FontSize = size, Width = size, Height = size, CenterPoint = new Vector3(half, half, 0) };
        ring = new Ellipse
        {
            Width = size,
            Height = size,
            StrokeThickness = 1.5,
            Stroke = (Brush)Application.Current.Resources["AccentBrush"],
            Opacity = 0,
            IsHitTestVisible = false,
            CenterPoint = new Vector3(half, half, 0),
        };
        Children.Add(ring);
        Children.Add(glyph);
    }

    /// <summary>Shows the heart; <paramref name="animate"/> plays the pop if it changed.</summary>
    public void Set(bool liked, Brush idle, bool animate)
    {
        glyph.Glyph = liked ? "" : "";
        glyph.Foreground = liked ? (Brush)Application.Current.Resources["AccentBrush"] : idle;
        var changed = shown != null && shown != liked;
        shown = liked;
        if (!animate || !changed) return;
        var c = ElementCompositionPreview.GetElementVisual(this).Compositor;
        var ease = c.CreateCubicBezierEasingFunction(new Vector2(0.3f, 0f), new Vector2(0.2f, 1f));
        var pop = c.CreateVector3KeyFrameAnimation();
        pop.Target = "Scale";
        if (liked)
        {
            pop.Duration = TimeSpan.FromMilliseconds(520);
            pop.InsertKeyFrame(0f, new Vector3(1, 1, 1));
            pop.InsertKeyFrame(0.18f, new Vector3(0.72f, 0.72f, 1), ease);
            pop.InsertKeyFrame(0.5f, new Vector3(1.28f, 1.28f, 1), ease);
            pop.InsertKeyFrame(0.75f, new Vector3(0.94f, 0.94f, 1), ease);
            pop.InsertKeyFrame(1f, new Vector3(1, 1, 1), ease);

            var grow = c.CreateVector3KeyFrameAnimation();
            grow.Target = "Scale";
            grow.Duration = TimeSpan.FromMilliseconds(480);
            grow.InsertKeyFrame(0f, new Vector3(0.6f, 0.6f, 1));
            grow.InsertKeyFrame(0.15f, new Vector3(0.6f, 0.6f, 1));
            grow.InsertKeyFrame(1f, new Vector3(1.9f, 1.9f, 1), ease);
            var fade = c.CreateScalarKeyFrameAnimation();
            fade.Target = "Opacity";
            fade.Duration = TimeSpan.FromMilliseconds(480);
            fade.InsertKeyFrame(0f, 0f);
            fade.InsertKeyFrame(0.15f, 0.9f);
            fade.InsertKeyFrame(1f, 0f, ease);
            ring.StartAnimation(grow);
            ring.StartAnimation(fade);
        }
        else
        {
            pop.Duration = TimeSpan.FromMilliseconds(260);
            pop.InsertKeyFrame(0f, new Vector3(1, 1, 1));
            pop.InsertKeyFrame(0.4f, new Vector3(0.8f, 0.8f, 1), ease);
            pop.InsertKeyFrame(1f, new Vector3(1, 1, 1), ease);
        }
        glyph.StartAnimation(pop);
    }
}

public sealed class LikeButton : Button
{
    private readonly HeartIcon heart = new(16);
    private readonly Brush tint;
    private Track? showing;

    public LikeButton(Brush? tint = null, double size = 40)
    {
        this.tint = tint ?? (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
        Content = heart;
        Style = (Style)Application.Current.Resources["IconButton"];
        Width = size;
        Height = size;
        Click += (_, _) =>
        {
            if (Player.Current is not { } t) return;
            if (LibraryStore.IsLiked(t)) LibraryStore.Unlike(t); else LibraryStore.Like(t);
        };
        Loaded += (_, _) =>
        {
            Player.TrackChanged += Refresh;
            LibraryStore.Changed += Refresh;
            Refresh();
        };
        Unloaded += (_, _) =>
        {
            Player.TrackChanged -= Refresh;
            LibraryStore.Changed -= Refresh;
        };
    }

    private void Refresh()
    {
        var t = Player.Current;
        var liked = t != null && LibraryStore.IsLiked(t);
        // It pops only when this song's heart changes, not when the next song comes on.
        heart.Set(liked, tint, animate: t != null && t == showing);
        showing = t;
        IsEnabled = t != null;
        var name = liked ? "Remove from Liked songs" : "Like";
        ToolTipService.SetToolTip(this, name);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, name);
    }
}
