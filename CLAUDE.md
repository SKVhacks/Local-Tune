# LocalTune: notes for Claude Code

LocalTune is SKVhacks's fully offline Android music player for local files, made to look and behave like Apple Music. It's a GPL-3.0 fork of OpenTune by justshivv. This file carries the context of the long design chat that built it, so a new session can pick up where that one left off.

## Project basics

- **Repository:** https://github.com/SKVhacks/Local-Tune (main). Never push to justshivv's upstream.
  - On 2026-11-24 the user deleted the old Local-Tune repository and recreated it empty under the same name. The whole history plus the clean layout was pushed as commit a95f0de.
  - The README (by Claude, approved by the user) shows `Screenshot/` (Mobile-View, PC-View, thumbnail.png). The phone Home shot shows the user's name; they were told, and published it anyway.
  - Git identity for this repository only: `Gadget_Vishwa <90371639+SKVhacks@users.noreply.github.com>`, the user's GitHub noreply address from their own earlier commits. There's no global git identity on this PC.
  - The four signing secrets must be added to the new repository (they were on the deleted one).
- **Layout (since 2026-11-24):**
  - `android/`: the Gradle project (app, core, wrapper).
  - `windows/`: LocalTune and the installer.
  - `third_party/`: font licences.
  - `.github/workflows/build.yml`.
  - README, LICENSE, THIRD_PARTY_NOTICES and this file.
