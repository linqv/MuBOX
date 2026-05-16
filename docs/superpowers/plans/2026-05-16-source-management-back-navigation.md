# Source Management And Back Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add unified source creation, saved WebDAV auto-connect, long-press source management, optional local backing-file deletion, and hierarchical Android back behavior.

**Architecture:** Extend file-directory persistence to store WebDAV connection fields and source deletion. Keep existing UI callbacks but add explicit source-management callbacks to `FileDirectoryScreen`. Use ViewModel-level tests for data/WebDAV behavior and app-shell integration in `MainActivity`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room, DataStore/SAF APIs, existing WebDAV ViewModel and repository.

---

## Task 1: File Directory Persistence

**Files:**
- Modify `app/src/main/java/com/example/comicdav/data/filedirectory/FileDirectoryEntities.kt`
- Modify `app/src/main/java/com/example/comicdav/data/filedirectory/FileDirectoryDao.kt`
- Modify `app/src/main/java/com/example/comicdav/data/filedirectory/FileDirectoryRepository.kt`
- Modify `app/src/main/java/com/example/comicdav/data/library/LibraryDatabase.kt`
- Modify `app/src/test/java/com/example/comicdav/data/FileDirectoryRepositoryTest.kt`

Steps:
- [ ] Add failing tests proving WebDAV sources store `baseUrl`, `username`, `password`, and `path`.
- [ ] Add failing tests proving `deleteSource(id)` removes a saved source.
- [ ] Add entity columns `webDavBaseUrl`, `webDavUsername`, and `webDavPassword`.
- [ ] Add DAO delete query and repository methods.
- [ ] Add Room migration from current schema to the new schema.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.data.FileDirectoryRepositoryTest'`.

## Task 2: WebDAV Auto-Connect

**Files:**
- Modify `app/src/main/java/com/example/comicdav/feature/webdav/WebDavViewModel.kt`
- Modify `app/src/test/java/com/example/comicdav/feature/webdav/WebDavViewModelTest.kt`

Steps:
- [ ] Add a failing test for connecting to a saved WebDAV source and automatically opening its saved path.
- [ ] Implement a `connectToSavedSource(baseUrl, username, password, path)` API.
- [ ] Preserve active account identity based on saved credentials.
- [ ] Keep existing connect/test behavior passing.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.feature.webdav.WebDavViewModelTest'`.

## Task 3: Source UI Management

**Files:**
- Modify `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`

Steps:
- [ ] Replace separate add buttons with one top-right add menu containing `添加本地文件夹` and `添加 WebDAV`.
- [ ] Add long-press handling on source rows.
- [ ] Add an action surface for source management with `删除来源`.
- [ ] For local sources, offer `仅移除来源` and `同时删除源文件`.
- [ ] Preserve existing read/add-library behavior for browsed comic rows.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.ui.ComicDavCopyTest'`.

## Task 4: App-Shell Back And Integration

**Files:**
- Modify `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryViewModel.kt`
- Modify `app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryViewModelTest.kt`

Steps:
- [ ] Add a failing ViewModel test for source deletion.
- [ ] Add a failing ViewModel test for local folder back behavior at nested and root levels.
- [ ] Wire WebDAV saved-source clicks to auto-connect.
- [ ] Wire long-press deletion callbacks to repository and optional SAF delete.
- [ ] Add Android `BackHandler` behavior: reader closes first, WebDAV/local browsing backs up one level, tabs return to Sources, root exits.
- [ ] Run `./gradlew :app:testDebugUnitTest`.
