# Cinematic UI Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved bounded cinematic visual refresh to MuBOX app screens and player controls without changing media routing, playback core, WebDAV behavior, or persisted data.

**Architecture:** Keep the existing Jetpack Compose screens and Material 3 component structure. Split work by disjoint UI ownership: theme/app shell, video library, source/WebDAV lists, and player overlay. Changes should be visual or lightweight interaction polish only, with focused unit tests for stable helper contracts and a final build/test verification.

**Tech Stack:** Android Kotlin, Jetpack Compose, Material 3, Gradle, JUnit JVM tests.

---

## File Structure

- Modify: `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`
  - Owns default cinematic color tokens and typography cleanup.
- Modify: `app/src/main/java/com/example/comicdav/AppNavigation.kt`
  - Owns dark media-style app shell bottom navigation and selection action bar.
- Test: `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`
  - Keeps stable app-shell helper contracts.
- Modify: `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt`
  - Owns cinematic video cards, empty state, selected state, fallback thumbnail treatment.
- Modify: `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`
  - Owns local source home, browse headers, source rows, entry rows, and local entry icon treatment.
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
  - Owns WebDAV app bar, rows, icon treatment, transfer panel, and progress colors.
- Test: `app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreenTest.kt`
  - Adds or preserves stable helper tests for source/list labels if needed.
- Test: `app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt`
  - Adds or preserves stable helper tests for row labels and byte formatting if needed.
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt`
  - Owns floating cinema player overlay visuals, button sizes, bottom panel, side rail, sheet, HUD.
- Test: `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`
  - Updates stable player UI sizing/label tests if constants intentionally change.
- Verification only: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
  - Inspect after controls change. Modify only if overlay placement must change; otherwise leave untouched.

## Coordination Rules

- Multiple workers may run in parallel, but each worker owns only the files listed in its task.
- Workers are not alone in the codebase. Do not revert edits made by other workers. If another worker's committed change affects your task, adapt to it.
- Do not edit `MainActivity.kt`, data repositories, WebDAV networking/proxy code, mpv controller logic, database entities, or media detection logic.
- Do not add dependencies.
- Do not assert raw color values in tests unless the helper contract explicitly exposes them.
- Use `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` for Gradle commands.

---

### Task 1: Theme And App Shell

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`
- Modify: `app/src/main/java/com/example/comicdav/AppNavigation.kt`
- Test: `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`

- [ ] **Step 1: Run current app-shell tests for baseline**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: PASS before changes.

- [ ] **Step 2: Update default theme tokens**

In `ComicDavTheme.kt`, update only `ComicDavLightColors` so the default palette becomes the approved cinematic dark palette:

- `primary`: cyan media accent.
- `secondary`: purple support accent.
- `tertiary`: amber status accent.
- `background`: deep ink-blue.
- `surface` and `surfaceContainer*`: layered dark blue/slate surfaces.
- `on*` values: high-contrast light text on dark surfaces.
- Error colors: dark-compatible red pairs.

Keep `ComicDavSepiaColors`, `ComicDavNightColors`, and `ComicDavHighContrastColors` available.

- [ ] **Step 3: Remove negative/overly decorative tracking in compact typography**

In `ComicDavTypography`, ensure compact styles used in controls and lists do not have negative letter spacing. Use `0.sp` or existing non-negative values. Preserve the existing type scale.

- [ ] **Step 4: Refresh app shell navigation**

In `AppNavigation.kt`:

- Keep `AppTab` and tab ordering unchanged.
- Keep `NavigationBar` structure unchanged.
- Use darker `containerColor` for the standard bottom bar and selection action bar.
- Keep selected tab clearly visible through `primary`/`primaryContainer` colors.
- Keep unselected text readable through `onSurfaceVariant`.
- Keep all labels at one line and touch targets unchanged.
- Keep `SelectionAction` and action construction unchanged.

