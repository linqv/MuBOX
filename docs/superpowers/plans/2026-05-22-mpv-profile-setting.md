# MPV Profile Setting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persisted MPV Profile dropdown to MuBOX video settings and apply it before mpv playback initialization.

**Architecture:** Reuse the existing video setting path: `AppSettingsStore` persists enum settings, `SettingsScreen` exposes a dropdown, `MainActivity` passes the selected mode into `VideoPlayerActivity`, and `MuBoxMpvView.initOptions()` applies the profile through `MPVLib.setOptionString("profile", value)`. Existing VO, GPU API, and decoder settings remain separate explicit options.

**Tech Stack:** Kotlin, Android DataStore Preferences, Jetpack Compose, Robolectric/JUnit, libmpv Android wrapper.

---

### Task 1: Persist MPV Profile Setting

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/MpvController.kt`
- Modify: `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- Test: `app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt`

- [ ] **Step 1: Write failing DataStore tests**

Add assertions that the default MPV profile is `MpvProfileMode.FAST` and that updates persist:

```kotlin
assertEquals(MpvProfileMode.FAST, settings.mpvProfileMode)
store.updateMpvProfileMode(MpvProfileMode.HIGH_QUALITY)
assertEquals(MpvProfileMode.HIGH_QUALITY, store.settings.first().mpvProfileMode)
```

- [ ] **Step 2: Run tests and verify RED**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest`

Expected: compilation fails because `MpvProfileMode`, `mpvProfileMode`, and `updateMpvProfileMode` do not exist.

- [ ] **Step 3: Add enum and persistence**

Add `MpvProfileMode` in `MpvController.kt`:

```kotlin
enum class MpvProfileMode(val profile: String) {
    FAST("fast"),
    DEFAULT("default"),
    HIGH_QUALITY("high-quality"),
    GPU_HQ("gpu-hq"),
    LOW_LATENCY("low-latency"),
    SW_FAST("sw-fast"),
}

internal fun mpvProfileModeLabel(mode: MpvProfileMode): String =
    when (mode) {
        MpvProfileMode.FAST -> "Fast"
        MpvProfileMode.DEFAULT -> "Default"
        MpvProfileMode.HIGH_QUALITY -> "High Quality"
        MpvProfileMode.GPU_HQ -> "GPU HQ"
        MpvProfileMode.LOW_LATENCY -> "Low Latency"
        MpvProfileMode.SW_FAST -> "SW Fast"
    }
```

Extend `AppSettings` with `val mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST`, read it with `toEnumOrDefault(MpvProfileMode.FAST)`, add `updateMpvProfileMode`, and add the `MPV_PROFILE_MODE` string preferences key.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest`

Expected: `AppSettingsStoreTest` passes.

### Task 2: Wire Setting Through UI and Intents

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntentTest.kt`

- [ ] **Step 1: Write failing intent tests**

In `VideoPlayerActivityIntentTest`, extend defaults and configured backend assertions:

```kotlin
assertEquals(MpvProfileMode.FAST, settings.mpvProfileMode)
mpvProfileMode = MpvProfileMode.LOW_LATENCY
assertEquals(MpvProfileMode.LOW_LATENCY.name, intent.getStringExtra(VideoPlayerActivity.EXTRA_MPV_PROFILE_MODE))
```

- [ ] **Step 2: Run tests and verify RED**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntentTest`

Expected: compilation fails because the intent extra and parameters do not exist.

- [ ] **Step 3: Add UI and launch plumbing**

Add `onMpvProfileModeChange` to `SettingsScreen`, import `MpvProfileMode` and `mpvProfileModeLabel`, and add a `DropdownRow` in the "视频" group:

```kotlin
DropdownRow(
    title = "MPV Profile",
    selected = settings.mpvProfileMode,
    options = MpvProfileMode.entries,
    label = ::mpvProfileModeLabel,
    onSelected = onMpvProfileModeChange,
)
```

In `MainActivity`, pass `appSettings.mpvProfileMode` into both `VideoPlayerActivity.localIntent` and `VideoPlayerActivity.webDavIntent`, and wire the settings callback to `appSettingsStore.updateMpvProfileMode(value)`.

In `VideoPlayerActivity`, add `EXTRA_MPV_PROFILE_MODE`, add `mpvProfileMode` parameters to `localIntent` and `webDavIntent`, and parse the initial value with `toEnumOrDefault(MpvProfileMode.FAST)`.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntentTest`

Expected: `VideoPlayerActivityIntentTest` passes.

### Task 3: Apply Profile Before MPV Options

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/ViewBackedMpvEngineTest.kt`

- [ ] **Step 1: Write failing source-order test**

Add a test asserting `MuBoxMpvView` has a configurable `mpvProfileMode`, calls `MPVLib.setOptionString("profile", mpvProfileMode.profile)`, and the profile call appears before `setVo("gpu")`.

- [ ] **Step 2: Run tests and verify RED**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.ViewBackedMpvEngineTest`

Expected: fails because profile setup is missing.

- [ ] **Step 3: Implement profile application**

Add to `MuBoxMpvView`:

```kotlin
var mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST
```

At the start of `initOptions()`:

```kotlin
MPVLib.setOptionString("profile", mpvProfileMode.profile)
```

In `VideoPlayerActivity.onCreate`, set `mpvView.mpvProfileMode = initialMpvProfileMode` before `prepareMpv()`.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.ViewBackedMpvEngineTest`

Expected: `ViewBackedMpvEngineTest` passes.

### Task 4: Verify Integrated Build

**Files:**
- No source edits unless verification exposes a regression.

- [ ] **Step 1: Run focused tests together**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntentTest --tests com.example.comicdav.video.player.ViewBackedMpvEngineTest`

Expected: all focused tests pass.

- [ ] **Step 2: Run app unit tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest`

Expected: app debug unit tests pass.

- [ ] **Step 3: Review diff**

Run: `git diff -- app/src/main/java app/src/test/java`

Expected: diff only touches the MPV profile setting path and tests.
