# Reader Landscape Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-reading-session landscape toggle to the comic reader.

**Architecture:** Keep Android orientation ownership in the `ComicDavApp` Activity layer. `ReaderScreen` exposes a small chrome button and emits a callback; `ReaderRoute` forwards state; `ComicDavApp` applies `requestedOrientation` from a pure orientation policy helper and clears the temporary flag on every reader close path.

**Tech Stack:** Kotlin, Jetpack Compose, Android `ActivityInfo`, JUnit 4, Gradle Android unit tests.

---

## File Structure

- `app/src/main/java/com/example/comicdav/AppNavigation.kt`: add pure helpers for reader-aware Activity orientation and reader landscape close reset.
- `app/src/main/java/com/example/comicdav/AppContentRoutes.kt`: forward reader landscape state and callback from `ComicDavApp` to `ReaderScreen`.
- `app/src/main/java/com/example/comicdav/MainActivity.kt`: hold `readerLandscapeModeEnabled`, apply requested orientation, pass state into `ReaderRoute`, and clear it when reader closes or loading is cancelled.
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`: add the top-bar landscape button, labels, and callback parameters.
- `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`: cover orientation policy and close reset helpers.
- `app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenTest.kt`: cover reader chrome button labels/order.

### Task 1: Reader Orientation Policy

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`
- Modify: `app/src/main/java/com/example/comicdav/AppNavigation.kt`

- [ ] **Step 1: Write the failing tests**

Add these tests to `MainActivityUiLogicTest` after `mainAppOrientationPolicyOnlyLocksWhenReaderRotationLockIsEnabled()`:

```kotlin
@Test
fun readerLandscapeModeOverridesGlobalOrientationOnlyWhileReaderIsOpen() {
    assertEquals(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        comicDavRequestedOrientation(
            screenRotationLockEnabled = false,
            isReaderOpen = true,
            readerLandscapeModeEnabled = true,
        ),
    )
    assertEquals(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        comicDavRequestedOrientation(
            screenRotationLockEnabled = true,
            isReaderOpen = true,
            readerLandscapeModeEnabled = true,
        ),
    )
    assertEquals(
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        comicDavRequestedOrientation(
            screenRotationLockEnabled = false,
            isReaderOpen = false,
            readerLandscapeModeEnabled = true,
        ),
    )
    assertEquals(
        ActivityInfo.SCREEN_ORIENTATION_LOCKED,
        comicDavRequestedOrientation(
            screenRotationLockEnabled = true,
            isReaderOpen = false,
            readerLandscapeModeEnabled = true,
        ),
    )
}

@Test
fun closingReaderClearsTemporaryLandscapeMode() {
    assertEquals(false, readerLandscapeModeAfterReaderClosed())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: compile failure because `comicDavRequestedOrientation` and `readerLandscapeModeAfterReaderClosed` do not exist.

- [ ] **Step 3: Write minimal implementation**

Add this to `AppNavigation.kt` after `mainAppRequestedOrientation`:

```kotlin
internal fun comicDavRequestedOrientation(
    screenRotationLockEnabled: Boolean,
    isReaderOpen: Boolean,
    readerLandscapeModeEnabled: Boolean,
): Int =
    if (isReaderOpen && readerLandscapeModeEnabled) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    } else {
        mainAppRequestedOrientation(screenRotationLockEnabled)
    }

internal fun readerLandscapeModeAfterReaderClosed(): Boolean = false
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: `MainActivityUiLogicTest` passes.

### Task 2: Reader Chrome Labels

**Files:**
- Create: `app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenTest.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`

- [ ] **Step 1: Write the failing tests**

Create `ReaderScreenTest.kt`:

```kotlin
package com.example.comicdav.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScreenTest {
    @Test
    fun topBarActionsExposeLandscapeBeforeLogAndClose() {
        assertEquals(
            listOf("横屏", "日志", "关闭"),
            readerTopBarActionLabels(readerLandscapeModeEnabled = false),
        )
    }

    @Test
    fun topBarActionsExposeExitLandscapeWhenEnabled() {
        assertEquals(
            listOf("退出横屏", "日志", "关闭"),
            readerTopBarActionLabels(readerLandscapeModeEnabled = true),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderScreenTest
```

Expected: compile failure because `readerTopBarActionLabels` does not exist.

- [ ] **Step 3: Write minimal implementation**

Add these helpers near the `ReaderTopBar` code in `ReaderScreen.kt`:

```kotlin
internal fun readerLandscapeModeButtonLabel(readerLandscapeModeEnabled: Boolean): String =
    if (readerLandscapeModeEnabled) "退出横屏" else "横屏"

internal fun readerTopBarActionLabels(readerLandscapeModeEnabled: Boolean): List<String> =
    listOf(
        readerLandscapeModeButtonLabel(readerLandscapeModeEnabled),
        ComicDavCopy.readerLog,
        ComicDavCopy.readerClose,
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderScreenTest
```

