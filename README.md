<div align="center">
  <img src="./app/src/main/ic_launcher-playstore.png" width="80" height="80" alt="VMusic Icon" style="border-radius: 18px;" />
  <h1>VMusic</h1>
  <p><b>A modern, high-performance, and lightweight music streaming client for Android.</b></p>

[![Release](https://img.shields.io/github/v/release/yashrajrocxx/VMusic?style=flat-square&color=2ecc71)](https://github.com/yashrajrocxx/VMusic/releases/latest)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-orange?style=flat-square&logo=android)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64%20%7C%20ARM32-purple?style=flat-square)](https://github.com/yashrajrocxx/VMusic/releases/latest)
</div>

---

## 📖 Overview

**VMusic** is a native Android music player powered by Kotlin and Jetpack Compose. It allows you to stream and cache music from YouTube Music with an ad-free experience, synchronized lyrics, audio normalization, and customizable Material You themes.

---

## ✨ Features

- 🎧 **Unlimited Music Streaming** — Access tracks, albums, artists, and playlists from YouTube Music without ads.
- 📂 **Local Audio Playback** — Play your local on-device music files in the same unified interface.
- 💾 **Offline Caching & Background Play** — Background audio playback with screen off, and cache songs for offline listening.
- 📜 **Real-time Synchronized Lyrics** — Time-synced and plain lyrics fetched via [LRCLIB](https://lrclib.net) and [KuGou](https://kugou.com).
- 🎨 **Material You Dynamic Theming** — Monet accent colors, system light/dark switching, and true AMOLED pure-black mode.
- 🚗 **Android Auto Integration** — Seamless vehicle dashboard playback support.
- 🎛️ **Audio Processing Engine** — Equalizer, loudness normalization, bass boost, pitch/tempo controls, and skip silence.
- ⚡ **Optimized Separate Architecture** — Dedicated **ARM64** (`arm64-v8a`) and **32-bit ARM** (`armeabi-v7a`) APK builds for minimal file sizes and peak performance.
- 🔄 **Built-in Smart Updates** — Automatic in-app update checks matching your device's specific architecture.
- 🔗 **Deep Linking** — Open YouTube Music URLs (`watch`, `playlist`, `channel`) directly in VMusic.
- 🔒 **Privacy Focused** — No analytics, no tracking, no Google Account login required.

---

## 📸 Screenshots

<details>
  <summary><b>Click to view app screenshots</b></summary>
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

## 📥 Installation

Download the latest stable APK from the [GitHub Releases](https://github.com/yashrajrocxx/VMusic/releases/latest) page:

| Package | Architecture | Recommended For |
| :--- | :--- | :--- |
| **`vmusic-*-arm64-v8a.apk`** | 64-bit ARM (`arm64-v8a`) | Most modern smartphones and tablets (fastest performance, smaller size) |
| **`vmusic-*-armeabi-v7a.apk`** | 32-bit ARM (`armeabi-v7a`) | Older Android devices and legacy hardware |

---

## 🛠️ Building from Source

### Requirements
- **JDK 21** (e.g. Eclipse Temurin 21)
- **Android SDK Platform API 36/37** & **Build-Tools 35.0.0/36.0.0**
- Environment variables: `JAVA_HOME` and `ANDROID_HOME` configured

### Build Commands
```bash
# Clone the repository
git clone https://github.com/yashrajrocxx/VMusic.git
cd VMusic

# Build separate ARM64 and ARM32 debug APKs
./gradlew :app:assembleDebug --no-configuration-cache

# Build separate ARM64 and ARM32 release APKs
./gradlew :app:assembleRelease --no-configuration-cache
```

Generated APKs will be in `app/build/outputs/apk/release/` or `app/build/outputs/apk/debug/`.

---

## 💖 Acknowledgments

VMusic is made possible thanks to:
- [**ViMusic**](https://github.com/vfsfitvnm/ViMusic) & [**ViTune**](https://github.com/bartoostveen/ViTune): Foundational codebase and architecture.
- [**InnerTune**](https://github.com/z-huang/InnerTune) & [**MetroList**](https://github.com/mostafa-a-elhariry/metrolist): UI components and design concepts.
- [**LRCLIB**](https://lrclib.net) & [**KuGou**](https://kugou.com): Lyrics provider endpoints.
- [**ionicons**](https://github.com/ionic-team/ionicons): Icon assets.

---

## ⚖️ Disclaimer

VMusic is an open-source project intended for personal use and research.
This project is not affiliated with, endorsed by, or sponsored by YouTube, Google LLC, or any of their affiliates. All trademarks, service marks, and company names are the property of their respective owners.
