# Third-party notices

LocalTune is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). It is
based on [OpenTune](https://github.com/justshivv/opentune) by justshivv and its contributors
(GPL-3.0), and includes or uses the following code, libraries, fonts and data, under their own
licences.

## Android app (`android/`)

### Code adapted from other projects

- **Echo Music motion effects.** The animated glow, wavy play button and soft spring motion are
  adapted from [Echo Music](https://github.com/EchoMusicApp/Echo-Music/tree/a31a72bd958f7cdc128db17d2ea6ae90c3489aa1)
  by the Echo Music contributors (GPL-3.0): its `ui/player/MiniPlayer.kt` and
  `ui/component/BottomSheet.kt`. They are adapted in `ui/player/EchoMotion.kt`,
  `PlayerButtons.kt` and `ui/PageMotion.kt`, with their own palette, pause and lifecycle
  handling, and reduced-motion behaviour.
- **Samsung FLAC decoder workaround.** `LosslessAudioPolicy` adapts the Samsung FLAC decoder
  exclusion and STREAMINFO measurement from [BitChord](https://github.com/kushagrasinghx/BitChord)
  (`AudioOutputPolicy.kt` and `PlaybackService.kt`, commit
  `edc1e6f36febf71a118e649411d2fc21b70ead45`, GPL-3.0). It excludes affected Samsung FLAC
  decoders and reads real FLAC metadata rather than treating float PCM as proof of a hi-res
  master.

### Libraries

| Library | Used for | Licence |
|---|---|---|
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Playback, the media session | Apache 2.0 |
| [Jellyfin's Media3 FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media) (`org.jellyfin.media3:media3-ffmpeg-decoder`) with [FFmpeg](https://ffmpeg.org) | Formats the phone can't decode, such as ALAC. Source and build steps are at that address. | Apache 2.0 / LGPL 2.1 or later |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) and AndroidX | The interface | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil) | Image loading | Apache 2.0 |
| [Haze](https://github.com/chrisbanes/haze) | Frosted glass | Apache 2.0 |
| [backdrop](https://github.com/Kyant0/AndroidLiquidGlass) by Kyant | Liquid glass | Apache 2.0 |
| [MaterialKolor](https://github.com/jordond/MaterialKolor) | Colours from the cover | MIT |
| [OkHttp](https://github.com/square/okhttp) | The optional artist-picture lookup | Apache 2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Background work, saved data | Apache 2.0 |
| [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | Reading the Bluetooth codec in use (`BluetoothA2dp.getCodecStatus`) | Apache 2.0 |

### Font

The [Outfit](https://github.com/Outfitio/Outfit-Fonts) font is used under the SIL Open Font
License 1.1 ([`third_party/outfit/OFL.txt`](third_party/outfit/OFL.txt)).

## Windows app (`windows/`)

| Component | Used for | Licence |
|---|---|---|
| [Windows App SDK / WinUI 3](https://github.com/microsoft/WindowsAppSDK) | The interface and windows | MIT |
| [.NET](https://github.com/dotnet/runtime) | The runtime, bundled with the app | MIT |
| [TagLibSharp](https://github.com/mono/taglib-sharp) | Reading song tags and covers | LGPL 2.1 |

- **Lyrics parsers.** `Lyrics/Lyrics.cs` and `Lyrics/Ttml.cs` are C# ports of the Android app's
  own lyric code, under the same licence.
- **The Apple Lossless logo** beside "Lossless" is drawn from the outline at
  [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Apple_Lossless_logo.svg). It is
  public domain as a simple shape; the mark is Apple's trademark and is shown only to describe
  the file format.
- **The app icon** is OpenTune's logo (https://justshivv.github.io/Opentune/), from the project
  LocalTune is based on.

## Online data (both apps, optional)

**Deezer artist pictures** come from the [Deezer public API](https://developers.deezer.com/api),
which needs no key. It is used only when a song file carries no artist picture and the
"artist pictures from the internet" setting is on. Only the artist's name is sent, and the
returned photo is cached on the device. Artist images belong to their owners.
