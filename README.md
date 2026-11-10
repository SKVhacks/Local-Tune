# OpenTune Local

A local-only music player for Android, made from
[OpenTune](https://github.com/justshivv/Opentune). It plays the music files
on your phone and nothing else: no streaming, no accounts, no online
lookups. It keeps OpenTune's player, sound and look.

The original project's README is kept in
[docs/ORIGINAL_README.md](docs/ORIGINAL_README.md).

## What it does

- **Your library.** Songs, Albums, Artists and Library tabs, with album,
  artist and folder pages and search across all of them. Songs come from
  Android's media library and update on their own when files are added or
  removed. "A & B feat. C" counts for each artist.
- **Synced lyrics from your own files.** Put a `.ttml`, `.lrc`, `.elrc` or
  `.txt` file next to the song, with the same name (`Song.flac` →
  `Song.ttml`), or in a `Lyrics` folder beside it.
  - **TTML** as Apple Music writes it: word *and syllable* timing (the
    highlight sweeps through each word letter by letter), duet voices on
    either side, backing vocals under the line, and song parts (verse,
    chorus) for the seek bar marks and Skip to chorus.
  - **LRC**: line timing, enhanced `<mm:ss.xx>` word timing, `[mm:ss.xx]`
    word timing inside a line, `v1:`/`v2:` duet marks, `[bg: …]` backing
    lines and `[offset:]`.
  - When a song has more than one file, word timing beats line timing, and
    TTML beats LRC.
  - **One setup step:** Android doesn't let apps read text files beside
    music without asking. Open **Settings › Lyrics › Lyrics folders › Add
    folder** and pick your music folder (or a folder of lyric files) once.
    Files named "Artist - Title" or "Title" anywhere in it are matched too.
- **Android Auto**: Recent (with Shuffle all), Albums, Artists, Library
  (all songs, Liked, Folders, playlists), search and "Hey Google, play …
  on OpenTune Local".
- **Casting** to Chromecast and DLNA speakers and TVs on your Wi-Fi: the
  phone serves the file and its cover.
- **Loudness normalization from ReplayGain tags** (FLAC/Ogg/Opus comments,
  MP3 `TXXX`, M4A atoms, Opus R128).
- **Autoplay and smart shuffle** from your own library: more by the same
  artists, from the same albums, and songs you play often.
- Kept from OpenTune: the equalizer and effects, crossfade, bit-perfect
  USB output, sleep timer, playlists and likes, Replay and Wrapped, lyric
  share cards, themes, widgets, Android TV.
- Track details show the file's own format, bit depth, sample rate, size,
  path and which lyric file was found. "Share" sends the audio file.
- Open an audio file from a file manager with OpenTune Local to play it
  with its album.

What was removed: YouTube Music, every online lyrics source, radio,
podcasts, song recognition, Spotify, Listen together, accounts, downloads,
add-ons, scrobbling, SponsorBlock, online covers, update checks and usage
counting. The only network use left is casting on your home network.

It installs as `com.opentune.local`, so it sits beside the original app.

## Getting the APK

**Without a computer:** fork this repository on GitHub, then open the
fork's **Actions** tab. Every push builds the APKs (`.github/workflows/build.yml`);
you can also start one with **Run workflow**. Open the finished run, download
**apks** under Artifacts, unzip it, and install
`app-arm64-v8a-release.apk` (or `app-universal-release.apk`).

**On a computer** with JDK 17+ and the Android SDK (platform 36):

```
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/
./gradlew :core:testDebugUnitTest  # unit tests
```

**Signing:** each GitHub build is signed with a throwaway debug key, so a
new build can't update the last one in place (you'd uninstall first, losing
likes and playlists unless you export them in Settings). To sign every
build with one key of your own, add these repository secrets: 
`OPENTUNE_KEYSTORE_BASE64` (your `.jks` file, base64-encoded),
`OPENTUNE_KEYSTORE_PASSWORD`, `OPENTUNE_KEY_ALIAS` and `OPENTUNE_KEY_PASSWORD`.

## License

GNU General Public License v3.0, like OpenTune: see [LICENSE](LICENSE).
If you share this app, share its source too. Code and assets from other
projects are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
