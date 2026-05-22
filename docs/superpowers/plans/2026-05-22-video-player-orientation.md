# Video Player Orientation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent video player orientation setting and an in-player right-side button that toggles portrait and landscape.

**Architecture:** Keep Android orientation ownership in `VideoPlayerActivity`. Add a small player-orientation helper in the video player package so orientation decisions are JVM-testable, then thread the selected mode through settings and video player Intent extras. The Compose player screen exposes a right-side orientation action beside the existing track and info controls.

**Tech Stack:** Android Kotlin, Jetpack Compose Material 3, DataStore preferences, JUnit 4 JVM tests, Robolectric-capable Android unit tests.

---

## File Structure

- Create `app/src/main/java/com/example/comicdav/video/player/VideoPlayerOrientation.kt`
  - Owns `VideoPlayerOrientationMode`, labels, Activity orientation mapping, video-dimension resolution, and session-local manual override behavior.
- Create `app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt`
  - Tests orientation mode labels, default landscape behavior, video-dimension decisions, Activity constant mapping, and manual override behavior.
- Modify `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
  - Adds persisted `videoPlayerOrientationMode` with default `VIDEO`.
- Modify `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
  - Adds `播放器方向` dropdown to the existing `视频` settings group.
- Modify `app/src/main/java/com/example/comicdav/MainActivity.kt`
  - Passes `appSettings.videoPlayerOrientationMode` to local and WebDAV player Intents and updates settings callbacks.
- Modify `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
  - Reads the orientation mode extra, applies initial orientation, reacts to video dimension updates, and wires the right-side toggle.
- Modify `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`
  - Updates UI metadata expectations for the new side-rail orientation action.

## Task 1: Orientation Domain Helper

**Files:**
- Create: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt`
- Create: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerOrientation.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt`:

```kotlin
package com.example.comicdav.video.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlayerOrientationTest {
    @Test
    fun orientationModeLabelsExposeRequestedOptionsInOrder() {
        assertEquals(
            listOf("视频", "竖屏", "横屏", "传感器"),
            VideoPlayerOrientationMode.entries.map(::videoPlayerOrientationModeLabel),
        )
    }

    @Test
    fun videoModeDefaultsToLandscapeWhenDimensionsAreMissing() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.VIDEO, VideoParams()),
        )
    }

    @Test
    fun videoModeUsesPortraitForTallVideo() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientationForVideoPlayerMode(
                VideoPlayerOrientationMode.VIDEO,
                VideoParams(width = 720, height = 1280),
            ),
        )
    }

    @Test
    fun videoModeUsesLandscapeForWideOrSquareVideo() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(
                VideoPlayerOrientationMode.VIDEO,
                VideoParams(width = 1920, height = 1080),
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(
                VideoPlayerOrientationMode.VIDEO,
                VideoParams(width = 1000, height = 1000),
            ),
        )
    }

    @Test
    fun fixedAndSensorModesMapToActivityOrientationConstants() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.PORTRAIT, VideoParams()),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.LANDSCAPE, VideoParams()),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.SENSOR, VideoParams()),
        )
    }

    @Test
    fun manualToggleSwitchesBetweenFixedPortraitAndLandscape() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.VIDEO)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, session.initialRequestedOrientation())
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            session.toggleFixedOrientation(Configuration.ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            session.toggleFixedOrientation(Configuration.ORIENTATION_PORTRAIT),
        )
    }

    @Test
    fun manualToggleDisablesLaterVideoAutoUpdatesForCurrentPlayback() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.VIDEO)

        session.initialRequestedOrientation()
        session.toggleFixedOrientation(Configuration.ORIENTATION_LANDSCAPE)

        assertNull(session.requestForVideoParams(VideoParams(width = 720, height = 1280)))
    }

    @Test
    fun videoModeSessionUpdatesOnlyWhenDimensionsAreKnownAndNotManuallyOverridden() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.VIDEO)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, session.initialRequestedOrientation())
        assertNull(session.requestForVideoParams(VideoParams()))
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            session.requestForVideoParams(VideoParams(width = 720, height = 1280)),
        )
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerOrientationTest
```

Expected: FAIL because `VideoPlayerOrientationMode`, `videoPlayerOrientationModeLabel`, `requestedOrientationForVideoPlayerMode`, and `VideoPlayerOrientationSession` do not exist.

- [ ] **Step 3: Add the minimal orientation helper**

Create `app/src/main/java/com/example/comicdav/video/player/VideoPlayerOrientation.kt`:

```kotlin
package com.example.comicdav.video.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration

