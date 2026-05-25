# Comic Reader Pinch Zoom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an off-by-default comic reader setting that enables two-finger pinch zoom and panning inside manga pages.

**Architecture:** Persist a new boolean in `AppSettingsStore`, expose it from the comic settings screen, route it into `ReaderScreen`, and keep zoom state local to each rendered reader page. The zoom math is pure and unit-tested; Compose gesture code stays thin and only installs when the setting is enabled.

**Tech Stack:** Android Kotlin, Jetpack Compose Foundation/Material3, DataStore Preferences, Gradle unit tests with JUnit.

---

## File Structure

- Modify `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
  - Add `readerPinchZoomEnabled` to `AppSettings`.
  - Read/write a new `reader_pinch_zoom_enabled` boolean preference.
- Modify `app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt`
  - Cover default false and update/readback.
- Modify `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
  - Add the comic settings layout row and visible `SwitchRow`.
  - Add `onReaderPinchZoomEnabledChange`.
- Modify `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`
  - Assert the comic settings layout includes `双指缩放`.
- Modify `app/src/main/java/com/example/comicdav/AppContentRoutes.kt`
  - Wire settings updates to `AppSettingsStore`.
  - Pass `appSettings.readerPinchZoomEnabled` to the reader route.
- Modify `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
  - Add `pinchZoomEnabled`.
  - Add pure zoom state helpers.
  - Add page-local graphics transform and raw pointer handling.
- Modify `app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenSettingsTest.kt`
  - Cover zoom scale clamping and offset reset at 1x.

---

### Task 1: Persist the Reader Pinch Zoom Setting

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt`
- Modify: `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`

- [ ] **Step 1: Write the failing DataStore tests**

Add these tests above `private fun TestScope.createStore(...)` in `AppSettingsStoreTest.kt`:

```kotlin
@Test
fun readerPinchZoomDefaultsOff() = runTest {
    val store = createStore("reader_pinch_zoom_default.preferences_pb")

    assertFalse(store.settings.first().readerPinchZoomEnabled)
}

@Test
fun readerPinchZoomCanBeUpdatedAndReadBack() = runTest {
    val store = createStore("reader_pinch_zoom_update.preferences_pb")

    store.updateReaderPinchZoomEnabled(true)

    assertTrue(store.settings.first().readerPinchZoomEnabled)
}
```