- [ ] **Step 5: Run app-shell tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt app/src/main/java/com/example/comicdav/AppNavigation.kt app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt
git commit -m "feat: add cinematic app shell theme"
```

---

### Task 2: Video Library Cinematic Cards

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt`

- [ ] **Step 1: Run current available UI logic tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: PASS before changes.

- [ ] **Step 2: Refresh the video library header and messages**

In `VideoLibraryScreen.kt`:

- Keep `VideoLibraryScreen` parameters unchanged.
- Keep `AnimatedContent`, loading behavior, and callbacks unchanged.
- Make the page background and message panels work with the cinematic dark theme.
- Keep the top "影视库" title and "来源" action.

- [ ] **Step 3: Refresh empty video library state**

In `EmptyVideoLibrary`:

- Keep a central icon, title, support text, and one Sources action.
- Change the icon backing to a dark cinematic gradient or surface with cyan accent.
- Keep button minimum height at least 48dp.
- Do not add new actions.

- [ ] **Step 4: Refresh video cards**

In `VideoLibraryCard`:

- Keep 16:9 thumbnails and adaptive grid.
- Use a dark media surface for card fallback and selected states.
- Strengthen overlay readability with a vertical dark gradient on thumbnails.
- Keep central play affordance visible and at least 44dp touch-sized visually/semantically through the card click target.
- Use cyan border/shadow or dark surface for selected state.
- Keep title max lines and metadata line behavior.

- [ ] **Step 5: Refresh missing-thumbnail fallback**

In `FallbackVideoTitle`:

- Use a dark gradient poster-like backing inherited from the card.
- Keep title text readable.
- Do not add network/image dependencies.

- [ ] **Step 6: Run compile-focused test task**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: PASS and Kotlin compilation succeeds for changed Compose code.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt
git commit -m "feat: refresh video library cards"
```

---

### Task 3: Sources And WebDAV Lists

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Optional Test: `app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreenTest.kt`
- Optional Test: `app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt`

- [ ] **Step 1: Run existing helper tests that cover routing and labels**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: PASS before changes.

- [ ] **Step 2: Refresh source home and browse headers**

In `FileDirectoryScreen.kt`:

- Keep all public parameters unchanged.
- Keep source add/open callbacks unchanged.
- Refresh `FileDirectoryHomeHeader` and `FileDirectoryBrowseHeader` to sit on dark cinematic surfaces.
- Keep the add button discoverable and at least 44dp.
- Keep path/title text ellipsized.

- [ ] **Step 3: Refresh local source and entry rows**

In `DirectorySourceRow`, `FileDirectoryEntryRow`, `EntryTypeIcon`, `SourceBadge`, and related local helpers:

- Keep row click/long-click behavior unchanged.
- Use dark row surfaces with stronger selected states.
- Use differentiated icon containers for folder/comic/video/subtitle.
- Preserve existing content descriptions.
- Keep row labels and supporting labels unchanged unless text no longer fits.

- [ ] **Step 4: Refresh WebDAV browser app bar and rows**

In `WebDavBrowserScreen.kt`:

- Keep public parameters unchanged.
- Refresh `WebDavBrowserAppBar`, path bar, and row surfaces for dark cinematic styling.
- Keep save-directory button logic unchanged.
- Keep row click/long-click behavior unchanged.
- Preserve media type content descriptions.

- [ ] **Step 5: Refresh WebDAV transfer panel**

In `WebDavTransferPanel`:

- Use dark surface, cyan progress, and clear error text.
- Keep progress label and cancel action unchanged.
- Keep `DownloadProgressUi` behavior unchanged.

- [ ] **Step 6: Add or update helper tests only if helper output changes**

If `fileDirectoryEntrySupportingLabel`, `webDavItemSupportingLabel`, `formatByteSize`, action routing helpers, or menu action helpers change, add focused tests under the matching package. If no helper outputs change, do not add brittle visual tests.

- [ ] **Step 7: Run focused tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

If new helper tests were added, include their class names with additional `--tests` arguments.

Expected: PASS and Kotlin compilation succeeds for changed Compose code.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreenTest.kt app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt
git commit -m "feat: refresh source and webdav lists"
```

