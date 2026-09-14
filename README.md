<div align="center">
    <img src="./app/src/main/banner.png" alt="VMusic Banner" height="360" style="display: block; margin: 0 auto; border-radius: 16px;"/>
    <br/>
    <h1>🎶 VMusic</h1>
    <p><b>A modern, beautiful, and lightweight music streaming client for Android.</b></p>

[![Release](https://img.shields.io/github/v/release/yashrajrocxx/VMusic?style=for-the-badge&color=2ecc71)](https://github.com/yashrajrocxx/VMusic/releases/latest)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=for-the-badge)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-orange?style=for-the-badge&logo=android)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64%20%7C%20ARM32-purple?style=for-the-badge)](https://github.com/yashrajrocxx/VMusic/releases/latest)
</div>

---

## ✨ Features

- 🎧 **Endless Streaming** — Stream almost any track, album, artist, or video from YouTube Music.
- 📱 **Local Media Playback** — Play your on-device audio files seamlessly.
- 🔄 **Background & Offline Playback** — Listen with your screen off, and cache songs for offline listening.
- 📜 **Time-synced Lyrics** — Real-time synchronized and plain lyrics fetched via [LRCLIB](https://lrclib.net) & [KuGou](https://kugou.com).
- 🔍 **Rich Discovery & Search** — Search tracks, albums, artists, and playlists or explore curated moods and genres.
- 🎨 **Material You & AMOLED** — Dynamic color palettes with Monet, Material You, pure black AMOLED mode, and custom accent colors.
- 🚗 **Android Auto** — Drive safely with full Android Auto vehicle media integration.
- 🔊 **Audio Engineering** — Built-in loudness normalization, customizable equalizer, bass boost, and silence skipping.
- 📦 **Optimized Native Architecture** — Independent **ARM64** (`arm64-v8a`) and **32-bit ARM** (`armeabi-v7a`) builds ensure the fastest startup and minimum APK size.
- 🔗 **Smart Deep Linking** — Open YouTube and YouTube Music links (`watch`, `playlist`, `channel`) straight in VMusic.
- 🛡️ **Privacy-First** — No ads, no telemetry, no tracking, and open source.

---

## 📸 Screenshots

<details>
  <summary><b>Click to expand screenshots</b></summary>
  <br/>
  <div align="center">
    <img src="./assets/screenshots/4.jpg" width="31%" style="margin: 1%; border-radius: 8px;" />
    <img src="./assets/screenshots/5.jpg" width="31%" style="margin: 1%; border-radius: 8px;" />
    <img src="./assets/screenshots/6.jpg" width="31%" style="margin: 1%; border-radius: 8px;" />
    <br/><br/>
    <img src="./assets/screenshots/1.png" width="31%" style="margin: 1%; border-radius: 8px;" />
    <img src="./assets/screenshots/2.png" width="31%" style="margin: 1%; border-radius: 8px;" />
    <img src="./assets/screenshots/3.png" width="31%" style="margin: 1%; border-radius: 8px;" />
  </div>
</details>

---

## 📥 Download & Installation

Get the latest stable release directly from the GitHub Releases page:

<div align="center">
  <a href="https://github.com/yashrajrocxx/VMusic/releases/latest">
    <img src="https://img.shields.io/badge/Download-VMusic%20Latest%20Release-brightgreen?style=for-the-badge&logo=github" alt="Download on GitHub" height="50">
  </a>
</div>

<br/>

### Which APK should you choose?

- **`vmusic-*-arm64-v8a.apk`** *(Recommended)*: For all modern Android devices (64-bit ARM). Faster and smaller download size.
- **`vmusic-*-armeabi-v7a.apk`**: For older 32-bit Android smartphones and legacy devices.

---

## 🛠️ Building From Source

### Prerequisites
- **JDK 21** (Temurin 21 recommended)
- **Android SDK Platform API 36 / 37** and **Build-Tools 35.0.0 / 36.0.0**
- Environment variables: `JAVA_HOME` and `ANDROID_HOME`

### Compile Command
```bash
# Clone the repository
git clone https://github.com/yashrajrocxx/VMusic.git
cd VMusic

# Build Debug APKs for both ARM64 and ARM32
./gradlew :app:assembleDebug --no-configuration-cache

# Build Release APKs
./gradlew :app:assembleRelease --no-configuration-cache
```
*Built APKs will be located in `app/build/outputs/apk/release/`.*

---

## 💖 Acknowledgments

VMusic is made possible thanks to these wonderful open-source projects:
- [**ViMusic**](https://github.com/vfsfitvnm/ViMusic) & [**ViTune**](https://github.com/bartoostveen/ViTune): The foundational music client architecture and inspiration.
- [**InnerTune**](https://github.com/z-huang/InnerTune) & [**MetroList**](https://github.com/mostafa-a-elhariry/metrolist): Core UI components and concepts.
- [**YouTube-Internal-Clients**](https://github.com/zerodytrash/YouTube-Internal-Clients): YouTube API exploration and client endpoints.
- [**LRCLIB**](https://lrclib.net) & [**KuGou**](https://kugou.com): Synchronized lyrics and metadata providers.
- [**ionicons**](https://github.com/ionic-team/ionicons): Beautiful crafted vector icons.

---

## ⚖️ Disclaimer

VMusic is an open-source project intended for personal use and educational research.
This project and its contributors are not affiliated with, authorized, maintained, sponsored, or endorsed by YouTube, Google LLC, or any of their affiliates or subsidiaries. All product and company names are trademarks™ or registered® trademarks of their respective holders.
