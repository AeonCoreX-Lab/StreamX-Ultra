<div align="center">

  <a href="https://github.com/cybernahid-dev/StreamX-Ultra">
    <img src="assets/logo.png" alt="StreamX Ultra Logo" width="180">
  </a>

  <h1 align="center">StreamX Ultra</h1>
  <p align="center">
    <strong>Next-Generation Live TV & Streaming Platform</strong><br />
    Built with ❤️ by <a href="https://github.com/cybernahid-dev"><strong>AeonCoreX</strong></a>
  </p>

  <p align="center">
    <a href="https://github.com/cybernahid-dev/StreamX-Ultra/actions/workflows/android-build.yml">
      <img src="https://github.com/cybernahid-dev/StreamX-Ultra/actions/workflows/android-build.yml/badge.svg?branch=main&event=push" alt="Build Status">
    </a>
    <a href="https://github.com/cybernahid-dev/StreamX-Ultra/releases">
      <img src="https://img.shields.io/github/v/release/cybernahid-dev/StreamX-Ultra?label=Version&color=2ea44f&style=flat-square&logo=github" alt="Latest Version">
    </a>
    <img src="https://img.shields.io/badge/Platform-Android-2ea44f?style=flat-square&logo=android&logoColor=white" alt="Platform">
    <a href="https://github.com/cybernahid-dev/StreamX-Ultra/blob/main/LICENSE">
  <img src="https://img.shields.io/github/license/cybernahid-dev/StreamX-Ultra?style=flat-square&color=blue&label=license&logo=gnu" alt="License">
</a>

  </p>

<p align="center">
  <a href="https://support-page-eta.vercel.app/" target="_blank">
    <img src="https://img.shields.io/badge/Support%20My%20Work-Donate-blueviolet?style=for-the-badge&logo=bitcoin" />
  </a>
</p>

  <br />

  <div align="center">
    <h3>📥 Official Download</h3>
    <a href='https://streamx-ultra.en.uptodown.com/android' title='Download StreamX Ultra on Uptodown'>
      <img src='https://stc.utdstc.com/img/mediakit/download-gio-big.png' width="250" alt='Download StreamX Ultra from Uptodown'>
    </a>
    <p>
      <a href="https://github.com/cybernahid-dev/StreamX-Ultra/releases/latest/download/app-release.apk">
        <img src="https://img.shields.io/badge/Alternative-Direct%20APK-0078D4?style=for-the-badge&logo=android&logoColor=white" alt="Download APK from GitHub">
      </a>
    </p>
  </div>

</div>

<br />

<details>
  <summary><strong>📖 Table of Contents</strong></summary>
  
  * [About the Project](#-about-the-project)
  * [Key Features](#-key-features)
  * [Architecture](#-architecture)
  * [Tech Stack](#-tech-stack)
  * [Getting Started](#-getting-started)
  * [Roadmap](#-roadmap)
  * [Contributing](#-contributing)
  * [License](#-license)

</details>

---

## 🧭 About the Project

**StreamX Ultra** is an enterprise-grade streaming engine designed for the modern Android ecosystem. By integrating **Live IPTV Protocols** and a **Spotify-Inspired Music Engine**, it provides a unified platform for all your media needs without the bloat.


Developed by **AeonCoreX**, this project focuses on **native performance**, **scalable architecture**, and a **distraction-free UI**, making it an ideal solution for modern media consumption.

### 🎯 Product Vision
To deliver a reliable, scalable, and modern live-streaming experience that feels fast, intuitive, and future-proof.

---

## 🚀 Key Features

### 🎧 Spotify-Grade Music Experience
* **Global Library Access**: Stream millions of tracks directly via JioSaavn API integration.
* **Dynamic Player UI**: Immersive, color-adaptive player interface that rivals industry leaders.
* **Background Playback**: Full support for background audio with system media control integration.
* **High-Fidelity Audio**: Optimized audio buffers for crystal clear sound quality.

### 📺 Next-Gen Live TV
* **Low-Latency Pipeline**: Minimal buffering even on 3G/Slow Wi-Fi networks.
* **Adaptive Bitrate**: Automatically switches quality based on network health (HLS/DASH support).
* **Minimalist View**: A distraction-free "Cinema Mode" for an immersive viewing experience.

---

## 🛠 Tech Stack

StreamX Ultra is built using the latest Android development standards.

| Component | Technology |
| :--- | :--- |
| **Language** | ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white) **Kotlin** |
| **UI Framework** | ![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat-square&logo=android&logoColor=white) **Jetpack Compose** |
| **Architecture** | MVVM / Clean Architecture |
| **Media Engine** | Media3 / ExoPlayer |
| **Asynchronous** | Coroutines & Flow |
| **Dependency Injection** | Hilt (Recommended) |
| **Build System** | Gradle (Kotlin DSL) |
| **Min SDK** | Android 8.0 (API 26) |

---

## 🏗️ Architecture

The application follows a clean, layered architecture to ensure scalability and testability.

mermaid
graph TD;
    UI[UI Layer (Compose)] --> ViewModel;
    ViewModel --> Domain[Domain Layer / UseCases];
    Domain --> Data[Data Layer / Repository];
    Data --> Remote[Network / API];
    Data --> Local[Database / Cache];

 * UI Layer: Handles user interaction using Jetpack Compose.
 * Media Layer: Manages playback logic separately from the UI.
 * Network Layer: Handles HLS/DASH streams and API calls.
📱 Device Requirements
To ensure the best experience, we recommend:
 * OS: Android 8.0 (Oreo) or higher
 * RAM: 4GB+ (Recommended)
 * Internet: Stable 4G/5G or Wi-Fi connection
 * Audio: Headphones recommended for immersive sound

## ⚡ Getting Started
Follow these steps to build the project locally.
Prerequisites
 * Android Studio Ladybug (or newer)
 * JDK 17
 * Android SDK API 34+
Installation
 * Clone the repository
   git clone [https://github.com/cybernahid-dev/StreamX-Ultra.git](https://github.com/cybernahid-dev/StreamX-Ultra.git)

 * Open in Android Studio
   * File > Open > Select StreamX-Ultra folder.
 * Sync Gradle
   * Wait for the dependencies to download.
 * Build & Run
   * Select your emulator or physical device and click Run.

## 🗺️ Roadmap
 * [x] Core streaming foundation
 * [x] Advanced channel management
 * [ ] User Profiles: Personalized watch history
 * [ ] Global Search: Find content across channels
 * [ ] Chromecast Support: Cast to big screens
 * [ ] Play Store Release: Official public launch

## 🤝 Contributing
Contributions make the open-source community an amazing place to learn, inspire, and create. Any contributions you make to StreamX Ultra are greatly appreciated.
 * Fork the Project
 * Create your Feature Branch (git checkout -b feature/AmazingFeature)
 * Commit your Changes (git commit -m 'Add some AmazingFeature')
 * Push to the Branch (git push origin feature/AmazingFeature)
 * Open a Pull Request
 

## 📄 License
Distributed under the MIT License. See LICENSE for more information.
<div align="center">
<p>Built with passion by <strong>AeonCoreX</strong></p>
<img src="assets/aeoncorex-logo.png" width="80" alt="AeonCoreX">
<p>© 2026 AeonCoreX. All rights reserved.</p>
</div>

