# Changelog

All notable changes to NNA Clicker are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses [Semantic Versioning](https://semver.org/).

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
