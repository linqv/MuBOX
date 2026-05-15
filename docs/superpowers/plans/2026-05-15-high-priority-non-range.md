# High Priority Non-Range Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the three high-priority improvements except remote Range decision changes.

**Architecture:** Preserve existing reader behavior while moving cohesive helpers out of `ReaderViewModel`. Keep remote opening semantics unchanged, but surface download progress and cancellation on the active reader loading screen. Add cache pruning around files currently written by the app.

**Tech Stack:** Android Kotlin, Jetpack Compose, coroutines, DataStore, Rust `comic-core`.

---

### Task 1: Protect Existing Reader Behavior

**Files:**
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`
- Test: `app/src/test/java/com/example/comicdav/data/ComicDownloadCacheTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderScreenStateTest.kt`

- [ ] Add focused tests before production edits:
  - `ReaderViewModel.closeReaderCancelsPendingRemoteOpen`
  - `ComicDownloadCache.pruneRemovesOldestFilesWhenCapacityExceeded`
  - `ReaderScreenState.loadingProgressShowsCancelableProgress`
- [ ] Run the focused tests and confirm they fail for missing behavior/API.

### Task 2: Split ReaderViewModel Without Behavior Changes

**Files:**
- Create: `app/src/main/java/com/example/comicdav/feature/reader/ReaderPageCache.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/reader/ReaderPrefetchPlanner.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticsTracker.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`

- [ ] Move page cache path creation behind `ReaderPageCache` with the same filename and safe key rules.
- [ ] Move neighbor window helpers behind `ReaderPrefetchPlanner` with the same constants.
- [ ] Move diagnostic maps and timing formatting calls behind `ReaderDiagnosticsTracker`, preserving log events and timing fields.
- [ ] Keep public `ReaderViewModel` methods and emitted diagnostics stable.

### Task 3: Reader Loading Progress and Cancellation

**Files:**
- Create: `app/src/main/java/com/example/comicdav/feature/reader/ReaderLoadingProgress.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`

- [ ] Add a small reader-facing progress model.
- [ ] Show progress and a cancel button in `ReaderScreen` while the reader is loading.
- [ ] Wire cancel to close the reader and return to the browser.
- [ ] Do not change remote Range/open fallback decisions.

### Task 4: Cache Capacity and Cleanup

**Files:**
- Create: `app/src/main/java/com/example/comicdav/data/FileLruPruner.kt`
- Modify: `app/src/main/java/com/example/comicdav/data/ComicDownloadCache.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderPageCache.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`

- [ ] Add reusable LRU pruning for cache directories.
- [ ] Prune whole-file remote cache after successful downloads.
- [ ] Prune reader page cache after page extraction.
- [ ] Prune old local-import temp files before/after copying a selected local file.

### Task 5: Verification

**Commands:**
- `cargo test` from `comic-core/`
- `cargo fmt -- --check` from `comic-core/`
- `./gradlew :app:testDebugUnitTest` from repo root
- `./gradlew :app:assembleDebug` from repo root

- [ ] Run focused tests after each implementation slice.
- [ ] Run full verification before reporting completion.
