# LocalTune

**A beautiful, fully offline music player for the songs on your phone.**

LocalTune plays the music files stored on your Android phone, with a clean, modern player inspired by the best music apps: synced lyrics that light up word by word, animated album artwork, a living colour background, and honest audio details that tell you exactly what you're hearing.

No account. No streaming. No ads. No tracking. Your music stays on your phone.

<img width="1280" height="640" alt="banner" src="https://github.com/user-attachments/assets/ce88b90a-1b51-4700-ae5d-a08388ae2fa6" />


## Features

### Player
- **Flow background**: a soft, slowly moving background made from the colours of the album cover.
- **Animated artwork**: put a video named `tall_animated_artwork.mp4` in a song's folder and it plays full-screen behind the player, melting into the background.
- **Smooth transitions**: the cover shrinks into the lyrics page and grows back, in portrait and landscape.
- **Quick links**: tap the song title or artist name to jump to the album or any of the artists.

### Lyrics
- Reads **`.ttml`** and **`.lrc`** lyric files from your music folders.
- **Word-timed lyrics** sweep letter by letter as they're sung; **line-timed lyrics** light up a line at a time.
- Overlapping lines and **background vocals** are shown the way they're sung.
- Instrumental breaks show animated dots; songs without lyrics simply say **"No lyrics available"**.

### Sound
- **Pure output** (on by default): every file's audio is played untouched, with no equalizer, effects or loudness levelling.
- **Bit-perfect USB output** for USB DACs (Android 14+): the DAC is switched to each song's own sample rate and bit depth.
- **Quality label** under the seek bar: Lossless, Hi-Res Lossless, Dolby Atmos and more, plus the **Bluetooth codec** in use (for example *Lossless - LDAC*).
- **Signal path** page showing the file's format, the decoder, what Android outputs and the Bluetooth link.
- Dolby and DTS formats play only through your phone's own licensed decoders, so they're never played in a reduced form.
- Plays MP3, AAC, FLAC, ALAC (Apple Lossless), WAV, Opus, Vorbis and more.

### Library
- **Home** with a personal greeting, Recently played, Artists, Playlists, Liked songs, Albums, Folders and **Replay** (your most played).
- **Artist pages** with a large artist photo, taken from your song files, or from Deezer's free public catalogue if you allow it.
- **Album pages** with **"More by"** the album's artists.
- Playlists, Liked songs, queue, search and listening history, all stored on your phone.

---

## Download

