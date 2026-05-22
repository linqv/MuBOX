# MPV Profile Setting Design

## Goal

Add an MPV Profile selector to the existing MuBOX video settings so users can choose one of mpv's built-in playback profiles:

- fast
- default
- high-quality
- gpu-hq
- low-latency
- sw-fast

The selected profile must be persisted with the rest of `AppSettings` and applied during mpv initialization before playback starts.

## Scope

In scope:

- Add a strongly typed enum for MPV profile choices.
- Persist the selected profile in `AppSettingsStore`.
- Add a dropdown row to the existing `SettingsScreen` video group.
- Pass the selected profile into `VideoPlayerActivity` through the same path used by the current video output, GPU API, decoder, controls, and orientation settings.
- Apply the profile in `MuBoxMpvView.initOptions()` by setting mpv's `profile` option.

Out of scope:

- Editing or generating `mpv.conf`.
- Custom user-defined profiles.
- Changing the behavior of existing VO, GPU API, decoder, cache, subtitle, or proxy settings.
- Runtime profile switching after mpv has already been initialized.

## User Experience

The settings screen will show a new dropdown in the existing "video" group named `MPV Profile`. It will list the six profile values using readable labels:

- Fast
- Default
- High Quality
- GPU HQ
- Low Latency
- SW Fast

The default value will be `Fast`, matching the mpvEx reference implementation. Existing users will receive this default until they choose another value.

## Architecture

Add `MpvProfileMode` in the video player package, near the existing `VideoOutputMode`, `GpuApiMode`, and `VideoDecoderMode` model types. Each enum value will expose the exact mpv profile string to avoid scattering string literals through the app.

Extend `AppSettings` with `mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST`. `AppSettingsStore` will read and write it as an enum name through a new DataStore key.

Extend `SettingsScreen` with:

- a selected `mpvProfileMode` dropdown row in the video settings group
- an `onMpvProfileModeChange` callback
- a label helper for display text

Extend the video open path in `MainActivity` so both local video and WebDAV video launches pass the selected profile to `VideoPlayerActivity`, matching the existing video setting flow.

Extend `VideoPlayerActivity` so it accepts and stores the profile mode from its intent. The activity will provide the mode to `MuBoxMpvView` before mpv initialization.

Extend `MuBoxMpvView` with a configurable profile property. `initOptions()` will call:

```kotlin
MPVLib.setOptionString("profile", mpvProfileMode.value)
```

This call should happen before the existing explicit options. Existing options such as VO, GPU API, and decoder remain authoritative if they override parts of the selected profile.

## Error Handling

Invalid or missing DataStore enum values will fall back to `MpvProfileMode.FAST` through the existing `toEnumOrDefault` pattern.

Invalid or missing intent values in `VideoPlayerActivity` will also fall back to `MpvProfileMode.FAST`.

## Testing

Add focused unit coverage where the project already has testable seams:

- DataStore reads default `MpvProfileMode.FAST`.
- Updating the setting persists and reads back the selected mode.
- Intent parsing in `VideoPlayerActivity` falls back to `FAST` for missing or invalid values if a suitable local test seam exists.

Manual verification:

- The settings screen shows the new dropdown under video settings.
- Selecting a profile persists after app recreation.
- Opening a video applies the selected profile before playback initialization.

## Non-Goals

This change does not document the full mpv behavior of each profile in-app. The setting is an advanced player option and should remain compact like the adjacent VO, GPU API, and decoder settings.
