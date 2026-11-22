# LocalTune: notes for Claude Code

LocalTune is SKVhacks's fully offline Android music player for local files, made to look and behave like Apple Music. It's a GPL-3.0 fork of OpenTune by justshivv. This file carries the context of the long design chat that built it, so a new session can pick up where that one left off.

## Project basics

- **Repository:** https://github.com/SKVhacks/Local-Tune (main). The older https://github.com/SKVhacks/opentune-local has the same history. Never push to justshivv's upstream.
- **Stack:**
  - Kotlin and Jetpack Compose.
  - Media3 ExoPlayer 1.11, with Jellyfin's FFmpeg decoder as a fallback.
  - MediaStore for the music library.
  - Gradle builds with R8.
  - Modules: `app` (UI) and `core` (data, playback).
- **Package name:** the Kotlin package is still `com.opentune`, and so is the applicationId. Don't change the applicationId: it would install as a separate app and lose the user's library. The display name is "LocalTune".
- **Build:** `./gradlew :app:assembleRelease` produces the APKs in `app/build/outputs/apk/release/`.
- **Tests:** `./gradlew :core:testReleaseUnitTest`.
- **Signing:** the env vars are `LOCALTUNE_KEYSTORE`, `LOCALTUNE_KEYSTORE_PASSWORD`, `LOCALTUNE_KEY_ALIAS` and `LOCALTUNE_KEY_PASSWORD`. The key is the old debug key (alias `androiddebugkey`, passwords `android`). The user keeps it as `localtune-signing.jks`, and the GitHub secrets hold it too. Never commit the key.
- **CI:** `.github/workflows/build.yml` runs on:
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
  - Import only accepts backups from LocalTune or the old OpenTune, and checks the whole file before changing anything.

### Other

- **Permissions:** `ui/StartupPermissions.kt` asks for audio, video, Nearby devices and notifications together when the app opens.
- **Offline:** the "no internet" banner and toast are removed (`ConnectionBanner` is a pass-through).
- **Settings:** options that no longer do anything were removed.
- **Credits:** Settings › About › "Made by SKVhacks" links to `DEVELOPER_PORTFOLIO`, which is the user's GitHub for now.
- **Source code:** the About link points to Local-Tune.
- **Licence:** the OpenTune credit must stay, because of the GPL.

## Open ideas the user may come back to

- **Windows version:** suggested with Compose Multiplatform.
  - Move the pure-Kotlin parts (lyrics, library, settings) into a shared module.
  - Play audio with libVLC/vlcj.
  - Use WASAPI exclusive mode for bit-perfect output.
  - Have GitHub Actions build the `.msi`.
- **Square animated artwork:** use `square_animated_artwork.mp4` as an animated cover in the lyrics header or the mini player.
- **App icon:** the user is designing their own icon (an orange/black logo). Generate the adaptive icon sizes from their 1024×1024 files; the safe zone is the central 624 px. Remove the old icon chooser in Settings at the same time.
- **Lyric smoothness:** possibly unlock 120 fps when "keep cool" is off.
- **Version number:** the app still says 1.0.4, inherited from upstream. Setting versionName to 1.0.0 was suggested; versionCode must never go down.
