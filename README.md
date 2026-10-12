<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="96" alt="NNA Clicker icon">
</p>

<h1 align="center">NNA Clicker</h1>

<p align="center">
  Touch recorder and auto clicker for Android. Record taps, swipes, and multi-finger gestures once, then replay them with precise timing.
</p>

<p align="center">
  <a href="../../releases/latest">Download</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

## Overview

NNA Clicker brings the *Operation Recorder* workflow from desktop Android emulators to a real phone. A floating panel lets you start recording inside any app, stop when you are done, and play the sequence back as many times as you need. No root access is required.

Two input engines are available:

| Engine | Requirement | Recording | Playback |
| --- | --- | --- | --- |
| **Standard** | Accessibility service | Gesture reaches the app after the finger is lifted | Accessibility gesture dispatch |
| **Shizuku** | [Shizuku](https://shizuku.rikka.app/) running | Real time, read directly from the touchscreen | Injected touch events with millisecond timing |

The app falls back to the Standard engine automatically if Shizuku is unavailable.

## Features

**Recording and playback**
- Taps, swipes, holds, and multi-finger gestures (up to 10 pointers)
- Portrait and landscape support, including rotation-aware Shizuku recording
- Repeat count or infinite loop, speed from 0.5× to 3×, delay between loops, start delay
- Auto stop after a set number of minutes
- Optional randomization of touch position and timing

**Controls**
- Draggable floating panel with record, play, and stop
- Quick Settings tile to show or hide the panel
- Volume Down stops recording or playback at any time
- Optional screen dimming during playback; the screen stays on while a recording plays

**Editing and sharing**
- Step editor: change the delay before any step, set all delays at once, duplicate or delete steps
- Import and export recordings as JSON

**Interface**
- Light and dark themes that follow the system setting

## Requirements

- Android 7.0 (API 24) or later
- Accessibility service permission
- Shizuku 13 or later for the Shizuku engine (optional)

## Installation

1. Download `NNA-Clicker.apk` from the [latest release](../../releases/latest) and install it.
2. On Android 13 and later, open **Settings › Apps › NNA Clicker**, tap **⋮**, and choose **Allow restricted settings**.
3. Open **Settings › Accessibility › NNA Clicker** and turn it on.
4. *(Optional)* Start Shizuku, then enable **Shizuku** in NNA Clicker and grant the permission.

## Usage

1. Tap **Show panel**, or use the Quick Settings tile.
2. Open the target app and press the red **record** button.
3. Perform your gestures, then press **stop**. The recording is saved and selected automatically.
4. Press **play** to replay it. Adjust repeat, speed, and other options under **Playback**.

> Playback uses screen coordinates. Record and play in the same orientation, with the target app in the same state.

## Privacy

NNA Clicker does not read screen content and has no network access. Touch input is only read while a recording is in progress, and all recordings are stored locally on the device.

## Building from source

The project uses Gradle 8.7 with Android Gradle Plugin 8.5 and Kotlin 1.9.

```bash
gradle assembleDebug
```

Every push to `main` builds the APK with GitHub Actions. When `versionName` in `app/build.gradle.kts` changes, a new release is published with notes taken from [`CHANGELOG.md`](CHANGELOG.md).

Builds are signed with the fixed key in `app/signing/` so new versions install over previous ones. Only install APKs published on this repository's Releases page.

## Disclaimer

Many online games prohibit automation in their terms of service. You are responsible for how you use this app.

---

<p align="center">Made by <a href="https://github.com/NNazuna">NNazuna</a></p>
