# Hybrid Chinese Reader UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the existing ComicDav library-reader UI around the approved Source + Library hybrid mockup, with Chinese user-facing copy by default.

**Architecture:** Keep the existing repositories, ViewModels, and reader pipeline. Add a small UI copy/token layer, replace the current boolean shell with a tab-oriented app surface outside the reader, and restyle Sources, Library, WebDAV browsing, first-run gate, and Reader screens using Material 3 Compose components.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, existing Room/DataStore/ViewModel stack, existing Coil reader image loading.

---

## File Structure

- Create `app/src/main/java/com/example/comicdav/ui/ComicDavCopy.kt`: centralized Chinese UI strings used by Compose screens.
- Modify `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`: expand color, shape, and typography defaults for the approved quiet manga-reader style.
- Modify `app/src/main/java/com/example/comicdav/MainActivity.kt`: add a bottom-tab shell for Sources, Library, Offline, and Settings; keep Reader full-screen outside the shell; update first-run gate copy.
- Modify `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`: rename the visible screen to Sources, add continue/saved-source layout, Chinese text, and polished rows.
- Modify `app/src/main/java/com/example/comicdav/feature/library/LibraryScreen.kt`: apply Chinese copy, filter chips, bookshelf grid polish, progress/source/offline badges, and empty state.
- Modify `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`: apply Chinese copy, app-bar/path-chip browser layout, clearer file actions, and compact metadata.
- Modify `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`: apply Chinese reader chrome, improve black loading/error surfaces, and align overlay labels with the mockup.
- Create `app/src/test/java/com/example/comicdav/ui/ComicDavCopyTest.kt`: verifies the central Chinese copy for primary navigation and key actions.

## Task 1: Central Chinese Copy

**Files:**
- Create: `app/src/main/java/com/example/comicdav/ui/ComicDavCopy.kt`
- Test: `app/src/test/java/com/example/comicdav/ui/ComicDavCopyTest.kt`

- [ ] **Step 1: Write the failing copy test**

Create `app/src/test/java/com/example/comicdav/ui/ComicDavCopyTest.kt`:

```kotlin
package com.example.comicdav.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicDavCopyTest {
    @Test
    fun primaryNavigationUsesChineseReaderTerms() {
        assertEquals("来源", ComicDavCopy.sourcesTab)
        assertEquals("书架", ComicDavCopy.libraryTab)
        assertEquals("离线", ComicDavCopy.offlineTab)
        assertEquals("设置", ComicDavCopy.settingsTab)
    }

    @Test
    fun primaryActionsUseChineseCopy() {
        assertEquals("添加本地文件夹", ComicDavCopy.addLocalFolder)
        assertEquals("添加 WebDAV", ComicDavCopy.addWebDav)
        assertEquals("阅读", ComicDavCopy.read)
        assertEquals("加入书架", ComicDavCopy.addToLibrary)
        assertEquals("保存当前目录", ComicDavCopy.saveCurrentDirectory)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.ui.ComicDavCopyTest'`

Expected: compile failure because `ComicDavCopy` does not exist.

- [ ] **Step 3: Add `ComicDavCopy`**

Create `app/src/main/java/com/example/comicdav/ui/ComicDavCopy.kt`:

```kotlin
package com.example.comicdav.ui

object ComicDavCopy {
    const val sourcesTab = "来源"
    const val libraryTab = "书架"
    const val offlineTab = "离线"
    const val settingsTab = "设置"
    const val sourcesTitle = "来源"
    const val libraryTitle = "书架"
    const val continueReading = "继续阅读"
    const val savedSources = "已保存来源"
    const val addLocalFolder = "添加本地文件夹"
    const val addWebDav = "添加 WebDAV"
    const val open = "打开"
    const val read = "阅读"
    const val addToLibrary = "加入书架"
    const val saveCurrentDirectory = "保存当前目录"
    const val emptyLibraryTitle = "书架还是空的"
    const val emptyLibraryBody = "从来源中浏览漫画，并把想长期阅读的作品加入书架。"
    const val chooseDataFolderTitle = "选择 ComicDav 数据文件夹"
    const val chooseDataFolderBody = "ComicDav 会把封面、离线漫画、诊断日志和后续导出的文件保存在你选择的文件夹中。"
    const val chooseFolder = "选择文件夹"
    const val readerLoading = "正在打开漫画"
    const val readerDownloading = "正在下载漫画"
    const val readerError = "无法打开漫画"
    const val readerClose = "关闭"
    const val readerLog = "日志"
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.ui.ComicDavCopyTest'`

Expected: test passes.

## Task 2: Hybrid App Shell

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`

- [ ] **Step 1: Add shell state and bottom navigation**

Replace the separate `isLibraryOpen` app-surface switch with an app tab enum for Sources, Library, Offline, and Settings. Keep `isReaderOpen` and `isWebDavOpen` as modal/deep surfaces outside the tab shell.

- [ ] **Step 2: Add lightweight Offline and Settings placeholders**

Show quiet Chinese empty states for Offline and Settings so the bottom navigation is complete without adding new persistence behavior.

- [ ] **Step 3: Update first-run gate copy**

Use `ComicDavCopy.chooseDataFolderTitle`, `ComicDavCopy.chooseDataFolderBody`, and `ComicDavCopy.chooseFolder`.

- [ ] **Step 4: Verify compile**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.ui.ComicDavCopyTest'`

Expected: test passes and Compose code compiles.

## Task 3: Sources, Library, WebDAV, and Reader Polish

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`

- [ ] **Step 1: Restyle Sources**

Rename visible copy from File Directory to Sources, add a compact summary/continue area, Chinese actions, polished source rows, and file rows matching the C mockup.

- [ ] **Step 2: Restyle Library**

Use Chinese labels, filter chips, tighter bookshelf grid, cover fallback blocks, progress indicators, and source/offline metadata.

- [ ] **Step 3: Restyle WebDAV browser**

Use Chinese labels, a path chip, save-current-directory action, file metadata, and explicit Read/Add actions.

- [ ] **Step 4: Restyle Reader**

Keep reader behavior intact. Apply Chinese chrome labels, black loading/error states, and overlay spacing aligned with the mockup.

- [ ] **Step 5: Run focused verification**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.ui.ComicDavCopyTest' --tests 'com.example.comicdav.feature.reader.*'
```

Expected: tests pass.

## Task 4: Final Verification

**Files:**
- No new files.

- [ ] **Step 1: Run app unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: all app unit tests pass.

- [ ] **Step 2: Check worktree**

Run: `git status --short`

Expected: only intentional UI redesign files and pre-existing unrelated changes are present.

## Self-Review

- Spec coverage: covers the approved C hybrid shell, Chinese copy, Sources/Library/WebDAV/Reader visual polish, first-run gate copy, and focused tests.
- Placeholder scan: no TBD/TODO placeholders.
- Type consistency: all new string references use `ComicDavCopy`.