enum class VideoPlayerOrientationMode {
    VIDEO,
    PORTRAIT,
    LANDSCAPE,
    SENSOR,
}

internal fun videoPlayerOrientationModeLabel(mode: VideoPlayerOrientationMode): String =
    when (mode) {
        VideoPlayerOrientationMode.VIDEO -> "视频"
        VideoPlayerOrientationMode.PORTRAIT -> "竖屏"
        VideoPlayerOrientationMode.LANDSCAPE -> "横屏"
        VideoPlayerOrientationMode.SENSOR -> "传感器"
    }

internal fun requestedOrientationForVideoPlayerMode(
    mode: VideoPlayerOrientationMode,
    videoParams: VideoParams,
): Int =
    when (mode) {
        VideoPlayerOrientationMode.VIDEO -> requestedOrientationForVideoParams(videoParams)
        VideoPlayerOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        VideoPlayerOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        VideoPlayerOrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

internal class VideoPlayerOrientationSession(
    private val initialMode: VideoPlayerOrientationMode,
) {
    private var manualOverride = false
    private var lastFixedOrientation: Int? = null

    fun initialRequestedOrientation(): Int =
        requestedOrientationForVideoPlayerMode(initialMode, VideoParams())
            .also(::rememberFixedOrientation)

    fun requestForVideoParams(videoParams: VideoParams): Int? {
        if (initialMode != VideoPlayerOrientationMode.VIDEO || manualOverride) return null
        if (videoParams.width == null || videoParams.height == null) return null
        return requestedOrientationForVideoParams(videoParams)
            .also(::rememberFixedOrientation)
    }

    fun toggleFixedOrientation(currentConfigurationOrientation: Int): Int {
        manualOverride = true
        val currentFixed = lastFixedOrientation ?: fixedOrientationForConfiguration(currentConfigurationOrientation)
        val next = if (currentFixed == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        lastFixedOrientation = next
        return next
    }

    private fun rememberFixedOrientation(requestedOrientation: Int) {
        if (
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ) {
            lastFixedOrientation = requestedOrientation
        }
    }
}

private fun requestedOrientationForVideoParams(videoParams: VideoParams): Int {
    val width = videoParams.width
    val height = videoParams.height
    return if (width != null && height != null && height > width) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

private fun fixedOrientationForConfiguration(configurationOrientation: Int): Int =
    if (configurationOrientation == Configuration.ORIENTATION_PORTRAIT) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
```

- [ ] **Step 4: Run the orientation test and verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerOrientationTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/VideoPlayerOrientation.kt app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt
git commit -m "feat: add video player orientation model"
```

## Task 2: Settings And Intent Plumbing

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Modify: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt`

- [ ] **Step 1: Add failing tests for Intent extras and setting labels**

Append these tests to `VideoPlayerOrientationTest`:

```kotlin
    @Test
    fun localIntentCarriesSelectedOrientationMode() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = VideoPlayerActivity.localIntent(
            context = context,
            request = com.example.comicdav.video.LocalVideoOpenRequest(
                uri = "content://video/movie.mp4",
                displayName = "movie.mp4",
            ),
            playerOrientationMode = VideoPlayerOrientationMode.PORTRAIT,
        )

        assertEquals(
            VideoPlayerOrientationMode.PORTRAIT.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_PLAYER_ORIENTATION_MODE),
        )
    }

    @Test
    fun webDavIntentCarriesSelectedOrientationMode() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = VideoPlayerActivity.webDavIntent(
            context = context,
            request = com.example.comicdav.video.WebDavVideoOpenRequest(
                accountId = "account",
                remotePath = "/movie.mp4",
                displayName = "movie.mp4",
            ),
            uri = "http://127.0.0.1:8080/stream/movie",
            subtitleUrls = emptyList(),
            streamIds = emptyList(),
            playerOrientationMode = VideoPlayerOrientationMode.LANDSCAPE,
        )

        assertEquals(
            VideoPlayerOrientationMode.LANDSCAPE.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_PLAYER_ORIENTATION_MODE),
        )
    }
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerOrientationTest
```

Expected: FAIL because `EXTRA_PLAYER_ORIENTATION_MODE` and `playerOrientationMode` Intent parameters do not exist.

- [ ] **Step 3: Add AppSettings persistence plumbing**

In `AppSettingsStore.kt`, import the new mode:

```kotlin
import com.example.comicdav.video.player.VideoPlayerOrientationMode
```

Add the setting to `AppSettings` after `videoControlsAutoHideMillis`:

```kotlin
    val videoControlsAutoHideMillis: Int = 5_000,
    val videoPlayerOrientationMode: VideoPlayerOrientationMode = VideoPlayerOrientationMode.VIDEO,