If optional tests were not created, omit those paths from `git add`.

---

### Task 4: Floating Cinema Player Overlay

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt`
- Modify only if necessary: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`

- [ ] **Step 1: Run current player UI tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest
```

Expected: PASS before changes.

- [ ] **Step 2: Update player sizing test first if constants intentionally change**

If the floating panel requires changing button, progress, rail, or sheet spacing constants, update `PlayerOptionPanelUiTest.playerControlSizingSupportsCenterPlaybackLockAndThinProgress` with the intended values before changing production code. Then run the test and confirm it fails for the expected constant mismatch.

If constants do not change, leave the test unchanged and continue.

- [ ] **Step 3: Refresh player palette constants**

In `VideoPlayerControls.kt`, update the existing player color constants:

- `PlayerOverlayColor`
- `PlayerSheetColor`
- `PlayerAccentColor`
- `PlayerOnAccentColor`
- `PlayerCenterPlayButtonColor`
- `PlayerProgressTrackColor`
- `PlayerProgressColor`
- `PlayerChipColor`
- `PlayerChipSelectedColor`

Use dark blue translucent surfaces and cyan progress/accent. Keep names unchanged.

- [ ] **Step 4: Convert bottom controls to a floating panel**

In `PlayerBottomControls`:

- Preserve parameters and behavior.
- Keep progress, error, active quick panel, and quick controls.
- Change the bottom area from only a full-width gradient overlay to a readable semi-transparent floating controls panel.
- Keep bottom safe padding and stable sizing.

- [ ] **Step 5: Polish top bar, center play, side rail, sheet, and HUD**

In existing composables:

- `PlayerTopBar`: smoother dark gradient and stable title/source contrast.
- `PlayerCenterPlayPauseButton`: polished translucent circular backing.
- `PlayerOverlayIconButton`: selected state uses cyan accent.
- `PlayerOptionSheet`: deep translucent surface with stable button sizing.
- `GestureHud`: dark cinematic surface, readable text.
- Keep all content descriptions unchanged.
- Keep `rightSideControlDescriptions`, `bottomQuickControlLabels`, and `scaleModeControlGroupLabels` outputs unchanged.

- [ ] **Step 6: Inspect `VideoPlayerActivity.kt`**

Modify `VideoPlayerActivity.kt` only if the new floating panel needs a placement adjustment in `VideoPlayerScreen`. Do not change controller calls, lifecycle behavior, mpv setup, or gesture wiring.

- [ ] **Step 7: Run player UI tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt
git commit -m "feat: refresh player overlay controls"
```

If `VideoPlayerActivity.kt` was not modified, omit it from `git add`.

---

### Task 5: Integration Verification

**Files:**
- Modify only if needed: files touched by Tasks 1-4.

- [ ] **Step 1: Inspect combined diff**

Run:

```bash
git diff --stat master...HEAD
git diff --check
```

Expected: no whitespace errors. Diff only includes approved UI files, tests, and this plan/spec docs.

- [ ] **Step 2: Run focused JVM tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest
```

Expected: PASS.

- [ ] **Step 3: Run debug build**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Review against approved design**

Check `docs/superpowers/specs/2026-05-24-cinematic-ui-refresh-design.md` and confirm:

- Default app has cinematic dark shell.
- Video library cards and empty state are media-forward.
- Sources and WebDAV remain list-first and readable.
- Player overlay uses floating cinema treatment.
- No playback, WebDAV, routing, database, or media detection logic changed.

- [ ] **Step 5: Commit final verification fixes if needed**

If integration required fixes, commit them:

```bash
git add app/src/main/java app/src/test/java
git commit -m "fix: polish cinematic ui integration"
```

If no fixes were needed, do not create an empty commit.
