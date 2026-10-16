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

**Image detection**
- Image steps: wait until something appears on screen, tap it wherever it is, or wait until it disappears
- Mark image steps while recording with the camera button, then choose the area in the editor with zoom and a live preview
- Manage saved screenshots and delete the ones you no longer need
- Search near the original spot (faster) or anywhere on screen
- Time limit per step, with stop or skip when the image isn't found
- Test an image step on the live screen and see how well it matches
- On-device matching with a color check; nothing is uploaded

**Flow**
- Labels and **Go to** steps for loops inside a recording, every time or a set number of times
- If / else with images: go to a label, continue, skip, or stop when an image is found or not found
- **Check once** image steps that branch without waiting
- Random waits and a **Stop playback** step
- **Back / Home / Recent apps** and **Open app** steps
- **Play from here** to test a recording from any step

**Quick start**
- **Auto clicker**: tap one spot or repeat a swipe at a set interval, without recording
- **Alert when finished**: vibrate and sound when playback ends or stops with an error

**Controls**
- Draggable floating panel with record, play, and stop; switch recordings from the panel or shrink it to a bubble
- Quick Settings tile to show or hide the panel
- Volume Down stops recording or playback at any time
- Optional screen dimming during playback; the screen stays on while a recording plays

**Editing and sharing**
- Step editor: add taps and swipes on a screenshot, edit positions, reorder steps, change delays, duplicate or delete steps
- Import and export recordings as JSON

**Interface**
- Light and dark themes that follow the system setting

## Image steps

1. Open the game and start recording.
2. When the screen you want to wait for is showing, press the **camera** button on the panel, then continue recording.
3. Stop recording and open **Edit** on the recording. Tap the image step and drag a box around the button or icon to look for. Pinch or double-tap to zoom for a precise box.
4. Choose what happens when it's found: **Wait for it**, **Tap it**, or **Wait until gone**.

Screenshots taken outside a recording can be added with **Add image step** in the editor.

## Flow steps

The easiest way to loop: open **⋮** on a step, choose **Repeat steps…**, and enter the first step, the last step, and how many times to run them. A colored bar in the editor marks the steps that repeat.

Add these with **Add step** in the editor:

| Step | What it does |
| --- | --- |
| **Label** | Marks a place in the recording that other steps can jump to |
| **Go to label** | Jumps to a label, every time or a set number of times, then continues |
| **Wait** | Waits a fixed or random time |
| **Stop playback** | Ends playback |
| **Back / Home / Recent apps** | Presses a system button |
| **Open app** | Opens an app, or brings it to the front |

Image steps can branch too: under **If found** and **If not found**, choose the next step, a label, or (when not found) stop. Set **Wait up to** to 0 to check the screen once without waiting.

Example: repeat a battle until a reward screen shows up.

1. **Label** "Battle"
2. Taps to start the battle
3. Image step: **Wait for** the reward screen, wait up to 60 s, **If not found → Battle**
4. Taps to collect the reward

Image steps use Shizuku's screen capture when it is connected. Without Shizuku they need Android 11 or later and run at about three checks per second.

## Requirements

- Android 7.0 (API 24) or later
- Accessibility service permission
- Shizuku 13 or later for the Shizuku engine (optional)
- Image steps: Shizuku, or Android 11 or later

## Installation

1. Download `NNA-Clicker.apk` from the [latest release](../../releases/latest) and install it. On first launch, a setup guide walks through the steps below.
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

NNA Clicker has no network access. Touch input is only read while a recording is in progress. The screen is captured only when you press the camera button or while a recording with image steps is playing, and the images never leave the device.

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
