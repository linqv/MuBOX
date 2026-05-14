# Phase 7 Compatibility Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Handle common WebDAV, ZIP, image, and network failures with clear UI states and stable cache behavior.

**Architecture:** Errors are classified at the boundary where they occur and mapped to user-facing categories in Kotlin. Rust reports archive-specific unsupported cases. Diagnostics can be exported for server compatibility testing.

**Tech Stack:** Kotlin sealed errors, Compose error views, Rust error enums, Android instrumentation smoke tests.

---

## Files

- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/PageErrorView.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/diagnostics/ReaderDiagnostics.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/WebDavClient.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
- Modify: `/home/lin/webcomic/comic-core/src/error.rs`
- Modify: `/home/lin/webcomic/comic-core/src/zip/zip64.rs`
- Modify: `/home/lin/webcomic/comic-core/src/cbz/page.rs`
- Modify: `/home/lin/webcomic/comic-core/src/cache/page_cache.rs`

## Task 1: Error Classification

- [ ] Add Kotlin sealed class `ReaderError` with `Network`, `Authentication`, `UnsupportedArchive`, `DamagedPage`, `ServerUnsupported`, and `Unknown`.
- [x] Add Rust errors for `EncryptedZip`, `SplitZip`, `UnsupportedCompression`, `InvalidUtf8Filename`, and `NoImages`.
- [ ] Write mapping tests from native error strings/codes to `ReaderError`.
- [ ] Add `PageErrorView` with message text and Retry button for retryable errors.

## Task 2: WebDAV Failure Cases

- [ ] Add MockWebServer tests for HEAD not supported, Range returning 200, wrong Content-Range, missing ETag, authentication failure, and encoded path with spaces and Chinese characters.
- [ ] Implement fallback from HEAD to PROPFIND metadata.
- [ ] Keep Range returning 200 as `ServerUnsupported` and route to whole-file mode.
- [ ] Add optional self-signed certificate setting behind an explicit user toggle in `SettingsScreen`.

## Task 3: ZIP and Filename Compatibility

- [x] Add tests for encrypted ZIP flag and split ZIP markers returning unsupported errors.
- [x] Add UTF-8 filename flag tests and GBK fallback tests.
- [x] Enable ZIP64 parser path when ZIP64 locator and record are both valid.
- [ ] Ensure damaged entries return per-page errors without invalidating the whole index.

## Task 4: Diagnostics and Cache Management

- [ ] Implement `ReaderDiagnostics` containing server URL host, mode, request count, bytes read, cache hits, fallback reason, and last error category.
- [ ] Add export action that writes a text summary into app cache and shares through Android share sheet.
- [ ] Add cache management UI showing index cache size and page cache size.
- [ ] Add clear-cache action that removes page cache files but keeps account settings.

## Task 5: Phase 6B ADB Follow-Up

- [ ] Execute `docs/superpowers/plans/2026-05-14-phase-7-range-prefetch-follow-up.md`.
- [ ] Investigate remaining sequential-read misses from Phase 6B phone logs.
- [ ] Improve next-page range cache hit rate toward the 80% stable Wi-Fi target.
- [ ] Reduce remaining `page_not_ready` waits where logs report `likelyCause=extract_slow`.
- [ ] Keep the Phase 6B lifecycle behavior intact: `prefetch_cancelled reason=select_page` should stay near zero and normal selections should continue using `prefetch_retained` or `prefetch_promoted`.

## Verification

- [x] Run `cargo test`.
- [x] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run one Android instrumentation smoke test for open-reader-close.
- [ ] Manually test at least two WebDAV services and record pass/fail notes.
- [x] Commit: `feat: harden webdav and archive compatibility`.