```

Read it in the `AppSettings(...)` mapper:

```kotlin
            videoControlsAutoHideMillis = coerceVideoControlsAutoHideMillis(
                preferences[VIDEO_CONTROLS_AUTO_HIDE_MILLIS] ?: 5_000,
            ),
            videoPlayerOrientationMode = preferences[VIDEO_PLAYER_ORIENTATION_MODE]
                .toEnumOrDefault(VideoPlayerOrientationMode.VIDEO),
```

Add the updater:

```kotlin
    suspend fun updateVideoPlayerOrientationMode(mode: VideoPlayerOrientationMode) {
        dataStore.edit { preferences ->
            preferences[VIDEO_PLAYER_ORIENTATION_MODE] = mode.name
        }
    }
```

Add the key in the companion object:

```kotlin
        val VIDEO_PLAYER_ORIENTATION_MODE = stringPreferencesKey("video_player_orientation_mode")
```

- [ ] **Step 4: Add SettingsScreen UI plumbing**

In `SettingsScreen.kt`, import:

```kotlin
import com.example.comicdav.video.player.VideoPlayerOrientationMode
import com.example.comicdav.video.player.videoPlayerOrientationModeLabel
```

Add a parameter after `onVideoControlsAutoHideMillisChange`:

```kotlin
    onVideoPlayerOrientationModeChange: (VideoPlayerOrientationMode) -> Unit = {},
```

Add the dropdown in the `SettingsGroup(title = "视频")` block after `控制自动隐藏`:

```kotlin
            DropdownRow(
                title = "播放器方向",
                selected = settings.videoPlayerOrientationMode,
                options = VideoPlayerOrientationMode.entries,
                label = ::videoPlayerOrientationModeLabel,
                onSelected = onVideoPlayerOrientationModeChange,
            )
```

- [ ] **Step 5: Pass the setting from MainActivity**

In both `VideoPlayerActivity.localIntent(...)` and `VideoPlayerActivity.webDavIntent(...)` calls in `MainActivity.kt`, add:

```kotlin
                playerOrientationMode = appSettings.videoPlayerOrientationMode,
```

In the `SettingsScreen(...)` call in `MainActivity.kt`, add:

```kotlin
                                    onVideoPlayerOrientationModeChange = { value ->
                                        scope.launch { appSettingsStore.updateVideoPlayerOrientationMode(value) }
                                    },
```

- [ ] **Step 6: Add Intent extra support in VideoPlayerActivity**

In both `localIntent()` and `webDavIntent()` signatures, add:

```kotlin
            playerOrientationMode: VideoPlayerOrientationMode = VideoPlayerOrientationMode.VIDEO,
```

In both Intent builders, add:

```kotlin
                .putExtra(EXTRA_PLAYER_ORIENTATION_MODE, playerOrientationMode.name)
```

Add the companion constant next to the other video player extras:

```kotlin
        const val EXTRA_PLAYER_ORIENTATION_MODE = "com.example.comicdav.video.extra.PLAYER_ORIENTATION_MODE"
