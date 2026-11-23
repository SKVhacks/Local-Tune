using Microsoft.UI.Dispatching;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;

namespace LocalTune.Core;

/// <summary>
/// The queue and the playing song, on Windows' own media engine. It also
/// feeds Windows' media controls: the volume flyout, the lock screen and the
/// keyboard's media keys all show and control what's playing.
///
/// The engine reports on its own threads; everything here is handed on to
/// the UI thread before anything changes, so the app only ever sees it there.
/// </summary>
public static class Player
{
    private static MediaPlayer? mp;
    private static DispatcherQueue? ui;
    private static DispatcherQueueTimer? saveTimer;

    public static Track? Current { get; private set; }
    public static bool IsPlaying { get; private set; }
    public static long DurationMs { get; private set; }

    /// <summary>In play order: shuffled when shuffle is on.</summary>
    public static List<Track> Queue { get; private set; } = new();
    public static int Index { get; private set; }

    public static bool Shuffle { get; private set; } = Settings.Value.Shuffle;
    public static RepeatMode Repeat { get; private set; } = Settings.Value.Repeat;
    public static int Volume { get; private set; } = Settings.Value.Volume;

    /// <summary>The song changed.</summary>
    public static event Action? TrackChanged;
    /// <summary>Playing or paused, shuffle, repeat or volume changed.</summary>
    public static event Action? StateChanged;
    /// <summary>What's queued changed.</summary>
    public static event Action? QueueChanged;
    /// <summary>The listener moved to another place in the song (a lyric line, the progress bar, a key).</summary>
    public static event Action? Seeked;
    /// <summary>A short note for the listener, such as a song that couldn't play.</summary>
    public static event Action<string>? Message;

    /// <summary>The queue as it was picked, before shuffling, to go back to.</summary>
    private static List<Track> original = new();

    /// <summary>Where to go once the file is open: a seek asked for before it was ready.</summary>
    private static long? pendingSeekMs;

    // ---- Counting listens, as the phone does ----

    /// <summary>How long the playing song has actually been heard (paused time isn't counted).</summary>
    private static readonly System.Diagnostics.Stopwatch heard = new();
    /// <summary>The playing song's entry in History once it counts as played; null before.</summary>
    private static long? listenRecord;
    private static DispatcherQueueTimer? listenTimer;
    private static int listenTicks;

    /// <summary>Counts loads, so a slow one finishing after a newer one is ignored.</summary>
    private static int loadId;

    public static void Start(DispatcherQueue dispatcher)
    {
        ui = dispatcher;
        mp = new MediaPlayer
        {
            AudioCategory = MediaPlayerAudioCategory.Media,
            Volume = Volume / 100.0,
            AutoPlay = false,
        };
        var commands = mp.CommandManager;
        commands.IsEnabled = true;
        commands.NextBehavior.EnablingRule = MediaCommandEnablingRule.Always;
        commands.PreviousBehavior.EnablingRule = MediaCommandEnablingRule.Always;
        commands.NextReceived += (_, e) => { e.Handled = true; Ui(Next); };
        commands.PreviousReceived += (_, e) => { e.Handled = true; Ui(Previous); };

        mp.PlaybackSession.PlaybackStateChanged += (s, _) => Ui(() =>
        {
            var playing = s.PlaybackState == MediaPlaybackState.Playing;
            if (playing) heard.Start(); else heard.Stop();
            if (playing == IsPlaying) return;
            IsPlaying = playing;
            StateChanged?.Invoke();
        });
        mp.MediaOpened += (p, _) => Ui(() =>
        {
            var d = (long)p.PlaybackSession.NaturalDuration.TotalMilliseconds;
            if (d > 0) DurationMs = d;
            if (pendingSeekMs is { } at && at > 0)
            {
                p.PlaybackSession.Position = TimeSpan.FromMilliseconds(at);
            }
            pendingSeekMs = null;
            StateChanged?.Invoke();
        });
        mp.MediaEnded += (_, _) => Ui(() => Advance(auto: true));
        mp.MediaFailed += (_, e) => Ui(() =>
        {
            Message?.Invoke($"Couldn't play {Current?.Title ?? "this song"}, skipped it");
            Advance(auto: true);
        });

        Restore();
        // The place in the queue is kept, so closing the app mid-song picks up there next time.
        saveTimer = dispatcher.CreateTimer();
        saveTimer.Interval = TimeSpan.FromSeconds(5);
        saveTimer.Tick += (_, _) => { if (IsPlaying) SaveState(); };
        saveTimer.Start();

        listenTimer = dispatcher.CreateTimer();
        listenTimer.Interval = TimeSpan.FromSeconds(1);
        listenTimer.Tick += (_, _) => TrackListening();
        listenTimer.Start();
    }