- **Removed in that clean-up (all OpenTune leftovers, unused):**
  - `scripts/` (website and Spotify tools).
  - `docs/` (OpenTune's notes, screenshots and a 10 MB video).
  - `fastlane/` (OpenTune's store listing). `WhatsNew` handles the missing changelog.
  - `ui/components/MarkRefresh.kt` and `data/history/SpeedDial.kt`, which nothing referenced.
  - `THIRD_PARTY_NOTICES.md` was rewritten to credit only what LocalTune still contains, Windows included.
  - Leftover OpenTune settings fields (sponsorBlock, jioSaavnQuality…) and comments that mention Innertube remain. They live in saved settings, so remove them only with a full build to check.
- **Stack:**
  - Kotlin and Jetpack Compose.
  - Media3 ExoPlayer 1.11, with Jellyfin's FFmpeg decoder as a fallback.
  - MediaStore for the music library.
  - Gradle builds with R8.
  - Modules: `app` (UI) and `core` (data, playback).
- **Package name:** the Kotlin package is still `com.opentune`, and so is the applicationId. Don't change the applicationId: it would install as a separate app and lose the user's library. The display name is "LocalTune".
- **Build:** `cd android && ./gradlew :app:assembleRelease` produces the APKs in `android/app/build/outputs/apk/release/`.
  - The user's PC can't build Android locally: its SDK lacks platform 36, and its licences aren't accepted. CI builds it.
- **Tests:** `cd android && ./gradlew :core:testReleaseUnitTest`.
- **Signing:** the env vars are `LOCALTUNE_KEYSTORE`, `LOCALTUNE_KEYSTORE_PASSWORD`, `LOCALTUNE_KEY_ALIAS` and `LOCALTUNE_KEY_PASSWORD`. The key is the old debug key (alias `androiddebugkey`, passwords `android`). The user keeps it as `localtune-signing.jks`, and the GitHub secrets hold it too. Never commit the key.
- **CI:** `.github/workflows/build.yml`, with two jobs. `android` (in `android/`) produces the APKs as the "LocalTune-android" artifact. `windows` produces the setup exe, the single `LocalTune-x64.exe` and the zip as the "LocalTune-windows" artifact. It runs on:
  - Every code push; changes to `.md` files and images are skipped.
  - Any published release, which attaches the APKs to that release.
  - "Run workflow", where an optional `release_tag` adds the APKs to an older release.
- **Commits** end with a `Co-Authored-By: Claude …` line. Push only when the user says so.

## How the user works

- The user tests every APK on a Xiaomi 14 Civi (HyperOS) and often uses Bluetooth earbuds (AAC or LDAC).
- They send screenshots with red markings and expect exactly what was marked, nothing more.
- If they say "restore" or "undo", revert that change exactly.
- Explanations should be in plain words, not code terms.
- Hand over each build as an APK named `LocalTune-arm64-v8a.apk`.
- They care a lot about animations being as smooth as Apple Music's, and about the phone not heating up.

## Features built in that chat, and decisions to keep

### Lyrics (`ui/player/LyricsView.kt`, `core/.../data/lyrics/`)

- **Lyric files:**
  - `.ttml`, the Apple Music format, read by `Ttml.kt`.
  - `.lrc` and enhanced LRC.
  - `.txt`.
- **Finding a song's lyric file:** a file with the same name as the song, in the same folder or a `Lyrics` subfolder. Lyric folders are granted through Settings › Lyrics › Lyrics folders.
- **Sweep:** only word-timed lines sweep letter by letter. TTML with `itunes:timing="Line"`, and plain LRC, light a whole line at once. There is no setting for this any more.
- **Overlapping lines:** they work like Apple's. A line still being sung stays lit while the next starts (`litRows`, `focusRow`, `holdsOver`, `HOLD_MS = 1000`). Background vocals (`x-bg`) are supported.
- **Instrumental breaks:** the gap dots take no space unless active; they grow from the line's position.
- **No lyrics:** the page says "No lyrics available".

### Player look (`ui/player/`)

- **Flow background (`FlowBackground.kt`):**
  - An AGSL shader on Android 13+, falling back to a cover blur below that.
  - Three turning copies of a 48 px cover; `FLOW_SPEED = 2.2`.
  - It runs at 30 fps, or 15 fps when the user picks the "keep cool" setting.
  - One shared `FlowClock` drives every copy so they stay in step.
- **Tall animated artwork (`TallArtwork.kt`, `core/.../local/AnimatedArtwork.kt`):**
  - A video named like `tall_animated_artwork.mp4` in the song's folder.
  - It's played on a SurfaceView, which runs cooler than a TextureView, at the top of the player, full width, using the video's own aspect ratio.
  - With Flow on, the video's lower part melts into a masked copy of Flow on the same clock.
  - On the way to the lyrics page, a captured frame flies into the lyrics header (`TallFlight`, PixelCopy).
- **Ignored file:** `square_animated_artwork.mp4` isn't used yet.
- **Portrait player:**
  - Removed: the volume bar, the "Playing from" status line, lyrics offset, Romanize, Copy log, the sleep timer and the volume handle.
  - The bottom pill holds only the output button, showing a Cast icon on the phone speaker.
  - The cover is widened by `COVER_WIDEN = 5.dp`.
  - The seek-bar time row is pulled up by 22 dp.
  - The bottom row is inset by 32 dp.
- **Landscape player (`LandscapePlayer.kt`):**
  - The buttons are Lyrics and Cast (the output picker).
  - When lyrics or the queue open, the cover flies into the small cover beside the title (SharedTransitionLayout).
- **Tapping the title or artist** opens a bottom sheet with View album and View artist. There's one View artist entry per name.
- **Song menu (`ui/components/SongMenu.kt`):**
  - The round buttons are Like, Play next, Add to queue and Playlist.
  - Removed: Share, Not interested, Don't play, Set as ringtone, View artist and Open album.
- **Things the user tried and rejected:** Apple-style transport controls (reverted), and a cover-top layout with even gaps (reverted).

### Sound

- **Pure output (`PlaybackSettings.pureOutput`, on by default):** no DSP, the effect session closed, loudness off. Settings hides EQ, Clarity, Spatial, System effects and Loudness while it's on.
- **Quality label (`SourceQualityBadge.kt`):** shows only the tier, plus " - LDAC/AAC…" on Bluetooth. Dolby formats show as text only, with no Dolby logo.
- **Bluetooth codec (`BluetoothCodec.kt`):** read through the hidden `BluetoothA2dp.getCodecStatus` via HiddenApiBypass. It needs the Nearby devices permission.
- **SpatialGuard (`core/.../playback/SpatialGuard.kt`):**
  - Dolby, TrueHD, DTS and MPEG-H never decode through FFmpeg, only through the phone's own decoders or passthrough.
  - A song nothing can play is skipped, with a message.
- **Bit-perfect USB output** exists for Android 14+ DACs.

### Library and Home (`ui/local/LocalScreens.kt`, `core/.../local/LocalMusic.kt`)

- **Tabs:** Home, Songs, Albums and Settings.
- **Home:**
  - A greeting with the user's name.
  - Shelves: Recently played, Artists (round), Playlists, Liked songs, Albums and Folders.
  - A Replay card.
- **Artist pictures (`ArtistImages.kt`):**
  - First taken from tags inside the files (ID3 APIC / FLAC PICTURE types 7, 8, 10, 9, 11, 12).
  - Otherwise fetched from Deezer's public API, if allowed in Settings › Appearance.
- **Artist page:** a hero image taking 40% of the height, blurred at the bottom, with the name over it.
- **Album page:** "More by <artist>" shelves, one per artist, at the bottom.
- **Splitting artist names (`LocalMusic.splitArtists`):** names are split on `,` `;` `/` ` & ` ` x ` `feat.` `ft.` and `featuring`.
- **Re-linking saved songs:**
  - Saved songs are stored by MediaStore ID, which changes after a reinstall, a restore on another phone or a rescan.
  - `Library.find` matches a song by ID, then by title plus artist, then by a unique title.
  - `LocalMusic.relinkSaved()` rewrites likes, playlists and history after the library loads.
  - `PlaybackService.recover()` re-links the queue on FILE_NOT_FOUND.
- **Backup:**
  - Settings › Music and storage › Backup exports `localtune-backup-YYYY-MM-DD.json`.
  - The file holds the library only: `{app:"LocalTune", format:"localtune-library", version:2, exportedAt, library, history}`. There are no settings in it, so the Windows app reads the same file. Songs are matched by title and artist.
  - Import only accepts backups from LocalTune or the old OpenTune, ignores the `settings` part of older backups, and checks the whole file before changing anything.
  - `version` is 2 here, while the Windows export writes 1. Both sides should accept either.
- **Home on Android (`HomePicks` in `android/app/.../ui/local/LocalScreens.kt`):**
  - The order is: Recently played, "Recommended songs for you", "Suggested albums", Artists, Playlists, Folders, Replay.
  - "Recommended songs for you" holds 20 songs from `android/core/.../data/recommend/Recommender.kt`. It's pure Kotlin with tests, and scores songs on:
    - Taste for the artist, album and folder, with a 3-week recency half-life. A song skipped before a third of it plays counts against.
    - Songs played together in one sitting.
    - Likes, plus a small bonus for songs not heard yet.
    - Songs played in the last 36 hours are held back.
    - At most 2 songs per artist or album.
  - "Suggested albums" is 15 random albums. Both shelves are picked once per app start, from a process seed.
  - Liked songs is the first tile in Playlists: a heart on a pink-to-orange gradient. It has no shelf of its own.
  - Tapping a song in Recently played plays it, then about 30 songs that go with it (`HomePicks.after` → `Recommender.recommend(after = id)`), not the history list.
- **Lost changes (history):** these Android features were left out when the code moved into `android/` (that copy came from commit `da381f5`). Commit `3745b1c` put them back. When moving or copying code, always start from the latest `main`.

### Other

- **Permissions:** `ui/StartupPermissions.kt` asks for audio, video, Nearby devices and notifications together when the app opens.
- **Offline:** the "no internet" banner and toast are removed (`ConnectionBanner` is a pass-through).
- **Settings:** options that no longer do anything were removed.
- **Credits:** Settings › About › "Made by SKVhacks" links to `DEVELOPER_PORTFOLIO`, which is the user's GitHub for now.
- **Source code:** the About link points to Local-Tune.
- **Licence:** the OpenTune credit must stay, because of the GPL.

## LocalTune for Windows (`windows/`)

The user wants a **native** Windows app: Compose Multiplatform was tried and rejected. It's WinUI 3 (Windows App SDK 2.7) in C# on .NET 10, and shares no code with the phone app.

- **Build and run:** `dotnet build windows/LocalTune -p:Platform=x64`. The app is in `windows/LocalTune/bin/x64/Debug/.../win-x64/LocalTune.exe`.
- **Release:** `dotnet publish windows/LocalTune -c Release -p:Platform=x64`. It's a plain self-contained .exe folder (no MSIX).
  - `EnableMsixTooling` must stay on, or publish leaves out the compiled layouts and the window fails to load.
  - Only the WinUI, InteractiveExperiences and Foundation packages are referenced. The full `Microsoft.WindowsAppSDK` package adds its AI runtimes (over 100 MB).
  - Language folders other than English are deleted after publish.
- **Single portable .exe (the user asked for one; CI also makes it, as `LocalTune-x64.exe`):**
  - Build: `dotnet publish windows/LocalTune -c Release -p:Platform=x64 -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:IncludeAllContentForSelfExtract=true -p:EnableCompressionInSingleFile=true -o windows/installer/Output/single`, then delete the .pdb.
  - The result is one `LocalTune.exe` of about 67 MB. It was tested alone in an empty folder.
  - On first start it unpacks itself to the temp folder, so that start is slower.
  - **`ProjectPriFileName=resources.pri` is required.** WinUI looks for "<exe name>.pri", or `resources.pri`. With the default `LocalTune.pri`, CI's rename to `LocalTune-x64.exe` made release 1.0's single exe crash at start ("XAML parsing failed" in `MainWindow.InitializeComponent`). The user reported it as "won't open".
  - With `resources.pri`, any exe name works (tested). The installer and the zip were fine all along.
  - Inno Setup isn't installed on the user's PC; the setup .exe comes from CI.
- **Installer:** `windows/installer/LocalTune.iss` (Inno Setup). It installs per user, with no admin prompt. CI's `windows` job builds `LocalTune-Setup-x64.exe` and `LocalTune-windows-x64.zip`. Never change the installer's AppId.
- **Data:** settings, the library cache and `crash.log` are in `%APPDATA%\LocalTune`.
- **Playback (`Core/Player.cs`):** Windows' own `MediaPlayer`. It feeds Windows' media controls, the lock screen and the media keys.
- **Library (`Core/Library.cs`):** read with TagLibSharp. Artist names are split with the phone's rule.
- **Lyrics (`Lyrics/`):** a line-by-line C# port of `Lyrics.kt` and `Ttml.kt`. When one changes, change the other. `LyricFiles.cs` finds lyric files the same way the phone does.
- **Lyrics view (`Views/LyricsView.cs`):** follows the phone's rules (`litRows`, `focusRow`, `HOLD_MS`, letter sweep only on word-timed lines, gap dots).
  - Lines glide with a composition animation on `Translation`.
  - It redraws every frame only while the full player is open and playing.
- **Theme:** the app follows Windows' light or dark mode, with no RequestedTheme in App.xaml.
  - When Windows switches, `MainWindow.RebuildForTheme` rebuilds the player bar, the queue and the page, because code-built parts take their colours when they're made.
  - The caption buttons follow through `TitleBar.PreferredTheme`.
  - The full player is always dark, as Apple's is (`RequestedTheme = Dark` on NowPlayingView).
  - The user's Windows apps are in light mode.
- **Layout:** sidebar, a player bar along the bottom, and a full player with the cover on the left and lyrics on the right.
- **Sidebar (Apple Music style, user's request):**
  - Search, then Home.
  - "Library": Songs, Albums, Artists, Folders.
  - "Playlists": Liked songs, each playlist, then New playlist. These are filled from code by `FillPlaylists` and refilled on `LibraryStore.Changed`.
  - Icons are red. There's no selection bar, just the grey rounded highlight (`NavigationViewSelectionIndicatorForeground` is transparent). Items are 36 px tall.
  - The headings must be plain `Content` strings: a TextBlock as content shows nothing.
  - The ☰ button opens and closes it (closed shows icons only). The choice is kept in `Settings.SidebarOpen`.
  - **No back button in the sidebar** (`IsBackButtonVisible="Collapsed"`): the user found it misaligned there. The back button (`BackButton`) sits at the top left of the content area. It shows only when there's somewhere to go back to, and the page moves down 22 px to make room.
- **Full player (`Views/NowPlayingView.cs`):** copied from the user's screenshots of Apple Music for Windows.
  - The Flow background (`Views/FlowBackground.cs`).
  - The title and "Artist — Album" centred under the cover.
  - A thin white progress line with the time left.
  - Volume and "…" on the left, back/play/next in the middle, and a lyrics toggle on the right.
  - **Full screen:** it opens with the `FullScreen` presenter, so there's no title bar, no window buttons and no resizing. The user wanted it like Apple's.
    - The old ⌄ close button was removed at the user's request. Apple's "exit full screen" button (top right) or Esc leaves it, and the window goes back to overlapped.
  - **Lyrics toggle:** the lyrics fade out (350 ms) while the cover and controls glide to the screen's centre (520 ms storyboard on a `TranslateTransform`). Showing them reverses it.
  - **No lyric file (`CheckLyrics`, `hasLyrics`):** the cover and controls glide to the centre and the lyrics button greys out (disabled, 35% opacity, "No lyrics for this song"). It switches per song. The next song with lyrics brings them back, as the user last left the toggle.
  - **Quality badge** under "Artist — Album":
    - "Lossless", or "Hi-Res Lossless" for 24-bit above 48 kHz, with the Apple Lossless logo before the words: the Wikimedia Commons outline, public domain as a shape and an Apple trademark, in `LosslessLogoPath`. Dolby formats show as text only.
    - Hovering shows, for example, "24-bit/48 kHz ALAC".
    - TagLib gives no bit depth for ALAC, so `WithAlacDetails` reads it from the 36-byte `alac` config box in `moov`. The library cache is at version 2.
- **Flow on Windows (`Views/FlowBackground.cs`):** the phone's `FLOW_AGSL` maths, line for line, worked out for a 192 px wide picture that Windows stretches.
  - The user said it must look exactly like the phone's.
  - A compositor approximation (gradient masks, no warp) was tried and rejected as not matching.
  - It moves in 30 steps a second, only while playing.
- **Heat (release build, 144 Hz screen):** Flow alone costs about 13% of one core; Flow plus lyrics about 30%.
  - The lyrics update 60 times a second from a timer. `CompositionTarget.Rendering` at 144 Hz cost about a third of a core by itself.
  - The user's PC has a 144 Hz NVIDIA/AMD hybrid laptop screen.
- **TTML one-word lines:** a line with one timed word (an ad-lib like "Yeah" over the next line) is word-timed unless the file says `itunes:timing="Line"`. This is the same in `Ttml.kt` and `Ttml.cs`, and is tested in `LocalLyricFormatsTest.oneWordAdLibLinesAreWordTimed`.
- **Sung lines hide (Windows only):** the user asked for this. Once a line's highlight is finished, it stays dimmed for 0.3 s (`HideDelay`, the user's choice), then leaves over 0.75 s, fading while it lifts 14 px and shrinks to 96% (`HidePast`, `Hide`).
  - While the user scrolls the lyrics, all lines show again, dimmed. They hide again when following resumes.
- **Exact jumps in FLAC (`Core/FlacSeek.cs`):** none of the user's FLAC files has a seek table (about nine in ten songs).
  - Without one, Windows guesses from the average bitrate. Measured against an ffmpeg decode, the sound landed 3 s early to 4.4 s late, different for every song and spot. The reported position was off by another amount, from the cover picture's size. So lyrics were wrong after any jump.
  - An earlier fix counted the time with the app's own clock (`ownClock`) from the target. It was wrong, because the sound itself doesn't land on the target. It's removed.
  - Now, when a FLAC without a table loads, the app reads its frame headers (6 to 34 ms on the user's NVMe drive) and builds a table with a point per second. Frames are chained, so each must start on the sample the last one ended. MediaPlayer gets a stream: a new header (STREAMINFO, the table, the tags, no pictures or padding) followed by the file's audio, read from disk.
  - With it, jumps land within 0.05 s and `PlaybackSession.Position` is right, so `PositionMs` is just Windows' clock. The user's files are never written.
  - m4a and mp3 were already exact.
  - Testing jumps without playing aloud: decode through AudioGraph's `MediaSourceAudioInputNode` (no output), and match against `ffmpeg -ss` output. The user's PC may be in use (a game was open once), so never click or mute anything during tests.
- **24/96 to the user's headphone (declined, 2026-11-22):** the realme TechLife Studio H1 connects to the PC over Bluetooth.
  - Windows takes only 16-bit/48 kHz for it, even in exclusive mode, and has no LDAC.
  - The user chose to leave it, so there's no exclusive-mode engine.
  - The Realtek wired jack takes 16/24-bit up to 192 kHz in exclusive mode, if this ever comes back.
- **Seeks snap the lyrics:** any seek (`Player.Seeked`) returns the view to following the song and hides the skipped lines at once.
- **The lyric list moves by a `TranslateTransform` storyboard, not the composition `Translation`:** clicks don't follow the composition `Translation`, so tapping a line played a different one.
- **No blink when a word-timed line starts:** it turns bright at once (no fade), because its letters go dim ("not sung yet") the same moment. Fading both made every line blink. Line-timed lines still fade up.
- **"Written by:" after the last lyric line (`LyricFiles.Writers`):** small and dim, as Apple shows it, and below plain lyrics too.
  - The names come from TTML `<songwriter>` (every TTML in the library has them), else an LRC `[au:]` line, else the song's composer tag.
  - These are kept outside the `Ttml.cs` port, so the phone parser rule isn't touched.
- **Lyric look:** bold, sized to the column (`LyricSize`), so long lines wrap. Backing vocals have no brackets. The sweep has one soft edge per line, at the singing front.
- **Home (`Views/HomePages.cs`), the first section:** the shelves, in the order the user asked for:
  1. Recommended songs for you (heading set by the user): `Core/Suggestions.cs`, a port of `LocalSuggestions`. The seeds are the last 12 songs heard. The random seed comes from the history, so the shelf changes with each new listen and refills in place on `History.Changed`.
  2. Suggested albums (heading set by the user), placed right under Recommended at the user's request: 15 at random, picked once per run of the app (`SessionSeed`). The pick stays while the app is open and changes on restart.
  3. Recently played.
  4. Playlists: Liked songs (`LikedPage`) first, then playlists, then New playlist. Import is only in Settings, by the user's choice.
  5. Artists: most songs first, as round tiles with the artist's own picture (`Core/ArtistImages.cs`, a port of `ArtistImages.kt`).
     - The picture comes from the songs' tags first (picture types 7, 8, 10, 9, 11, 12), else Deezer if Settings allows it (on by default).
     - Results, including "none", are cached in `%APPDATA%\LocalTune\artist-pictures`.
  6. Folders: plain covers, no folder mark, by the user's choice.
  7. A Replay card, leading to `ReplayPage`, which is a port of `Wrapped` in `Core/Replay.cs`.
- **History and playlists use the phone's own JSON format:** `history.json` and `library-store.json`, in `Core/History.cs` and `Core/LibraryStore.cs`.
  - A listen counts after 30 s, or half the song if shorter.
  - Songs saved on this PC use the id `file:<path>`. Phone songs (`local:123`) are matched by title and main artist, as the phone's `Library.find` does.
- **Export (`Backup.Write`):** writes `{app: "LocalTune", version: 1, history, library}` in the phone's format, with no settings. The phone and other PCs import it. Songs are matched by title and artist on the other device.
- **Storage (Settings, `StorageCard`):** worked out in the background. Your music (song files, plus lyric files beside them) and LocalTune's folder item by item: artist-picture cache, library index, history, playlists, settings. There's an "Open LocalTune's folder" button.
  - On 2026-11-23: 51.1 GB of music, 8.1 MB of lyrics, and 127 MB of app data. 127 MB of that is artist pictures, cached at full size (up to about 480 KB each). Shrinking them on save would be an easy win if the user wants it.
- **Autoplay at the end of the queue (`Player.Autoplay`):** the user asked for it after an album ended in silence.
  - When the queue runs out (Repeat off), about 25 songs that go with its last dozen are added (`Suggestions.After`: same artists, same albums, often played, anything). Songs already queued and snoozed songs are left out.
  - It shows "Autoplay: playing similar songs" and plays on. This applies to Next at the end too.
- **Clear my data (Settings):** clears history, playlists and likes only. Music files and folders are never touched.
- **Likes:** the song menu has Like / Remove from Liked songs.
- **Phone backup import (`Backup.Read` then `Apply`):**
  - It reads `history` and `library`, not `settings`, and merges them without doubling anything.
  - The merge must run on the UI thread, because its change events rebuild pages.
  - The user has imported their real phone backup (`Downloads\opentune-backup.json`).
- **Sidebar:** uses `ItemInvoked`, not `SelectionChanged`, so clicking the current section from a sub-page still goes back to it.
- **Hover play on Home (`HoverPlay`):**
  - Playlist, Liked, album and folder tiles show a round accent play button at the cover's bottom left while hovered. The button plays at once; the rest of the tile opens the page.
  - Song tiles (Recommended, Recently played) show a centred play mark, and the tile itself plays.
- **Recommended / Recently played refills animate (`HomePage.Swap`):** a shelf whose songs changed fades its tiles out with an 18 px drift left (200 ms). The new tiles then fade in and rise from 28 px right, each 45 ms after the last. It runs on the compositor and skips shelves whose songs are the same.
- **Add to playlist (`PlaylistMenu.Show`, glyph ``, a plus in a circle):** in the player bar right after the heart, in the mini player, and as an "Add to playlist ›" submenu in the full player's "…" menu.
- **Player bar buttons (right side):** heart, Add to playlist, queue, the volume slider, then the mini player button (pop-up glyph ``). The lyrics button was removed at the user's request; lyrics are reached through the full player.
- **Player bar colour (`PlayerBar.Tint`):** the playing song's cover colour (`Core/CoverColour.cs`, shared with the mini player), glided over 700 ms.
  - Dark mode: saturation ×0.7 capped at 0.42, lightness 0.15–0.22.
  - Light mode: saturation ×0.6 capped at 0.38, lightness 0.86–0.9.
  - With no cover it's a neutral grey.
- **App icon (Windows):** OpenTune's logo, the user's choice: a white waveform on a black rounded square, from https://justshivv.github.io/Opentune/img/opentune-logo-current.png.
  - It's `Assets/AppIcon.ico` (16–256 px PNG entries), used as `ApplicationIcon`, by `AppWindow.SetIcon` on both windows, and as `SetupIconFile` in the installer.
  - `Assets/Logo.png` (64 px) sits before "LocalTune" in the title bar.
- **Player bar quality badge:** the Lossless logo and "Lossless" / "Hi-Res Lossless" after the song name (words only for Dolby), with bits/kHz on hover. `NowPlayingView.LosslessLogo(brush)` is shared. The title's MaxWidth leaves room for it.
- **Mini player (`Views/MiniPlayer.cs`):** measured from the user's screenshot of Apple's (`myscreenshot/mini-player.png`). It opens from the player bar's last button.
  - Size and layout:
    - 350 × 152 DIP, or 632 tall with lyrics/queue (the bottom edge is kept).
    - Padding 17/15/17/17.
    - Cover 60 px at the left.
    - Title, subtitle and badge centred across the whole window (14 px semibold / 14 px / 11 px).
    - A small `LineSeekBar(small: true)`: a 3 px line and 12 px times.
    - A 30 px control row: the heart (`LikeButton`) and Add to playlist at the left, where Apple has volume and "…" (swapped at the user's request), back/play/next in the middle, lyrics and queue at the right.
    - Small soft minimise / ⤢ / × buttons at the top right.
  - **Background:** `CoverTint`, a custom SystemBackdrop wrapping `DesktopAcrylicController`.
    - It stays see-through when the window isn't focused (`IsInputActive = true`).
    - The tint is the cover's average colour from a 16×16 decode, in HSL: saturation ×0.55 capped at 0.32, lightness clamped to 0.44–0.56 (light and greyish, as Apple's). TintOpacity 0.55, LuminosityOpacity 0.72.
    - Apple's measured about rgb(152,136,142) on a pink cover; ours comes out close.
  - Always on top, no system title bar (`SetBorderAndTitleBar(true, false)`), not resizable, always dark. The top part (except the buttons) is the drag area.
  - **Position:** remembered as left edge + bottom edge (`Settings.MiniLeft/MiniBottom`).
    - The first time it goes to the bottom right, 24 px in. It sits a further 48 px up when the taskbar auto-hides: the user's does, so `WorkArea` equals the whole screen.
  - **Lyrics:** a second `LyricsView`, set to match the user's screenshot of Apple's mini lyrics (`myscreenshot/apple-mini-lyric.png`): `MinLyricSize = 20`, `Anchor = 0.05` (the active line just under the song), `DotSize = 7`, `GapHeight = 34`, `LineSpace = 0.75`. The full player keeps the defaults (0.40, 12, 52, 0.3).
  - The queue is a `QueuePanel`.
  - **Buttons:** ⤢ = `LeaveMini`. × = `QuitFromMini` (closes LocalTune, as Apple's does). Closing it any other way brings the main window back. The main window hides while it's open.
  - Never call `AutomationProperties.SetName` with a null element: it crashes natively (an access violation in coreclr, with no managed exception).
- **Heart (`LikeButton`):** likes or unlikes the playing song. It's in the player bar and just before the lyrics button in the full player.
- **Heart animation (`HeartIcon`, in `Views/Controls.cs`):** used by `LikeButton` and the Songs page rows.
  - Liking pops it (dip to 72%, swell to 128%, settle, over 520 ms) while a thin red ring flashes out. Unliking dips to 80% and back.
  - It runs on the compositor. It plays only when the same song's heart changes, not on a track change.
- **No horizontal lines (user's choice):** the player bar has no top border, and shelves hide their sideways scroll bars.
- **Songs page (`SongsPage`, `RowMode.Detailed`):** the user wanted the data from another player's screenshot, shown in our own style.
  - Columns: cover+title, time, artist, album, genre, a heart to like or unlike, plays (`History.PlayCount`, cached), and "…".
  - Clicking a column heading sorts by it; clicking again reverses. The headings share `SongRow.DetailedColumns` with the rows so they line up.
  - Genre is read from the tags. The library cache is at version 3.
- **Song menu (`Views/SongMenu.cs`):** follows the user's reference player.
  - Big buttons: Play, Shuffle, Play Next, Add to Queue.
  - Rows: Add to Playlist › (opens beside the menu), Favorites, Snooze for a Month, Metadata.
    - Snooze uses `Settings.Snoozed` and is skipped by Recommended for 30 days.
    - Metadata is a read-only details dialog with "Show in folder".
  - Below those: Remove from this Playlist, View Album, and one View <artist> per name.
  - Rows, "…" and Home song tiles all use it.
- **Filter and sort (`Views/LibraryPages.cs`, `ListToolbar`):** Songs, Albums, Artists and Folders each have a "Filter by name" box and dropdown menus. Changes apply in place, and the choices persist while the app is open. A count reads "48 of 1,315 songs".
  - Songs: genre, quality, favourites only. Sort by any column.
  - Albums: genre, type (Singles & EPs means the title ends " - Single" or " - EP", not a song count). Sort by title, artist, year, most songs, recently played.
  - Artists: genre. Sort by name, most songs, most albums.
  - Folders: sort by name, most songs, location.
- **Folders:** have their own page (`FoldersPage`, a sidebar item, and "See all" on Home). `FolderTile` has `Folder` and `Fill` properties like the album tiles.
- **Albums and Artists pages:** an `ItemsRepeater` with `UniformGridLayout` (`ItemsStretch=Fill`), so the tiles share each row and re-flow with the window. They use the tiles' `Fill` mode, where the cover size follows the tile width.
- **Shelves:** a shelf that scrolls sideways passes the plain mouse wheel to the page (`Ui.PassVerticalWheel`); Shift + wheel scrolls the shelf.
- **Not done yet:**
  - A Liked songs view (likes are imported and kept, but not shown yet).
  - Artist pictures.
  - Animated artwork and the Flow background.
  - An exclusive-mode (bit-perfect) WASAPI output.
  - An ARM64 build.

## Open ideas the user may come back to

- **Square animated artwork:** use `square_animated_artwork.mp4` as an animated cover in the lyrics header or the mini player.
- **App icon:** the user is designing their own icon (an orange/black logo). Generate the adaptive icon sizes from their 1024×1024 files; the safe zone is the central 624 px. Remove the old icon chooser in Settings at the same time.
- **Lyric smoothness:** possibly unlock 120 fps when "keep cool" is off.
- **Version number:** the app still says 1.0.4, inherited from upstream. Setting versionName to 1.0.0 was suggested; versionCode must never go down.
