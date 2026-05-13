# Phase 3 JNI Local Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect Kotlin to the Rust CBZ core and display local CBZ pages with Coil.

**Architecture:** Kotlin calls a narrow native wrapper that returns handles and cache file paths. Rust stores sessions in a mutex-protected handle table and never returns decoded bitmaps. Compose uses a pager whose ViewModel loads current, previous, and next pages on `Dispatchers.IO`.

**Tech Stack:** Rust JNI/C ABI, Kotlin external functions, Coil, Compose foundation pager, coroutines.

---

## Files

- Modify: `/home/lin/webcomic/comic-core/Cargo.toml`
- Modify: `/home/lin/webcomic/comic-core/src/lib.rs`
- Create: `/home/lin/webcomic/comic-core/src/ffi.rs`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/ComicNative.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`

## Task 1: Add Native Session API

- [ ] Add Rust dependencies: `jni = "0.21"`, `once_cell = "1"`.
- [ ] Write Rust unit test that opens a fixture CBZ, stores a session, returns a non-zero handle, and closes it.
- [ ] Implement `ComicHandle = u64` and session map `Mutex<HashMap<u64, CbzSession>>`.
- [ ] Implement exported functions `comic_open_local`, `comic_page_count`, `comic_load_page_to_file`, `comic_close`, and `comic_last_error_message`.
- [ ] Run `cargo test ffi`.

## Task 2: Register Kotlin Native Bridge

- [ ] Create `ComicNative.kt` with `System.loadLibrary("comic_core")` and external methods `openLocal`, `pageCount`, `loadPageToFile`, `close`, `lastErrorMessage`.
- [ ] Prefer `RegisterNatives` in Rust `JNI_OnLoad` for the exact class `com/example/comicdav/nativebridge/ComicNative`.
- [ ] Create `ComicEngine.kt` that wraps handles in `Closeable` and maps `0` handles or negative counts to `ComicNativeException`.
- [ ] Add unit tests for wrapper error mapping using a fake native facade interface.

## Task 3: Add Local Reader UI

- [ ] Add dependencies: `io.coil-kt.coil3:coil-compose:3.0.4` and `androidx.compose.foundation:foundation`.
- [ ] Create `ReaderViewModel.kt` with state `pageCount`, `currentPage`, `pageFiles`, `isLoading`, `error`.
- [ ] Implement `openLocal(path, cacheDir)` and load pages `index - 1`, `index`, `index + 1` on `Dispatchers.IO`.
- [ ] Create `ReaderScreen.kt` using `HorizontalPager` and Coil `AsyncImage` for cache file paths.
- [ ] Release native handle in `ViewModel.onCleared()`.

## Verification

- [ ] Run `cargo test` in `/home/lin/webcomic/comic-core`.
- [ ] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run `./gradlew :app:assembleDebug`.
- [ ] Verify on one `x86_64` emulator or `arm64-v8a` device that a local CBZ page appears.
- [ ] Commit: `feat: connect rust core to android reader`.