    /// <summary>A song counts as played after 30 seconds, or half its length if shorter (as on the phone).</summary>
    private static void TrackListening()
    {
        if (Current is not { } t || !IsPlaying) return;
        var ms = heard.ElapsedMilliseconds;
        if (listenRecord == null)
        {
            var threshold = DurationMs > 0 ? Math.Min(30_000, DurationMs / 2) : 30_000;
            if (ms >= threshold) listenRecord = History.Record(t, ms);
        }
        else if (++listenTicks % 5 == 0) History.UpdateListened(listenRecord.Value, ms);
    }

    /// <summary>The last song's heard time written into its listen before the next starts.</summary>
    private static void FinishListen()
    {
        if (listenRecord is { } key) History.UpdateListened(key, heard.ElapsedMilliseconds);
        listenRecord = null;
        listenTicks = 0;
        heard.Reset();
        if (IsPlaying) heard.Start();
    }

    public static void Shutdown()
    {
        FinishListen();
        History.Save();
        SaveState();
        mp?.Dispose();
        mp = null;
    }

    private static void Ui(Action a)
    {
        if (ui == null || ui.HasThreadAccess) a();
        else ui.TryEnqueue(() => a());
    }

    // ---- Position ----

    /// <summary>
    /// Where the song is now, to the millisecond: Windows' own clock. It's right after jumps too,
    /// since FLAC files without a seek table are given one (<see cref="FlacSeek"/>).
    /// </summary>
    public static long PositionMs
    {
        get
        {
            if (pendingSeekMs is { } p) return p;
            return mp?.Source == null ? 0 : (long)mp.PlaybackSession.Position.TotalMilliseconds;
        }
    }

    public static void SeekTo(long ms)
    {
        if (mp == null || Current == null) return;
        var target = Math.Clamp(ms, 0, DurationMs > 0 ? DurationMs : long.MaxValue);
        if (pendingSeekMs != null) pendingSeekMs = target;
        else mp.PlaybackSession.Position = TimeSpan.FromMilliseconds(target);
        Seeked?.Invoke();
    }

    // ---- Playing ----

    /// <summary>Plays <paramref name="tracks"/> from <paramref name="start"/>, shuffled if asked (with the start first).</summary>
    public static void Play(IReadOnlyList<Track> tracks, int start = 0, bool? shuffled = null)
    {
        if (tracks.Count == 0) return;
        var shuffle = shuffled ?? Shuffle;
        start = Math.Clamp(start, 0, tracks.Count - 1);
        original = tracks.ToList();
        var first = tracks[start];
        if (shuffle != Shuffle)
        {
            Shuffle = shuffle;
            Settings.Update(s => s.Shuffle = shuffle);
        }
        if (shuffle)
        {
            var rest = original.Where((_, i) => i != start).OrderBy(_ => Random.Shared.Next()).ToList();
            Queue = new List<Track> { first }.Concat(rest).ToList();
            Load(0, autoplay: true);
        }
        else
        {
            Queue = original.ToList();
            Load(start, autoplay: true);
        }
        QueueChanged?.Invoke();
    }

    public static void PlayAt(int queueIndex)
    {
        if (queueIndex >= 0 && queueIndex < Queue.Count) Load(queueIndex, autoplay: true);
    }

    public static void Toggle()
    {
        if (mp == null || Current == null) return;
        if (mp.Source == null) Load(Index, autoplay: true);
        else if (IsPlaying) mp.Pause();
        else mp.Play();
    }

    public static void Next() => Advance(auto: false);

    public static void Previous()
    {
        if (PositionMs > 3_000 || Index == 0) SeekTo(0);
        else Load(Index - 1, autoplay: IsPlaying);
    }

    public static void PlayNext(Track track)
    {
        if (Queue.Count == 0) { Play(new[] { track }); return; }
        Queue.Insert(Index + 1, track);
        original.Add(track);
        QueueChanged?.Invoke();
        Message?.Invoke($"â€œ{track.Title}â€ plays next");
    }

    public static void AddToQueue(Track track)
    {
        if (Queue.Count == 0) { Play(new[] { track }); return; }
        Queue.Add(track);
        original.Add(track);
        QueueChanged?.Invoke();
        Message?.Invoke($"Added â€œ{track.Title}â€ to the queue");
    }

    public static void SetShuffle(bool on)
    {
        Shuffle = on;
        Settings.Update(s => s.Shuffle = on);
        var cur = Current;
        if (cur != null && Queue.Count > 0)
        {
            if (on)
            {
                var rest = Queue.Where((_, i) => i != Index).OrderBy(_ => Random.Shared.Next()).ToList();
                Queue = new List<Track> { cur }.Concat(rest).ToList();
                Index = 0;
            }
            else
            {
                Queue = original.ToList();
                Index = Math.Max(0, Queue.IndexOf(cur));
            }
            QueueChanged?.Invoke();
        }
        StateChanged?.Invoke();
    }

    public static void CycleRepeat()
    {
        Repeat = (RepeatMode)(((int)Repeat + 1) % 3);
        Settings.Update(s => s.Repeat = Repeat);
        StateChanged?.Invoke();
    }

    public static void SetVolume(int v, bool save)
    {
        Volume = Math.Clamp(v, 0, 100);
        if (mp != null) mp.Volume = Volume / 100.0;
        if (save) Settings.Update(s => s.Volume = Volume);
        StateChanged?.Invoke();
    }

