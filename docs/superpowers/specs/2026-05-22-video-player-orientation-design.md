# MuBOX Video Player Orientation Design

## Goal

Add a player orientation setting with four modes:

- `视频`: choose portrait or landscape from the video dimensions.
- `竖屏`: force portrait playback.
- `横屏`: force landscape playback.
- `传感器`: allow sensor-based rotation.

When the mode is `视频`, the player must not use sensor rotation. It starts in landscape until video dimensions are known, then locks to portrait for tall videos and landscape for wide or square videos. The player screen also needs a right-side button that toggles between portrait and landscape during playback.

## Current State

The video player currently sets `requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR` in `VideoPlayerActivity.onCreate()`. That means player rotation always follows the device sensor, independent of app settings.

Existing video settings already flow through:

```text
AppSettingsStore -> MainActivity -> VideoPlayerActivity Intent extras -> VideoPlayerActivity
```

The player UI already has a right-side floating control rail for track and information panels. Video dimensions are available through `MpvController.onVideoParamsChanged()` and `MpvPlayerState.videoParams`.

## Chosen Approach

Keep orientation ownership in `VideoPlayerActivity`, not `MpvController`.

`MpvController` continues to own mpv state and observed video metadata. `VideoPlayerActivity` owns Android platform behavior by translating the selected orientation mode and the observed video dimensions into `requestedOrientation` values.

This keeps platform orientation logic near the Activity lifecycle while still using the existing player state flow for video dimensions.

## Data Model

Add `VideoPlayerOrientationMode` in the video player package:

- `VIDEO`
- `PORTRAIT`
- `LANDSCAPE`
- `SENSOR`

Add a label helper for settings UI:

- `VIDEO` -> `视频`
- `PORTRAIT` -> `竖屏`
- `LANDSCAPE` -> `横屏`
- `SENSOR` -> `传感器`

Add `videoPlayerOrientationMode` to `AppSettings`, persist it in `AppSettingsStore`, and pass it through both `VideoPlayerActivity.localIntent()` and `VideoPlayerActivity.webDavIntent()`.

Default value: `VIDEO`.

Invalid or missing stored values fall back to `VIDEO`, matching existing enum setting behavior.

## Orientation Behavior

`VideoPlayerActivity` reads the initial orientation mode from the Intent.

Mode mapping:

- `SENSOR`: set `ActivityInfo.SCREEN_ORIENTATION_SENSOR`.
- `PORTRAIT`: set `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`.
- `LANDSCAPE`: set `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`.
- `VIDEO`: first set `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`; when `videoParams.width` and `videoParams.height` are available, lock to portrait if `height > width`, otherwise lock to landscape.

The `VIDEO` mode deliberately never sets `SCREEN_ORIENTATION_SENSOR`. This satisfies the requirement to close sensor-based portrait/landscape switching when orientation is decided by video.

Square videos are treated as landscape.

If dimensions arrive multiple times, `VIDEO` mode may update the lock only while the user has not manually toggled orientation.

## In-Player Toggle

Add a right-side floating orientation button to the existing rail.

Behavior:

- Visible with the other right-side controls when controls are visible and unlocked.
- Taps switch between portrait and landscape immediately.
- After the user taps it, the current playback uses the manual fixed orientation and no longer gets overridden by later video dimension callbacks.
- In `SENSOR` mode, tapping the button switches the current playback to a fixed portrait or landscape orientation.
- In `VIDEO` mode, tapping the button overrides video-decided orientation for the current playback only.

This button does not persist a new default setting. The settings screen controls future playback defaults; the button controls the current playback session.

## Settings UI

Add a dropdown row in the existing settings screen `视频` group:

- Title: `播放器方向`
- Options: `视频`, `竖屏`, `横屏`, `传感器`
- Default: `视频`

The setting is saved through `AppSettingsStore` and passed to new video player Intents from both local file playback and WebDAV playback.

## Testing

Use focused JVM tests matching the existing player tests.

Expected tests:

- Orientation mode labels expose the four requested options in order.
- `VIDEO` defaults to landscape when dimensions are missing.
- `VIDEO` resolves portrait when height is greater than width.
- `VIDEO` resolves landscape when width is greater than or equal to height.
- `SENSOR`, `PORTRAIT`, and `LANDSCAPE` map to the expected Activity orientation constants.
- Manual toggle switches portrait to landscape and landscape to portrait.
- Manual toggle disables later `VIDEO` auto updates for the current playback.
- Settings store compiles with the new setting and Intents carry the selected mode.
- Existing player UI tests are updated to account for the new side rail button.

Verification command:

```bash
./gradlew :app:testDebugUnitTest
```

## Files To Change

Expected production files:

- `app/src/main/java/com/example/comicdav/video/player/MpvController.kt`
- `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- `app/src/main/java/com/example/comicdav/MainActivity.kt`

Expected tests:

- `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`
- New or updated player orientation tests under `app/src/test/java/com/example/comicdav/video/player/`

## Out Of Scope

- Persisting the in-player toggle as the new default.
- Adding rotation behavior to the comic reader.
- Supporting reverse portrait or reverse landscape.
- Changing fullscreen, status bar, mpv lifecycle, subtitle, or seek behavior.