Expected: `ReaderScreenTest` passes.

### Task 3: Wire Reader UI And Activity Orientation

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/AppContentRoutes.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`

- [ ] **Step 1: Extend ReaderScreen API**

Update `ReaderScreen` parameters:

```kotlin
readerLandscapeModeEnabled: Boolean = false,
onReaderLandscapeModeChange: (Boolean) -> Unit = {},
```

Pass them to `ReaderTopBar` in the loaded-reader overlay:

```kotlin
ReaderTopBar(
    title = "正在阅读",
    subtitle = "共 ${uiState.pageCount} 页",
    readerLandscapeModeEnabled = readerLandscapeModeEnabled,
    onReaderLandscapeModeChange = onReaderLandscapeModeChange,
    onChooseLogFile = onChooseLogFile,
    onClose = onClose,
    modifier = Modifier.align(Alignment.TopCenter),
)
```

Keep empty and error states using default values so they do not show a landscape toggle.

- [ ] **Step 2: Update ReaderTopBar**

Update `ReaderTopBar` signature:

```kotlin
private fun ReaderTopBar(
    title: String,
    subtitle: String? = null,
    readerLandscapeModeEnabled: Boolean = false,
    onReaderLandscapeModeChange: (Boolean) -> Unit = {},
    onChooseLogFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Add the button before the log button:

```kotlin
ReaderChromeButton(
    text = readerLandscapeModeButtonLabel(readerLandscapeModeEnabled),
    onClick = { onReaderLandscapeModeChange(!readerLandscapeModeEnabled) },
)
ReaderChromeButton(text = ComicDavCopy.readerLog, onClick = onChooseLogFile)
ReaderChromeButton(text = ComicDavCopy.readerClose, onClick = onClose)
```

- [ ] **Step 3: Forward through ReaderRoute**

Add parameters to `ReaderRoute` in `AppContentRoutes.kt`:

```kotlin
readerLandscapeModeEnabled: Boolean = false,
onReaderLandscapeModeChange: (Boolean) -> Unit = {},
```

Pass them to `ReaderScreen`:

```kotlin
readerLandscapeModeEnabled = readerLandscapeModeEnabled,
onReaderLandscapeModeChange = onReaderLandscapeModeChange,
```

- [ ] **Step 4: Store and apply Activity orientation**

In `ComicDavApp`, add state near `isReaderOpen`:

```kotlin
var readerLandscapeModeEnabled by rememberSaveable { mutableStateOf(false) }
```

Replace both uses of `mainAppRequestedOrientation(appSettings.screenRotationLockEnabled)` in the orientation effects with:

```kotlin
comicDavRequestedOrientation(
    screenRotationLockEnabled = appSettings.screenRotationLockEnabled,
    isReaderOpen = isReaderOpen,
    readerLandscapeModeEnabled = readerLandscapeModeEnabled,
)
```

Include `isReaderOpen` and `readerLandscapeModeEnabled` in the relevant `LaunchedEffect` and `DisposableEffect` keys.

- [ ] **Step 5: Clear landscape state on reader close paths**

Set:

```kotlin
readerLandscapeModeEnabled = readerLandscapeModeAfterReaderClosed()
```

in these paths before or alongside `isReaderOpen = false`:

- `closeReaderFromNavigation()`
- `ReaderRoute` `onCancelLoading`
- `ReaderRoute` `onClose`

- [ ] **Step 6: Pass state into ReaderRoute**

In the `ReaderRoute` call, add:

```kotlin
readerLandscapeModeEnabled = readerLandscapeModeEnabled,
onReaderLandscapeModeChange = { value ->
    readerLandscapeModeEnabled = value
},
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest --tests com.example.comicdav.feature.reader.ReaderScreenTest
```

Expected: both focused test classes pass.

### Task 4: Full Verification

**Files:**
- No new code files.

- [ ] **Step 1: Run Android unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all app unit tests pass.

- [ ] **Step 2: Inspect git diff**

Run:

```bash
git diff -- app/src/main/java/com/example/comicdav/AppNavigation.kt app/src/main/java/com/example/comicdav/AppContentRoutes.kt app/src/main/java/com/example/comicdav/MainActivity.kt app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenTest.kt
```

Expected: diff only contains the reader landscape mode feature and tests.

- [ ] **Step 3: Commit implementation**

Run:

```bash
git add -f docs/superpowers/plans/2026-07-06-reader-landscape-mode.md
git add app/src/main/java/com/example/comicdav/AppNavigation.kt app/src/main/java/com/example/comicdav/AppContentRoutes.kt app/src/main/java/com/example/comicdav/MainActivity.kt app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenTest.kt
git commit -m "feat: add reader landscape toggle"
```

Expected: commit succeeds with the implementation and plan.
