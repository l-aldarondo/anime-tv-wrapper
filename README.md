# 9Anime TV - Android & Google TV Wrapper

A high-performance Android & Google TV app wrapper for [9anime](https://9anime.or.at/) featuring built-in multi-layer ad and pop-up blocking, hardware D-pad remote navigation, and automated HTML5 fullscreen cinema playback.

---

## ✨ Features

- 🛡️ **Advanced Built-In Ad & Pop-up Blocker**:
  - Intercepts and scrubs malicious third-party ad networks (`adsterra`, `monetag`, `furudloof`, `belchlipin`, `subduepaler`, `doubleclick`, etc.) directly at the network layer.
  - Neutralizes click-jacking overlays (fake voice messages, fake Amazon/betting deals, anti-adblock modals).
  - Intercepts `window.open` and programmatic anchor clicks before popups can spawn.
- 📺 **Google TV & Android TV Optimized**:
  - Official Android TV Leanback launcher support with custom 320x180 banner.
  - Seamless touchless playback: `mediaPlaybackRequiresUserGesture = false`.
  - Automatic desktop widescreen layout when running on TV devices.
  - Display keep-awake (`FLAG_KEEP_SCREEN_ON`) prevents screen timeouts during long viewing sessions.
- 🎮 **D-Pad Remote & Media Controls**:
  - **Pointer Mode**: Glowing on-screen cursor with velocity acceleration and edge scrolling.
  - **Scroll Mode**: Directly scroll anime catalog grids up and down.
  - **Playback Shortcuts**:
    - `[OK] / [PLAY/PAUSE]`: Toggle Play / Pause
    - `[DPAD RIGHT] / [FAST FORWARD]`: Skip forward 10s (`⏩ +10s`)
    - `[DPAD LEFT] / [REWIND]`: Rewind 10s (`⏪ -10s`)
    - `[DPAD UP]`: **Skip Anime Intro (+85s)**
    - `[DPAD DOWN]`: Rewind 30s (`⏪ -30s`)
    - `[NEXT] / [PREVIOUS]`: Quick jump between episodes
    - `[BACK] / [ESCAPE]`: Smoothly exit fullscreen back to episode view
- 🎬 **Cinema Fullscreen Video**:
  - Auto-rotates mobile devices into **Sensor Landscape** (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`) when video starts playing.
  - Restores portrait browsing upon pressing BACK.
  - Immersive sticky mode hides Android status and navigation bars.
  - Responsive 16:9 ratio enforcement prevents player collapse.

---

## 🚀 Building & Installation

### Requirements
- Android SDK 36 (minSdk 24, targetSdk 36)
- Java 17+

### Build Debug APK (v1.0.0)
```bash
./gradlew assembleDebug
```
Output APK location:
`app/build/outputs/apk/debug/app-debug.apk`

### Install to Connected Device via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License
MIT License - Personal entertainment and streaming wrapper utility.