```

- [ ] **Step 7: Run the focused test and verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerOrientationTest
```

Expected: PASS.

- [ ] **Step 8: Commit Task 2**

Run:

```bash
git add app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt app/src/main/java/com/example/comicdav/MainActivity.kt app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt
git commit -m "feat: add video player orientation setting"
```

## Task 3: Apply Orientation In VideoPlayerActivity

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt`
- Create: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityOrientationSourceTest.kt`

- [ ] **Step 1: Add failing source-level integration tests for Activity orientation wiring**

Create `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityOrientationSourceTest.kt`:

```kotlin
package com.example.comicdav.video.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoPlayerActivityOrientationSourceTest {
    @Test
    fun playerActivityUsesOrientationSessionInsteadOfHardcodedSensor() {
        val source = playerActivitySourceFile().readText()

        assertTrue(source.contains("VideoPlayerOrientationSession(initialPlayerOrientationMode)"))
        assertTrue(source.contains("orientationSession.initialRequestedOrientation()"))
        assertFalse(source.contains("requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR"))
    }

    @Test
    fun playerActivityAppliesVideoParamsThroughOrientationSession() {
        val source = playerActivitySourceFile().readText()

        assertTrue(source.contains("LaunchedEffect(state.videoParams.width, state.videoParams.height)"))
        assertTrue(source.contains("orientationSession.requestForVideoParams(state.videoParams)"))
    }

    private fun playerActivitySourceFile(): File =
        sequenceOf(
            File("src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
        ).first { it.isFile }
}
```

Append this additional non-video session test to `VideoPlayerOrientationTest`:

```kotlin
    @Test
    fun nonVideoModeSessionDoesNotReactToVideoParams() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.SENSOR)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, session.initialRequestedOrientation())
        assertNull(session.requestForVideoParams(VideoParams(width = 720, height = 1280)))
    }
```

- [ ] **Step 2: Run the focused tests and verify the source test fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerOrientationTest --tests com.example.comicdav.video.player.VideoPlayerActivityOrientationSourceTest
```

Expected: FAIL because `VideoPlayerActivity` still hardcodes `requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR` and does not use `VideoPlayerOrientationSession`.

- [ ] **Step 3: Replace the hardcoded sensor orientation**

In `VideoPlayerActivity`, add a property near the other Activity fields:

```kotlin
    private lateinit var orientationSession: VideoPlayerOrientationSession
```

Replace the current hardcoded line in `onCreate()`:

```kotlin
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
```

with:

```kotlin
        val initialPlayerOrientationMode = intent.getStringExtra(EXTRA_PLAYER_ORIENTATION_MODE)
            .toEnumOrDefault(VideoPlayerOrientationMode.VIDEO)
        orientationSession = VideoPlayerOrientationSession(initialPlayerOrientationMode)
        requestedOrientation = orientationSession.initialRequestedOrientation()
```

- [ ] **Step 4: React to video dimension updates**

Inside the `setContent` block, after `val state by controller.state.collectAsState()`, add:

```kotlin
                LaunchedEffect(state.videoParams.width, state.videoParams.height) {
                    orientationSession.requestForVideoParams(state.videoParams)?.let { orientation ->
                        requestedOrientation = orientation
                    }
                }
```

- [ ] **Step 5: Run Activity orientation tests**
 
Run the Activity source and orientation tests:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerOrientationTest --tests com.example.comicdav.video.player.VideoPlayerActivityOrientationSourceTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/test/java/com/example/comicdav/video/player/VideoPlayerOrientationTest.kt app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityOrientationSourceTest.kt
git commit -m "feat: apply player orientation mode"
```

## Task 4: Add The Right-Side Orientation Button

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Modify: `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`

- [ ] **Step 1: Write the failing UI metadata test**

Add this test:

```kotlin
    @Test
    fun rightSideControlsIncludeOrientationBeforePanels() {
        assertEquals(
            listOf("切换横竖屏", "音轨与字幕", "播放信息"),
            rightSideControlDescriptions(),
        )
    }
```

