# 🎵 Sepotify

<p align="center">
  <img src="assets/logo.svg" width="160" height="160" alt="Sepotify Logo" />
  <br/>
  <b>High-Fidelity, Zero-Delay Music Experience for Android</b>
  <br/>
  <i>Crafted and enhanced by <a href="https://github.com/RMNO21">RMNO21</a></i>
</p>

---

## ✨ Key Enhancements in Sepotify

- ⚡ **Zero-Delay Offline Playback**: Downloaded audio files are loaded and played instantly from local storage with 0ms network latency.
- 📶 **100% Seamless Offline Startup**: The app opens instantly even without an internet connection—no timeouts, no freezing, with persistent local cache of your Library, Playlists, and Home Feed.
- 📥 **One-Tap Playlist Downloads**: Download entire playlists with a single tap directly from your Library or Playlist view. Downloaded tracks stay organized in their respective playlists.
- 🏷️ **Smart Offline Badges**: Fully downloaded playlists and tracks display sleek neon emerald status badges.
- 💾 **Storage Manager**: View exact storage locations, track counts, and disk usage under **Settings > Downloads & Storage**, with 1-click export to `Music/sepotify`.
- 🎯 **Accurate Track Resolution**: Enhanced audio matching algorithm filters out remaster/radio-edit noise and falls back gracefully so song playback never fails.
- 🎨 **Neon Emerald Visual Identity**: Custom broken-ring circular soundwave branding with animated splash screen and `github.com/RMNO21` attribution.
- 💎 **Audiophile Quality Support**: Deezer HiFi (FLAC), Lossless streaming, and high-bitrate YouTube Music audio streams.

---

## 🛠️ Building the APK

### Prerequisites
- **Java 17 JDK** (e.g. Eclipse Temurin 17 / OpenJDK 17)
- **Android SDK** (API level 34, Build-Tools 34.0.0)

### Build Commands
To assemble the debug APK:
```bash
./gradlew assembleDebug
```
The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

To assemble the release APK:
```bash
./gradlew assembleRelease
```
The compiled APK will be generated at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## 📱 Features

- 🎵 **Synced Playlists**: Browse, create, and manage your Spotify playlists.
- 📝 **Live Synced Lyrics**: Real-time synchronized lyrics with full-screen view.
- 📻 **Smart Autoplay & Radio**: Autoplay similar tracks when your queue ends.
- 🎧 **Offline Mode**: Play all your downloaded tracks anytime, anywhere without cellular data or Wi-Fi.
- 🎚️ **Equalizer & Crossfade**: Smooth transition between songs with adjustable duration.
- 📁 **Local Files Import**: Import and play your local device audio files directly inside the app.

---

## 👨‍💻 Credits & Attribution

- **Sepotify Core Enhancement & Maintenance**: [@RMNO21](https://github.com/RMNO21)
- Built on top of [Meld](https://github.com/), [Neptune](https://github.com/navneet851/spotify-clone-jetpack-compose),  [Spotui](https://github.com/Spotui/Spotui)و [SpotiFLAC](https://github.com/spotbye/SpotiFLAC), and [SimpMusic](https://github.com/maxrave-dev/SimpMusic).

---

## ⚖️ Disclaimer

This project is for educational and personal use only. Spotify is a registered trademark of Spotify AB.
