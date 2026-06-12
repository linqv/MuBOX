# Anime4K Player Design

## Goal

Add Anime4K upscaling to the app's mpv-backed video player. The feature should have a global default in video settings and allow the active playback session to turn Anime4K on or off and switch preset/quality immediately.

The implementation should follow the existing player architecture and use `/home/lin/MuBOX-pro/mpvEx` as a behavioral reference for shader assets, shader chains, and mpv compatibility handling.

## User-Facing Behavior

- Video settings expose:
  - Anime4K enabled: default `false`.
  - Anime4K preset: `A`, `B`, `C`, `A+`, `B+`, `C+`; default `A`.
  - Anime4K quality: `Fast`, `Balanced`, `High`; default `Fast`.
- The player menu exposes the same session controls:
  - Enable or disable Anime4K during playback.
  - Select preset during playback.
  - Select quality during playback.
- Runtime changes apply to the active mpv session without reopening the video.
- If Anime4K cannot be applied, playback continues without shaders and the UI state remains coherent.

## Architecture

### Shader Assets

Copy the Anime4K GLSL files from `mpvEx/app/src/main/assets/shaders` into this app under `app/src/main/assets/shaders`.

The app will use the same core shader files:

- `Anime4K_Clamp_Highlights.glsl`
- `Anime4K_AutoDownscalePre_x2.glsl`
- `Anime4K_Restore_CNN_{S,M,L}.glsl`
- `Anime4K_Restore_CNN_Soft_{S,M,L}.glsl`
- `Anime4K_Upscale_CNN_x2_{S,M,L}.glsl`
- `Anime4K_Upscale_Denoise_CNN_x2_{S,M,L}.glsl`

### Anime4K Manager

Add `Anime4KManager` in `com.example.comicdav.video.player`, alongside the existing mpv controller code. Responsibilities:

- Copy shader assets from APK assets to `filesDir/shaders`.
- Validate expected shader files exist before returning a shader chain.
- Build `glsl-shaders` strings for a selected preset and quality.
- Return an empty shader chain for disabled or invalid configurations.

The shader chains mirror `mpvEx`:

- Common prefix: `Anime4K_Clamp_Highlights.glsl`.
- `A`: restore, upscale, autodownscale, upscale.
- `B`: soft restore, upscale, autodownscale, upscale.
- `C`: upscale denoise, autodownscale, upscale.
- `A+`: restore, upscale, autodownscale, restore, upscale.
- `B+`: soft restore, upscale, autodownscale, soft restore, upscale.
- `C+`: upscale denoise, autodownscale, restore, upscale.

Quality maps to file suffixes:

- `Fast` -> `S`
- `Balanced` -> `M`
- `High` -> `L`

### Settings

Extend `AppSettings` and `AppSettingsStore` with:

- `anime4kEnabled: Boolean`
- `anime4kMode: Anime4KMode`
- `anime4kQuality: Anime4KQuality`

Add update methods for each value. Invalid or missing stored enum names fall back to defaults.

`SettingsScreen` adds controls in the existing video settings list, near VO/GPU/Profile options because Anime4K is a rendering feature.

### Player Startup

Pass Anime4K settings through existing video open paths:

`AppSettingsStore -> MainActivity/AppContentRoutes -> VideoPlayerActivity intent -> player setup`.

`MuBoxMpvView.initOptions()` applies startup Anime4K before loading media, because shader options are most reliable when set during mpv initialization. It should:

- Apply selected mpv profile and VO/GPU settings as today.
- Initialize shaders only if global Anime4K is enabled and selected mode is not disabled.
- Set `glsl-shaders` with `MPVLib.setOptionString` for startup application.

### Playback Runtime Controls

Extend `MpvPlayerState` with the current Anime4K session settings:

- `anime4kEnabled`
- `anime4kMode`
- `anime4kQuality`
- `anime4kStatusMessage: String?` for short non-fatal shader application failures

Add controller methods:

- `setAnime4KEnabled(enabled: Boolean)`
- `setAnime4KMode(mode: Anime4KMode)`
- `setAnime4KQuality(quality: Anime4KQuality)`
- an internal method that computes the shader chain and calls `engine.setPropertyString("glsl-shaders", chainOrEmpty)`.

The playback menu adds an Anime4K group below visual controls:

- One existing-style compact text button for on/off.
- Existing-style compact text buttons for preset values `A`, `B`, `C`, `A+`, `B+`, `C+`.
- Existing-style compact text buttons for quality values `Fast`, `Balanced`, `High`.

Runtime controls update the active session only. Global defaults remain controlled by the settings page, matching the existing split between app settings and per-playback controls such as scale and decoder mode.

## Compatibility

Anime4K shaders should prefer the legacy `gpu` renderer. If Anime4K is enabled and the stored video output mode is `gpu-next` without Vulkan, the player should temporarily use `gpu` for that session.

Rules:

- Anime4K off: use the user's configured video output mode.
- Anime4K on with `gpu`: keep `gpu`.
- Anime4K on with `gpu-next` and Vulkan: allow the configured renderer.
- Anime4K on with `gpu-next` and non-Vulkan GPU API: use `gpu` for this session.

The app should not rewrite the user's global VO setting when applying this compatibility fallback.

OpenGL-only tuning from `mpvEx` (`opengl-pbo`, `opengl-early-flush`) should only be applied when the session is not using Vulkan.

## Error Handling

- Shader asset copy failure: clear `glsl-shaders`, keep playback running, and expose a short session status.
- Missing expected shader file: clear `glsl-shaders`, keep playback running.
- Invalid stored enum: fall back to default values.
- mpv property/option write failure: catch at the controller/application boundary and keep state consistent with shaders disabled.

## Tests

Use test-first implementation with existing unit test patterns.

Required tests:

- `Anime4KManager` builds the expected shader chain for representative modes and qualities.
- Disabled mode returns an empty shader chain.
- Missing shader assets return an empty chain instead of a partial chain.
- `MpvController` enabling Anime4K writes `glsl-shaders` with the computed chain.
- `MpvController` disabling Anime4K clears `glsl-shaders`.
- `MpvController` applies the `gpu-next` non-Vulkan fallback without changing global settings.
- `AppSettingsStore` defaults and update methods persist Anime4K values.
- `PlayerOptionPanelUiTest` verifies the player menu includes Anime4K controls.

## Non-Goals

- User-provided custom shader management.
- Downloading shaders at runtime.
- Per-video persistent Anime4K overrides.
- Replacing the mpv Android library.
- Adding new Anime4K algorithms beyond the `mpvEx` shader set.