    private static void Advance(bool auto)
    {
        if (Queue.Count == 0) return;
        if (auto && Repeat == RepeatMode.One) Load(Index, autoplay: true);
        else if (Index + 1 < Queue.Count) Load(Index + 1, autoplay: true);
        else if (Repeat == RepeatMode.All) Load(0, autoplay: true);
        // The end of the queue keeps going, as Apple Music's Autoplay does: songs that go with
        // what was just played are added, and play on.
        else if (Autoplay()) Load(Index + 1, autoplay: true);
        // Nothing left to add (a library of one album): back to the first song, waiting.
        else Load(0, autoplay: false);
    }

    /// <summary>
    /// Adds about twenty-five songs that go with the end of the queue (the same artists first,
    /// then the same albums, then songs often played here), none already in it and none snoozed.
    /// </summary>
    private static bool Autoplay()
    {
        var lib = MusicLibrary.Current;
        var seeds = Queue.Skip(Math.Max(0, Queue.Count - 12)).Reverse().ToList();
        var skip = Queue.Select(t => t.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        foreach (var (path, until) in Settings.Value.Snoozed) if (until > now) skip.Add(path);
        var more = Suggestions.After(seeds, 25, skip, new Random(), lib, History.PlayCounts(lib));
        if (more.Count == 0) return false;
        Queue.AddRange(more);
        original.AddRange(more);
        QueueChanged?.Invoke();
        Message?.Invoke("Autoplay: playing similar songs");
        return true;
    }

    private static async void Load(int i, bool autoplay, long fromMs = 0)
    {
        if (mp == null || i < 0 || i >= Queue.Count) return;
        var id = ++loadId;
        var track = Queue[i];
        var changed = Current != track;
        // A new listen starts with every load, even of the same song again (repeat one).
        FinishListen();
        Index = i;
        Current = track;
        DurationMs = track.DurationMs;
        pendingSeekMs = fromMs;
        if (changed) TrackChanged?.Invoke();
        StateChanged?.Invoke();
        try
        {
            // A FLAC without a seek table is given one, so jumps land exactly (see FlacSeek).
            var exact = await Task.Run(() => FlacSeek.Open(track.Path));
            if (id != loadId) { exact?.Dispose(); return; }
            var source = exact != null
                ? MediaSource.CreateFromStream(exact, "audio/flac")
                : MediaSource.CreateFromStorageFile(await StorageFile.GetFileFromPathAsync(track.Path));
            if (id != loadId) return;
            var item = new MediaPlaybackItem(source);
            ApplyDisplay(item, track, null);
            mp.Source = item;
            if (autoplay) mp.Play();
            SaveState();
            var thumb = await Artwork.ThumbnailAsync(track);
            if (id == loadId && thumb != null) ApplyDisplay(item, track, thumb);
        }
        catch (Exception)
        {
            if (id != loadId) return;
            pendingSeekMs = null;
            Message?.Invoke($"Couldn't open {track.Title}, skipped it");
            if (Queue.Count > 1 && autoplay) Advance(auto: true);
        }
    }

    /// <summary>What Windows' media controls show for the song.</summary>
    private static void ApplyDisplay(MediaPlaybackItem item, Track track, Windows.Storage.Streams.RandomAccessStreamReference? thumb)
    {
        var props = item.GetDisplayProperties();
        props.Type = MediaPlaybackType.Music;
        props.MusicProperties.Title = track.Title;
        props.MusicProperties.Artist = track.Artist;
        props.MusicProperties.AlbumTitle = track.Album;
        props.MusicProperties.AlbumArtist = track.AlbumArtist;
        if (thumb != null) props.Thumbnail = thumb;
        item.ApplyDisplayProperties(props);
    }

    // ---- The last session ----

    private static void Restore()
    {
        var s = Settings.Value;
        if (s.Queue.Count == 0) return;
        var lib = MusicLibrary.Current;
        var tracks = s.Queue.Select(p => lib.Track(p)).OfType<Track>().ToList();
        if (tracks.Count == 0)
        {
            // No library cache yet (a first start, or it was cleared): try again once the folders are read.
            if (MusicLibrary.Scanning && Current == null)
            {
                void Retry()
                {
                    if (MusicLibrary.Scanning) return;
                    MusicLibrary.Changed -= Retry;
                    if (Current == null) Restore();
                }
                MusicLibrary.Changed += Retry;
            }
            return;
        }
        original = tracks;
        Queue = tracks.ToList();
        QueueChanged?.Invoke();
        Load(Math.Clamp(s.QueueIndex, 0, tracks.Count - 1), autoplay: false, fromMs: s.PositionMs);
    }

    private static void SaveState()
    {
        var q = Queue.Select(t => t.Path).ToList();
        var index = Index;
        var pos = PositionMs;
        Settings.Update(s =>
        {
            s.Queue = q;
            s.QueueIndex = index;
            s.PositionMs = pos;
        });
    }
}