- [ ] **Step 2: Run the UI metadata test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest
```

Expected: FAIL because `rightSideControlDescriptions()` does not exist.

- [ ] **Step 3: Add the orientation action metadata**

In `VideoPlayerActivity.kt`, add the icon import:

```kotlin
import androidx.compose.material.icons.filled.ScreenRotation
```

Near `PlayerOptionPanelDescriptor`, add:

```kotlin
private const val PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION = "切换横竖屏"

internal fun rightSideControlDescriptions(): List<String> =
    listOf(PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION) +
        PlayerOptionPanel.entries.map { it.sideRailDescriptor().contentDescription }
```

- [ ] **Step 4: Thread the callback through player composables**

In the `VideoPlayerScreen(...)` call from `VideoPlayerActivity.onCreate()`, add:

```kotlin
                    onOrientationToggle = {
                        requestedOrientation = orientationSession.toggleFixedOrientation(
                            resources.configuration.orientation,
                        )
                    },
```

Add `onOrientationToggle: () -> Unit` to the `VideoPlayerScreen(...)` signature immediately after `onDecoderModeSelected`:

```kotlin
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    onOrientationToggle: () -> Unit,
    onControlsLockedChanged: (Boolean) -> Unit,
```

Add the same parameter to `PlayerSideControls(...)`:

```kotlin
    onOrientationToggle: () -> Unit,
```

Add the same parameter to `EdgeFloatingControls(...)`:

```kotlin
    onOrientationToggle: () -> Unit,
```

Pass it through all call sites:

```kotlin
                    onOrientationToggle = onOrientationToggle,
```

- [ ] **Step 5: Render the right-side orientation button**

In `EdgeFloatingControls`, render the orientation button before `PlayerOptionPanel.entries`.

For compact mode, put this button at the top of the `FlowRow`:

```kotlin
            PlayerOverlayIconButton(
                icon = Icons.Filled.ScreenRotation,
                contentDescription = PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION,
                onClick = onOrientationToggle,
            )
```

For non-compact mode, put the same button at the top of the `Column`:

```kotlin
        PlayerOverlayIconButton(
            icon = Icons.Filled.ScreenRotation,
            contentDescription = PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION,
            onClick = onOrientationToggle,
        )
```

- [ ] **Step 6: Preserve compact side rail sizing**

Keep the existing assertion in `PlayerOptionPanelUiTest.playerControlSizingSupportsCenterPlaybackLockAndThinProgress()`:

```kotlin
        assertEquals(1, PLAYER_EDGE_FLOATING_CONTROLS_MAX_ITEMS)
```

The compact rail still stacks one item per row; it now stacks the orientation, track, and info controls vertically.

- [ ] **Step 7: Run player UI and orientation tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest --tests com.example.comicdav.video.player.VideoPlayerOrientationTest
```

Expected: PASS.

- [ ] **Step 8: Commit Task 4**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt
git commit -m "feat: add player orientation toggle"
```

## Task 5: Full Verification

**Files:**
- Verify all changed production and test files.

- [ ] **Step 1: Run the full app unit test suite**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Inspect git status**

Run:

```bash
git status --short
```

Expected: no unstaged edits except deliberate changes already committed. If there are uncommitted implementation edits, review them and commit with a focused message.

- [ ] **Step 3: Final implementation review**

Check these requirements manually against the code:

- `VIDEO` is the default setting and default Intent value.
- `VIDEO` starts landscape before dimensions are known.
- `VIDEO` never applies `SCREEN_ORIENTATION_SENSOR`.
- Tall videos lock portrait.
- Wide and square videos lock landscape.
- `SENSOR` applies `SCREEN_ORIENTATION_SENSOR`.
- The right-side button is visible with other unlocked controls.
- The button toggles current playback only and does not persist settings.
- Local and WebDAV playback pass the setting.

- [ ] **Step 4: Commit any final cleanup**

If Step 3 required edits, run the relevant focused tests again, then:

```bash
git add app/src/main/java/com/example/comicdav app/src/test/java/com/example/comicdav
git commit -m "fix: polish video player orientation behavior"
```

If Step 3 required no edits, do not create an empty commit.
