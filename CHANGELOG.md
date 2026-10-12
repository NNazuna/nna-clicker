# Changelog

All notable changes to NNA Clicker are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-10-13

### Added
- Image steps: wait for an image to appear, tap it wherever it is, or wait until it disappears.
- Camera button on the floating panel. While recording, it marks "wait for this screen" at that moment; otherwise it saves a screenshot to use later.
- Crop screen to choose the area to look for, with options for action, search area, time limit, what to do if not found, and match strictness.
- "Add image step" in the step editor, using any saved screenshot.
- Fast on-device matching: grayscale normalized cross-correlation over an image pyramid with a color check, no external libraries.
- Screen capture through Shizuku (`screencap`) or, without Shizuku, the accessibility screenshot API on Android 11 and later.

### Changed
- The step editor shows touch steps and image steps on one timeline.
- When a recording has image steps, the dim overlay is limited to 60% so the screen can still be read.

## [1.0.0] - 2026-10-12

Initial release.

### Recording and playback
- Record taps, swipes, holds, and multi-finger gestures (up to 10 pointers) in any app.
- Two input engines: Standard (accessibility service) and Shizuku (real-time recording with precise touch injection). The app falls back to Standard automatically when Shizuku is unavailable.
- Portrait and landscape support, including rotation-aware recording in Shizuku mode.
- Repeat count or infinite loop, playback speed from 0.5× to 3×, delay between loops, and start delay.
- Auto stop after a set number of minutes.
- Optional randomization of touch position and timing.

### Controls
- Draggable floating panel with record, play, and stop.
- Quick Settings tile to show or hide the panel.
- Volume Down stops recording or playback at any time.
- Optional screen dimming during playback with an adjustable level. The screen stays on while a recording plays.

### Editing and sharing
- Step editor: change the delay before any step, set all delays at once, duplicate or delete steps.
- Import and export recordings as JSON.

### Interface
- Light and dark themes that follow the system setting.
- Adaptive app icon.
