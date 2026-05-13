# Phase 5 Remote Range Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open and read remote CBZ/ZIP files using HTTP Range without downloading the whole archive when the server supports correct Range responses.

**Architecture:** Kotlin owns WebDAV HTTP and cancellation. Rust owns archive parsing through a `RangeReader` implementation that calls back into Kotlin via JNI. Range failures fall back to the phase 4 whole-file cache path.

**Tech Stack:** Kotlin RangeProvider registry, JNI callbacks, Rust trait adapter, OkHttp cancellation.

---

## Files

- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/RangeProvider.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/RangeProviderRegistry.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/ComicNative.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`
- Modify: `/home/lin/webcomic/comic-core/src/ffi.rs`
- Create: `/home/lin/webcomic/comic-core/src/remote/mod.rs`
- Create: `/home/lin/webcomic/comic-core/src/remote/jni_range_reader.rs`

## Task 1: Kotlin Provider Registry

- [ ] Write unit test: registering a provider returns unique `fileId` and unregister removes it.
- [ ] Implement `RangeProvider` with `size(fileId)` and `readRange(fileId, start, endInclusive)`.
- [ ] Implement thread-safe `RangeProviderRegistry` using `ConcurrentHashMap<Long, RangeProvider>` and `AtomicLong`.
- [ ] Implement `WebDavRangeProvider` that delegates to `WebDavClient.readRange`.

## Task 2: Rust JNI RangeReader

- [ ] Write Rust test using a fake callback adapter that serves bytes and proves `read_range(2,4)` returns three bytes.
- [ ] Implement `JniRangeReader` with JVM attach before Kotlin callback.
- [ ] Convert Kotlin `ByteArray` into Rust `Vec<u8>` immediately and release local refs.
- [ ] Map callback exceptions to Rust `anyhow::Error`.

## Task 3: Remote Open and Page Load

- [ ] Add native `openRemote(fileId, size, cacheDir)` in `ComicNative`.
- [ ] Implement `comic_open_remote` to parse EOCD and Central Directory through `JniRangeReader`.
- [ ] Reuse `comic_load_page_to_file` so remote pages are inflated into page cache paths.
- [ ] Record request count and downloaded bytes in an in-memory diagnostic struct.

## Task 4: Fallback

- [ ] Add unit test where RangeProvider throws `RangeNotSupported`; `OpenComicUseCase` calls whole-file cache mode.
- [ ] Wire browser open flow: use Range mode only when `RemoteFileInfo.supportsRange` is true.
- [ ] On bad `Content-Range`, unregister provider and fall back to phase 4.

## Verification

- [ ] Run `cargo test` in `/home/lin/webcomic/comic-core`.
- [ ] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run `./gradlew :app:assembleDebug`.
- [ ] Manually open a Range-capable WebDAV CBZ and verify first page appears before whole file is downloaded.
- [ ] Manually test a non-Range server and confirm whole-file fallback.
- [ ] Commit: `feat: add remote range cbz reading`.
