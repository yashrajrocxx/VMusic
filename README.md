<div align="center">
  <img src="./app/src/main/ic_launcher-playstore.png" width="88" height="88" alt="VMusic Icon" style="border-radius: 20px;" />
  <h1>VMusic</h1>
  <p><b>An ultra-fast, modern, and private YouTube Music & Radio streaming client for Android.</b></p>

[![Release](https://img.shields.io/github/v/release/yashrajrocxx/VMusic?style=flat-square&color=2ecc71)](https://github.com/yashrajrocxx/VMusic/releases/latest)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-orange?style=flat-square&logo=android)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-ARM64%20%7C%20ARM32-purple?style=flat-square)](https://github.com/yashrajrocxx/VMusic/releases/latest)
[![Ad-Free](https://img.shields.io/badge/Ads-Free%20%26%20Open%20Source-red?style=flat-square)](https://github.com/yashrajrocxx/VMusic)

</div>

---

## 📖 Overview

**VMusic** is a native, lightweight Android music streaming application built entirely in **Kotlin** and **Jetpack Compose**. It brings together unlimited ad-free streaming from YouTube Music, live worldwide radio stations, on-device music playback, real-time Shazam-style music recognition, and synchronized lyrics — all wrapped in a fluid, Material You design.

No Google account required. No background trackers. Minimal battery and data consumption.

---

## ✨ Features Showcase

### 🎵 Unlimited Streaming & Discovery
- **Ad-Free Music Playback**: Seamlessly stream any track, album, artist, or playlist from YouTube Music with zero advertisements.
- **Background & Lock Screen Playback**: Full background audio playback with lock screen controls and system notification integration.
- **Smart Caching & Offline Listening**: Automatically or manually cache songs and albums locally for completely offline playback.
- **Quick Picks & Forgotten Favorites**: Dynamic home screen algorithm that learns your listening habits to suggest music and rediscover old favorites.
- **Discover & Moods**: Explore curated moods, activities, top charts, and new releases (Workout, Focus, Chill, Party, Romance, etc.).
- **Built-in PO Token Generator**: Embedded Proof-of-Origin token engine to bypass YouTube bot blocks and playback throttling seamlessly.

### 📻 Live Worldwide Radio & Curated Stations
- **Global Radio Browser**: Search and stream over 30,000+ live internet radio stations from every country and genre across the globe.
- **Curated Indian Radio Stations**: Instant access to top curated Indian stations spanning Bollywood, Indie, Classical, Punjabi, and regional broadcasts.
- **Station Bookmarks**: Favorite stations for one-tap listening and view live track metadata while tuning in.

### 🎙️ Shazam-Style Music Recognition
- **Instant Audio Identification**: Built-in Shazam-compatible fingerprinting engine powered by your device microphone.
- **Identify Songs Nearby**: Tap the radar button to identify any track playing in your environment in seconds.
- **Direct Playback**: Immediately stream, favorite, or add identified tracks to your playlists within VMusic.

### 📜 Real-Time Synchronized Lyrics
- **Word-by-Word & Line Synced Lyrics**: Powered by [BetterLyrics](https://lyrics-api.boidu.dev), [LRCLIB](https://lrclib.net), and [KuGou](https://kugou.com).
- **Karaoke Style**: Live highlight of the current verse or word as the artist sings.
- **Offline Lyrics**: Synced lyrics are cached alongside tracks for offline sessions.

### 📂 Local On-Device Library
- **Unified Local Player**: Scan and play your local audio library (`.mp3`, `.flac`, `.m4a`, `.ogg`, `.opus`, `.wav`) alongside streamed content.
- **Custom Playlists**: Combine streaming songs and local tracks into unified playlists.
- **Backup & Restore**: Export and import your entire database (playlists, favorites, search history) as a single JSON file.

### 🎛️ Advanced Audio Engine (DSP)
- **Volume Normalization**: Automatic replay-gain loudness normalization so every song plays at consistent volume without sudden spikes.
- **Built-in Equalizer & Bass Boost**: Fine-tune frequency bands, boost low-end punch, and adjust virtualizer settings.
- **Pitch & Tempo Controls**: Change playback speed (0.5x – 2.0x) or adjust audio pitch on the fly.
- **Skip Silence**: Automatically detects and trims silent intros/outros to maintain continuous flow.
- **Sleep Timer**: Schedule automatic playback pauses when falling asleep.

### 🎨 Material You & AMOLED Design
- **Dynamic Theming (Monet)**: Adapts seamlessly to your Android system wallpaper accent colors (Android 12+).
- **True AMOLED Pure Black**: Ultra-dark pure black UI for battery efficiency on OLED displays.
- **Customizable Layout**: Rearrange or hide bottom navigation tabs (Home, Discover, Radio, Songs, Playlists, Artists, Albums, Local, Recognize, Settings).

### 🚗 Android Auto & Deep Linking
- **Android Auto**: Full native dashboard interface for safe and intuitive playback while driving.
- **Universal Links**: Open YouTube Music URLs (`youtube.com/watch?v=...`, `music.youtube.com/...`) straight into VMusic.

### ⚡ Clean Dedicated Architecture
- **No Bloated Universal APKs**: Built with dedicated **ARM64** (`arm64-v8a`) and **32-bit ARM** (`armeabi-v7a`) splits for 50%+ smaller file sizes (~6 MB) and faster launch times.
- **Intelligent In-App Updates**: Auto-updater detects your phone's specific hardware architecture and downloads only the matching APK directly from GitHub Releases.

---

## 📸 Screenshots

<details>
  <summary><b>Click to preview app screenshots</b></summary>
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

Grab the latest release APK from [**GitHub Releases**](https://github.com/yashrajrocxx/VMusic/releases/latest):

| Architecture | Package | Recommended For |
| :--- | :--- | :--- |
| **ARM64 (64-bit)** | `vmusic-v1.0.0-arm64-v8a.apk` | Most modern phones & tablets released since 2017 (Fastest & Smallest) |
| **ARM32 (32-bit)** | `vmusic-v1.0.0-armeabi-v7a.apk` | Older devices, entry-level phones, and 32-bit Android systems |

---

## 🛠️ Building from Source

### Prerequisites
- **Java Development Kit (JDK)**: JDK 21 (Adoptium Temurin 21 recommended)
- **Android SDK**: Platform API 36/37 with Build-Tools 35.0.0 or 36.0.0
- Configured environment variables: `JAVA_HOME` and `ANDROID_HOME`

### One-Click Local Build Script
To build, sign, and package both ARM64 and ARM32 release APKs:
```powershell
.\build-release.ps1
```
The script will output signed APKs and SHA-256 hashes into the `release_apks/` directory.

### Manual Gradle Commands
```bash
# Clone the repository
git clone https://github.com/yashrajrocxx/VMusic.git
cd VMusic

# Build Debug APKs
./gradlew :app:assembleDebug --no-configuration-cache

# Build Signed Release APKs
./gradlew :app:assembleRelease --no-configuration-cache
```

---

## 💖 Acknowledgments

VMusic is built with love and gratitude to the open-source community:
- [**ViMusic**](https://github.com/vfsfitvnm/ViMusic) & [**ViTune**](https://github.com/bartoostveen/ViTune) — Foundational player architecture and core concepts.
- [**InnerTubeX**](https://github.com/z-huang/InnerTune) & [**MetroList**](https://github.com/mostafa-a-elhariry/metrolist) — UI inspiration and streaming endpoints.
- [**Radio Browser**](https://www.radio-browser.info/) — Free and open-source community radio directory.
- [**LRCLIB**](https://lrclib.net) & [**BetterLyrics**](https://lyrics-api.boidu.dev) — Synchronized lyrics providers.
- [**ionicons**](https://github.com/ionic-team/ionicons) — Vector iconography.

---

## ⚖️ Disclaimer

VMusic is an open-source educational project developed for personal research and fair use. It is not affiliated with, endorsed by, or sponsored by Google LLC, YouTube, or any of their subsidiaries. All trademarks, logos, and brand names belong to their respective owners.
