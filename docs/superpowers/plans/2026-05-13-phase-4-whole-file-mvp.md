# Phase 4 Whole File Cached Reading MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open a remote WebDAV comic by streaming the full file into cache, then using the local Rust reader path.

**Architecture:** Whole-file download is the compatibility baseline for every server. Files are written to `.tmp` names and atomically renamed. Reading progress is keyed by remote path, size, and ETag or lastModified.

**Tech Stack:** OkHttp streaming, Kotlin coroutines, DataStore preferences, Rust local reader.

---

## Files

- Modify: `/home/lin/webcomic/app/build.gradle.kts`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/data/ComicDownloadCache.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/data/ReadingProgressStore.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/OpenComicUseCase.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`

## Task 1: Cache Key and Progress Tests

- [ ] Add dependency `androidx.datastore:datastore-preferences:1.1.1`.
- [ ] Write unit test: same path + size + ETag returns same cache key.
- [ ] Write unit test: changed ETag returns different cache key.
- [ ] Implement SHA-256 cache key from `accountId`, `remotePath`, `size`, and `etag ?: lastModified`.
- [ ] Implement `ReadingProgressStore` with `savePage(comicKey, pageIndex)` and `loadPage(comicKey)`.

## Task 2: Streaming Download Cache

- [ ] Write test using MockWebServer body larger than 1 MiB to verify streaming writes the expected final byte count.
- [ ] Implement `ComicDownloadCache.download()` that writes to `<key>.tmp`, reports progress, and renames to `<key>.cbz`.
- [ ] Delete `.tmp` file when coroutine cancellation occurs.
- [ ] Reuse existing final cache file if size matches expected size.

## Task 3: Open Remote Through Local Reader

- [ ] Implement `OpenComicUseCase` that calls `head`, downloads full file, opens local file through `ComicEngine`, and returns initial page from `ReadingProgressStore`.
- [ ] Modify browser screen comic row click to call `OpenComicUseCase`.
- [ ] Modify ReaderViewModel to save current page changes.
- [ ] Add UI progress and cancel controls during download.

## Verification

- [ ] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run `./gradlew :app:assembleDebug`.
- [ ] Manually open a remote CBZ from WebDAV; confirm download progress, page display, cancel behavior, and resume page after reopening.
- [ ] Commit: `feat: add whole file cached reading mvp`.
