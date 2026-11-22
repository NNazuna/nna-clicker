# Changelog

All notable changes to NNA Clicker are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses [Semantic Versioning](https://semver.org/).

## [2.3.0] - 2026-11-22

### Added
- Multi-target auto tap: place numbered targets over the app, drag them to move, then tap Start. Tap a target to set its delay, hold it to remove; use Undo and Clear.
- Saved setups: every multi-target run is saved automatically and can be loaded again from the panel menu or Tools → AUTO TAP → Saved setups.
- Multi-target timing: adjust the pause between targets and touch hold in the picker; numbered markers stay visible while playback runs.
- Quick auto tap: open the floating panel, tap **Auto tap here**, then tap the target once. It repeats after 1.5 seconds; Volume Down stops it.
- Unit tests for the playback program, timeline, evdev coordinate mapping, and model constraints.
- Original N+play app icon with adaptive and monochrome layers, replacing the previous character artwork.
- A small **Running** pill at the top of the screen while quick or multi-target auto tap runs, with its own Stop button. Drag it aside if it covers something.
- Unit tests for the auto tap loops (timing, per-target delay, clamping), saved setup JSON, and setup naming.

### Changed
- Support links for Ko-fi, Saweria, and Trakteer in the app, README, and repository funding card.
- Android cloud backup is disabled. Recordings and screenshots stay on the device; use Export to move a recording.
- English-only interface. The language picker and Indonesian translation table were removed, reducing startup work and APK size.
- Main and Setup screens share a flat instrument-panel style with condensed headings. Main keeps a compact tool dock for Auto tap, Screens, Log, and Setup.
- Recording list is cached and no longer rebuilt when nothing changed.
- Release APKs are now signed release builds instead of debug builds. Publishing refuses to run without the signing secrets, and the release cleanup no longer deletes tags that aren't `vX.Y.Z`.
- The floating overlays (quick tap, multi-target picker, saved setups, running pill) moved out of the accessibility service into their own files, with shared overlay colors.
- Saved setups list redesigned: each setup shows its tap count, timing, and quick Open, Rename, and Delete buttons. Long lists scroll, and a **New setup** button starts a fresh one.

### Fixed
- Playback settings loaded from storage or imported files are now clamped to safe ranges. The constructor previously bypassed the setter validation.
- Local debug builds no longer require the private signing password; releases still use the stable key. CI passes the signing secrets explicitly.
- Quick auto tap and multi-target runs no longer add a new recording every time Start is pressed. Each reuses one recording that is overwritten on the next run.
- Saved setups can now be renamed and deleted (with a confirmation step).
- New setup names no longer repeat after a setup is deleted.

## [2.2.0] - 2026-10-16

### Added
- **Bahasa Indonesia.** The app follows the phone's language, or choose it under **App › Language** (Same as phone, English, Bahasa Indonesia).
- **Tutorial**: a short guide on first launch, also under **App › Tutorial**.
- **Pause and resume** on the floating panel. Waits and delays stop counting while paused, and playback continues from the same step.
- **Run log**: when an image step stops playback, the screen at that moment is saved with the closest match. Open it from the main screen to see what went wrong and fix the step: use that screenshot, lower the match, search the whole screen, or wait longer.
- **Settings per recording**: turn on **Only for this recording** under Playback to give the selected recording its own repeat, speed, delays, auto stop, and randomization.

### Changed
- The "image not found" message now shows the closest match and the match needed.
- Auto stop doesn't count time while paused.

## [2.1.0] - 2026-10-16

### Added
- **Repeat steps…**: choose the first and last step and how many times to run them; the label and Go to are added for you.
- Colored bars in the editor show which steps repeat.
- **Play** and **Help** in the editor, and a large **Add step** button at the end of the list.
- **Duplicate** for recordings, and an **Edit** button on each recording.

### Changed
- **Add step** shows grouped choices (Touch, Image, Repeat and logic, System) with a short description of each.
- Go to and Label steps describe what they do in plain words, such as "Repeats steps 3–5 · 3× in total".
- Clearer step details and a short guide when there are no recordings yet.
- "Give up after" on image steps is now "Wait up to".

## [2.0.0] - 2026-10-15

### Added
- Flow steps: **Label**, **Go to label**, **Wait**, and **Stop playback**. Add them with **Add step** in the editor.
- Loops inside a recording: **Go to** jumps to a label every time or a set number of times, then continues.
- If / else with images: each image step can continue, stop, skip, or go to a label, separately for found and not found.
- **Check once** for image steps (give up after 0 s): look at the screen once and branch without waiting.
- Random waits: wait a random time between two values.
- The panel shows the current step while playing.
- **Auto clicker** on the main screen: pick a spot (or a swipe) and how often, and it repeats until you stop it. No recording needed.
- **Back / Home / Recent apps** steps and an **Open app** step, so a macro can switch apps and recover from wrong screens.
- **Play from here** in the step menu: test a recording from any step without running it all.
- **Alert when finished** (Playback setting): vibrate and sound when playback ends on its own or stops with an error.

### Changed
- Both engines run recordings through the same step program, so jumps and branches behave the same with and without Shizuku.

### Fixed
- Importing a recording kept only taps and swipes; image steps are now kept too.

## [1.3.0] - 2026-10-15

### Added
- Setup guide on first launch: restricted settings, accessibility, Shizuku, and the panel, each with its status and a button to fix it. Open it again from **Setup** on the main screen.
- **Test on screen** for image steps: checks the current box on the live game screen and reports the match, the closest score, or a color mismatch, with one-tap fixes.
- Recording list on the floating panel: switch macros without opening the app.
- Shrink the panel to a small bubble (long-press the grip or use the panel menu); tap the bubble to expand. Its color shows recording or playing.
- Add **Tap** and **Swipe** steps by placing points on a screenshot, at the end or after any step.
- **Edit position** of an existing tap, hold, or swipe, with zoom and exact coordinates.

### Changed
- Image matching keeps fine details (text, stripes) at the coarse search level and also searches on a half-cell shifted grid, so small or patterned targets are found more reliably.
- Shared zoom view for cropping and point editing.

## [1.2.0] - 2026-10-13

### Added
- Move steps up or down, or to a specific position, from the step menu. Delays stay in place, so the rhythm of the recording is kept.
- Zoom in the crop screen: pinch, double-tap, or the + and − buttons, move with two fingers, and "Zoom to box".
- Live preview of the selected area while cropping.
- Screenshot manager with thumbnails: delete single screenshots or all unused ones. Open it from **Screenshots** on the main screen or when picking a screenshot.

### Changed
- The crop area now matches the screenshot's shape, so landscape screenshots no longer leave empty space.
- A single tap outside the box no longer resets the selection; drag to draw a new one.

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