Get the latest APK from the [**Releases**](https://github.com/SKVhacks/Local-Tune/releases) page.

| File | For |
|---|---|
| `LocalTune-arm64-v8a.apk` | Almost all phones from the last several years (recommended) |
| `LocalTune-armeabi-v7a.apk` | Older 32-bit phones |
| `LocalTune-x86_64.apk` | Emulators and Chromebooks |
| `LocalTune-universal.apk` | Any device, if you're not sure (larger download) |

**Requires Android 8.0 or newer.**

### Permissions
LocalTune asks for these the first time it opens:

| Permission | Why |
|---|---|
| Music and audio | To find and play your songs |
| Photos and videos | To play animated artwork videos stored next to your songs |
| Nearby devices | To show which Bluetooth codec your headphones are using |
| Notifications | For the playback controls in the notification shade |

Each one is optional; LocalTune works without the ones you decline, minus that feature.

---

## Organising your music

LocalTune finds every song on your phone by itself. To get lyrics, covers and animated artwork too, keep each album in its own folder, with the extra files **next to the songs**:

```
Music/
└── K-C-J Pop/                        ← any folder name
    └── _Dall_/                       ← one folder per album
        ├── cover.jpg                 ← album cover
        ├── tall_animated_artwork.mp4 ← full-screen animated artwork
        ├── square_animated_artwork.mp4
        ├── Virtual Angel.flac        ← the song
        ├── Virtual Angel.ttml        ← its lyrics (word-by-word)
        ├── Unf_Air.flac
        └── Unf_Air.lrc               ← its lyrics (line-by-line)
```

| File | What it does |
|---|---|
| `Song.flac` (or `.mp3`, `.m4a`, `.wav`, `.opus`…) | The song. Title, artist and album come from its tags. |
| `Song.ttml` | Lyrics with **word or syllable timing**, background vocals and duets (the format Apple Music uses). Must have **exactly the same name** as the song. |
| `Song.lrc` | **Line-timed** lyrics (enhanced word-timed LRC works too). Same name as the song. |
| `cover.jpg` / `folder.jpg` | The album cover, when it isn't embedded in the song files. A cover inside the files is used first. |
| `tall_animated_artwork.mp4` | Plays full-screen behind the player for every song in the folder. Any name containing "tall animated artwork" works (`.mp4`, `.mov`, `.m4v`, `.webm`, `.mkv`). |
| `square_animated_artwork.mp4` | Not used yet: it's ignored, so it's fine to keep it. |

**Tips**
- **Lyrics need one extra step:** Android doesn't let apps read text files beside your music on their own, so open **Settings › Lyrics › Lyrics folders** and pick your music folder once. LocalTune remembers it.
- Lyrics can also go in a `Lyrics` folder inside the album folder, or anywhere in a lyrics folder you picked, named `Artist - Title.lrc` or `Title.lrc`.
- If a song has both, `.ttml` is used before `.lrc`.
- Animated artwork needs the **Photos and videos** permission, and can be turned off in **Settings › Appearance › Animated artwork**.

---

## Backup and restore

Keep your library safe, or move it to a new phone, from **Settings › Music and storage › Backup**.

- **Export data** saves one file, `localtune-backup-YYYY-MM-DD.json`, wherever you choose (your phone, Google Drive, a USB stick…).
- **Import data** restores it, on the same phone or a new one.

| Included in the backup | Not included |
|---|---|
| Liked songs | The music files themselves |
| Playlists, in their order | Lyrics files and animated artwork |
| Listening history and Replay | Downloaded artist pictures (fetched again when needed) |
| All your settings | |

**Restoring on a new phone:** copy your music to the new phone first, then import. Android numbers every music file differently on each phone, so LocalTune matches your liked songs, playlists and history to the files by **title and artist**. A song only stays missing if its file isn't on the phone, or it's been retagged with a different title.

Importing **replaces** the settings, history, likes and playlists on the phone. The whole file is checked before anything changes, so a damaged or wrong file leaves your library untouched.

---

## Privacy

LocalTune works completely offline. It has no account, analytics, ads or trackers.

The only thing that ever uses the internet is the optional **artist pictures** feature: when a song file has no artist photo, LocalTune can look the artist's name up on [Deezer's public API](https://developers.deezer.com/api). Only the artist's name is sent. You can turn this off in **Settings › Appearance**.

---

## Build it yourself

```bash
git clone https://github.com/SKVhacks/Local-Tune.git
cd Local-Tune
./gradlew :app:assembleRelease
```

The APKs are written to `app/build/outputs/apk/release/`. You need JDK 21 and the Android SDK (platform 36).

Every push to this repository is also built automatically by GitHub Actions. Open the **Actions** tab, pick a run and download **LocalTune-apks**.

---

## Credits

**LocalTune is designed and built by [SKVhacks](https://github.com/SKVhacks).**

LocalTune is based on **[OpenTune](https://github.com/justshivv/opentune) by justshivv**, and builds on the work of many open-source projects, including:

- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media): playback (Apache 2.0)
- [Jellyfin's Media3 FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media) and [FFmpeg](https://ffmpeg.org): decoding formats the phone can't (LGPL / GPL)
- [Coil](https://github.com/coil-kt/coil): image loading (Apache 2.0)
- [Haze](https://github.com/chrisbanes/haze) and [backdrop](https://github.com/Kyant0/AndroidLiquidGlass): blur and glass effects
- [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass): reading the Bluetooth codec (Apache 2.0)
- [Deezer API](https://developers.deezer.com/api): optional artist pictures

The full list, with each licence, is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## License

LocalTune is free software, released under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the full text.

Copyright © 2026 SKVhacks.
Based on OpenTune, copyright © its contributors.

In short, you are free to:
- **Use** LocalTune for any purpose.
- **Study and change** its source code.
- **Share** copies of it, with or without your changes.

As long as you:
- Keep it under the **same GPL-3.0 licence**, and keep the copyright and licence notices.
- **Make the source code available** to anyone you give the app to, including your changes.
- **State that you changed it**, if you did.

LocalTune comes with **no warranty**, to the extent permitted by law.

---

## Disclaimer

LocalTune is an independent project. It is not affiliated with, endorsed by or sponsored by Apple, Dolby Laboratories, DTS, Deezer, Google or any other company. Apple Music, Dolby, Dolby Atmos, DTS, LDAC and other names are trademarks of their respective owners and are used here only to describe file formats and features.