- [ ] **Step 2: Run the failing DataStore tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest
```

Expected: FAIL because `readerPinchZoomEnabled` and `updateReaderPinchZoomEnabled` are unresolved.

- [ ] **Step 3: Implement the setting storage**

In `AppSettingsStore.kt`, add the property near the other reader settings:

```kotlin
data class AppSettings(
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val readerLoggingMode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val avifImagesEnabled: Boolean = false,
    val autoPageEnabled: Boolean = false,
    val autoPageSpeedMillis: Int = 5_000,
    val screenRotationLockEnabled: Boolean = false,
    val volumeKeysTurnPagesEnabled: Boolean = false,
    val readerPinchZoomEnabled: Boolean = false,
```

Read the preference in the `AppSettings(...)` mapping after `volumeKeysTurnPagesEnabled`:

```kotlin
volumeKeysTurnPagesEnabled = preferences[VOLUME_KEYS_TURN_PAGES_ENABLED] ?: false,
readerPinchZoomEnabled = preferences[READER_PINCH_ZOOM_ENABLED] ?: false,
diskCacheLimitMb = coerceStoredDiskCacheLimitMb(preferences[DISK_CACHE_LIMIT_MB] ?: 1024),
```

Add the update method after `updateVolumeKeysTurnPagesEnabled`:

```kotlin
suspend fun updateReaderPinchZoomEnabled(enabled: Boolean) {
    dataStore.edit { preferences ->
        preferences[READER_PINCH_ZOOM_ENABLED] = enabled
    }
}
```

Add the DataStore key near the other reader keys:

```kotlin
val VOLUME_KEYS_TURN_PAGES_ENABLED = booleanPreferencesKey("volume_keys_turn_pages_enabled")
val READER_PINCH_ZOOM_ENABLED = booleanPreferencesKey("reader_pinch_zoom_enabled")
val DISK_CACHE_LIMIT_MB = intPreferencesKey("disk_cache_limit_gb")
```

- [ ] **Step 4: Run the DataStore tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt
git commit -m "feat: persist reader pinch zoom setting"
```

---

### Task 2: Expose the Setting in Comic Settings and Routes

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/AppContentRoutes.kt`

- [ ] **Step 1: Write the failing settings layout test**

Update the expected rows in `SettingsScreenUiTest.kt`:

```kotlin
assertEquals(
    listOf(
        "阅读方向",
        "音量键翻页",
        "双指缩放",
        "WebDAV 预取页数",
        "诊断日志",
        "AVIF 图片",
        "书架封面",
        "启用自动翻页",
        "翻页速度",
    ),
    comicRows,
)
```

- [ ] **Step 2: Run the failing settings layout test**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: FAIL because `comicSettingsGroupLayout()` does not include `双指缩放`.

- [ ] **Step 3: Add the settings UI contract**

In `SettingsScreen.kt`, add `双指缩放` to `comicSettingsGroupLayout()` immediately after `音量键翻页`:

```kotlin
rows = listOf(
    "阅读方向",
    "音量键翻页",
    "双指缩放",
    "WebDAV 预取页数",
    "诊断日志",
    "AVIF 图片",
    "书架封面",
    "启用自动翻页",
    "翻页速度",
),
```

Add `onReaderPinchZoomEnabledChange` to `SettingsScreen(...)` after `onVolumeKeysTurnPagesChange`:

```kotlin
onVolumeKeysTurnPagesChange: (Boolean) -> Unit,
onReaderPinchZoomEnabledChange: (Boolean) -> Unit = {},
onDiskCacheLimitChange: (Int) -> Unit,
```

Pass it into `ComicSettingsPage(...)`:

```kotlin
onVolumeKeysTurnPagesChange = onVolumeKeysTurnPagesChange,
onReaderPinchZoomEnabledChange = onReaderPinchZoomEnabledChange,
onWebDavPrefetchPageCountChange = onWebDavPrefetchPageCountChange,
```

Add it to the `ComicSettingsPage(...)` signature:

```kotlin
onVolumeKeysTurnPagesChange: (Boolean) -> Unit,
onReaderPinchZoomEnabledChange: (Boolean) -> Unit,
onWebDavPrefetchPageCountChange: (Int) -> Unit,
```

Add the visible row after `音量键翻页`:

```kotlin
SwitchRow(
    title = "双指缩放",
    subtitle = "在阅读时用双指放大并拖动查看细节",
    checked = settings.readerPinchZoomEnabled,
    onCheckedChange = onReaderPinchZoomEnabledChange,
)
```

- [ ] **Step 4: Wire settings through app routes**

In `AppContentRoutes.kt`, pass the update handler to `SettingsScreen(...)` after `onVolumeKeysTurnPagesChange`:

```kotlin
onVolumeKeysTurnPagesChange = { value ->
    scope.launch { appSettingsStore.updateVolumeKeysTurnPagesEnabled(value) }
},
onReaderPinchZoomEnabledChange = { value ->
    scope.launch { appSettingsStore.updateReaderPinchZoomEnabled(value) }
},
onDiskCacheLimitChange = { value ->
    scope.launch { appSettingsStore.updateDiskCacheLimitMb(value) }
},
```

- [ ] **Step 5: Run the settings layout test**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt app/src/main/java/com/example/comicdav/AppContentRoutes.kt app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt
git commit -m "feat: expose reader pinch zoom setting"
```

---

### Task 3: Add Tested Reader Zoom Math

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenSettingsTest.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`

- [ ] **Step 1: Write the failing zoom math tests**

Add these imports to `ReaderScreenSettingsTest.kt`:

```kotlin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
```

Add these tests inside `ReaderScreenSettingsTest`:

```kotlin
@Test
fun readerZoomScaleIsClampedToSupportedRange() {
    val viewport = IntSize(width = 1_000, height = 1_500)

    val zoomedOut = readerZoomStateAfterTransform(
        current = ReaderZoomState(scale = 2f, offsetX = 20f, offsetY = 30f),
        zoomChange = 0.1f,
        pan = Offset.Zero,
        viewportSize = viewport,
    )
    val zoomedIn = readerZoomStateAfterTransform(
        current = ReaderZoomState(scale = 2f),
        zoomChange = 10f,
        pan = Offset.Zero,
        viewportSize = viewport,
    )

    assertEquals(1f, zoomedOut.scale, 0.001f)
    assertEquals(0f, zoomedOut.offsetX, 0.001f)
    assertEquals(0f, zoomedOut.offsetY, 0.001f)
    assertEquals(4f, zoomedIn.scale, 0.001f)
}

@Test
fun readerZoomPanIsClampedToScaledViewport() {
    val viewport = IntSize(width = 1_000, height = 1_500)

    val state = readerZoomStateAfterTransform(
        current = ReaderZoomState(scale = 2f),
        zoomChange = 1f,
        pan = Offset(x = 900f, y = -900f),
        viewportSize = viewport,
    )

    assertEquals(500f, state.offsetX, 0.001f)
    assertEquals(-750f, state.offsetY, 0.001f)
}
```

- [ ] **Step 2: Run the failing reader settings test**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderScreenSettingsTest
```

Expected: FAIL because `ReaderZoomState` and `readerZoomStateAfterTransform` are unresolved.

- [ ] **Step 3: Add pure zoom helpers**

In `ReaderScreen.kt`, add imports:

```kotlin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
```

Add these helpers after `reportableContinuousPageChange(...)`:

```kotlin
internal const val ReaderMinZoom = 1f
internal const val ReaderMaxZoom = 4f

internal data class ReaderZoomState(
    val scale: Float = ReaderMinZoom,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

internal fun readerZoomStateAfterTransform(
    current: ReaderZoomState,
    zoomChange: Float,
    pan: Offset,
    viewportSize: IntSize,
): ReaderZoomState {
    val nextScale = (current.scale * zoomChange).coerceIn(ReaderMinZoom, ReaderMaxZoom)
    if (nextScale <= ReaderMinZoom || viewportSize.width <= 0 || viewportSize.height <= 0) {
        return ReaderZoomState()
    }
    val maxOffsetX = ((nextScale - ReaderMinZoom) * viewportSize.width / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((nextScale - ReaderMinZoom) * viewportSize.height / 2f).coerceAtLeast(0f)
    return ReaderZoomState(
        scale = nextScale,
        offsetX = (current.offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
        offsetY = (current.offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY),
    )
}
```

- [ ] **Step 4: Run the reader settings test**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderScreenSettingsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenSettingsTest.kt
git commit -m "feat: add reader zoom state math"
```

---

### Task 4: Connect Pinch Zoom to the Reader UI

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/AppContentRoutes.kt`

- [ ] **Step 1: Pass the setting into `ReaderScreen`**

In `ReaderScreen(...)`, add the parameter after `volumeKeysTurnPages`:

```kotlin
volumeKeysTurnPages: Boolean = false,
pinchZoomEnabled: Boolean = false,
```

In all three `ReaderImagePage(...)` calls, pass the setting:

```kotlin
pinchZoomEnabled = pinchZoomEnabled,
```

In `ReaderImagePage(...)`, add the parameter:

```kotlin
fillWidth: Boolean = false,
pinchZoomEnabled: Boolean = false,
```

In `ReaderRoute(...)` in `AppContentRoutes.kt`, pass the persisted value after `volumeKeysTurnPages`:

```kotlin
volumeKeysTurnPages = appSettings.volumeKeysTurnPagesEnabled,
pinchZoomEnabled = appSettings.readerPinchZoomEnabled,
modifier = modifier,
```

- [ ] **Step 2: Add Compose imports for zoom gestures**

In `ReaderScreen.kt`, add these imports:

```kotlin
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
```

- [ ] **Step 3: Add zoom state to `ReaderImagePage`**

At the start of `ReaderImagePage(...)`, after `continuousImageReady`, add:

```kotlin
var zoomState by remember(page, pageFile?.absolutePath, fillWidth) {
    mutableStateOf(ReaderZoomState())
}
var viewportSize by remember(page, pageFile?.absolutePath, fillWidth) {
    mutableStateOf(IntSize.Zero)
}
val latestZoomState by rememberUpdatedState(zoomState)
val latestZoomStateUpdater by rememberUpdatedState<(ReaderZoomState) -> Unit> { nextState ->
    zoomState = nextState
}
```

- [ ] **Step 4: Add a reusable zoom modifier helper**

Add this private helper after `readerZoomStateAfterTransform(...)`:

```kotlin
private fun Modifier.readerZoomTransform(
    enabled: Boolean,
    zoomState: ReaderZoomState,
    viewportSize: IntSize,
    currentZoomState: () -> ReaderZoomState,
    onZoomStateChanged: (ReaderZoomState) -> Unit,
): Modifier {
    val transformed = if (enabled) {
        this.graphicsLayer {
            scaleX = zoomState.scale
            scaleY = zoomState.scale
            translationX = zoomState.offsetX
            translationY = zoomState.offsetY
        }
    } else {
        this
    }
    if (!enabled) return transformed
    return transformed.pointerInput(viewportSize) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }
                val current = currentZoomState()
                when {
                    pressedChanges.size > 1 -> {
                        val nextState = readerZoomStateAfterTransform(
                            current = current,
                            zoomChange = event.calculateZoom(),
                            pan = event.calculatePan(),
                            viewportSize = viewportSize,
                        )
                        if (nextState != current) {
                            onZoomStateChanged(nextState)
                        }
                        event.changes.forEach { it.consume() }
                    }
                    current.scale > ReaderMinZoom && pressedChanges.size == 1 -> {
                        val change = pressedChanges.first()
                        val pan = change.positionChange()
                        val nextState = readerZoomStateAfterTransform(
                            current = current,
                            zoomChange = 1f,
                            pan = pan,
                            viewportSize = viewportSize,
                        )
                        if (nextState != current) {
                            onZoomStateChanged(nextState)
                        }
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
```

- [ ] **Step 5: Apply size tracking, clipping, and zoom transform to the image layer**

Update the outer `Box` modifier in `ReaderImagePage(...)` so both modes track size and clip drawing:

```kotlin
modifier = if (fillWidth) {
    modifier
        .fillMaxWidth()
        .background(Color.Black)
        .clipToBounds()
        .onSizeChanged { viewportSize = it }
} else {
    modifier
        .fillMaxSize()
        .clipToBounds()
        .onSizeChanged { viewportSize = it }
},
```

In the `AsyncImage(...)` modifier, append the zoom transform after the existing size modifier:

```kotlin
modifier = (if (fillWidth) {
    Modifier
        .fillMaxWidth()
        .then(if (continuousImageReady) Modifier else Modifier.height(ContinuousPageLoadingHeight))
} else {
    Modifier.fillMaxSize()
}).readerZoomTransform(
    enabled = pinchZoomEnabled && continuousImageReady,
    zoomState = zoomState,
    viewportSize = viewportSize,
    currentZoomState = { latestZoomState },
    onZoomStateChanged = latestZoomStateUpdater,
),
```

- [ ] **Step 6: Run targeted reader and settings tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderScreenSettingsTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest --tests com.example.comicdav.data.AppSettingsStoreTest
```

Expected: PASS.

- [ ] **Step 7: Run a debug compile**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:compileDebugKotlin
```

Expected: PASS with no Kotlin compile errors.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt app/src/main/java/com/example/comicdav/AppContentRoutes.kt
git commit -m "feat: enable reader pinch zoom"
```

---

### Task 5: Final Verification

**Files:**
- Read: all modified files

- [ ] **Step 1: Run the focused test suite**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest --tests com.example.comicdav.feature.reader.ReaderScreenSettingsTest
```

Expected: PASS.

- [ ] **Step 2: Run the app unit tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Run a debug build**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Inspect git diff**

Run:

```bash
git diff --stat HEAD~4..HEAD
git status --short
```

Expected: only the planned app source/test files are changed or committed, and `git status --short` has no unrelated working-tree changes from this implementation.
