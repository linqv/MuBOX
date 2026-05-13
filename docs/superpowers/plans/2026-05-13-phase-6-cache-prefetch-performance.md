# Phase 6 Cache Prefetch Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add index cache, page cache, Range planning, and prefetch so remote reading is smooth under normal network conditions.

**Architecture:** Rust computes archive-aware Range plans and cache keys. Kotlin reports viewport changes and network class. Cache state is observable through diagnostics for performance checks.

**Tech Stack:** Rust serde/bincode or JSON, SHA-256, Kotlin coroutines, Android cache directory.

---

## Files

- Modify: `/home/lin/webcomic/comic-core/Cargo.toml`
- Create: `/home/lin/webcomic/comic-core/src/cache/mod.rs`
- Create: `/home/lin/webcomic/comic-core/src/cache/index_cache.rs`
- Create: `/home/lin/webcomic/comic-core/src/cache/page_cache.rs`
- Create: `/home/lin/webcomic/comic-core/src/scheduler/mod.rs`
- Create: `/home/lin/webcomic/comic-core/src/scheduler/range_planner.rs`
- Create: `/home/lin/webcomic/comic-core/src/scheduler/prefetch.rs`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`

## Task 1: Index Cache

- [x] Add Rust dependencies `serde`, `serde_json`, and `sha2`.
- [x] Write test: unchanged size and validator loads index cache.
- [x] Write test: changed size invalidates index cache.
- [x] Implement index cache format with `version`, `comic_key`, `file_size`, `validator`, and `page_entries`.
- [x] Load index cache before EOCD and Central Directory Range reads.

## Task 2: Page Cache and LRU

- [x] Write test: existing page cache file path is returned without Range read.
- [x] Write test: LRU removes oldest files when capacity exceeds configured bytes.
- [x] Implement page cache directory `<cacheDir>/<comicKey>/pages/<pageIndex>.<ext>`.
- [x] Implement cache size scan and removal by last-modified timestamp.

## Task 3: Range Planner

- [x] Write test: ranges separated by 63 KiB merge when result is under 8 MiB.
- [x] Write test: ranges separated by 65 KiB do not merge.
- [x] Implement `range_planner.rs` with merge gap `64 * 1024` and max merged size `8 * 1024 * 1024`.
- [x] Expose planned request count in diagnostics.

## Task 4: Prefetch Scheduler

- [x] Write test: current page priority is higher than next and previous pages.
- [x] Write test: viewport jump demotes old forward-window tasks.
- [x] Implement priority order: current, next, previous, forward window, backward window.
- [x] Add Kotlin `ComicEngine.updateViewport(handle, pageIndex, networkClass)`.
- [x] Trigger updates from `ReaderViewModel` when pager page changes.

## Verification

- [x] Run `cargo test` in `/home/lin/webcomic/comic-core`.
- [x] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Manually verify second open uses index cache.
- [ ] Manually verify sequential reading on Wi-Fi reaches at least 80% next-page cache hits in diagnostics.
- [ ] Commit: `feat: add comic cache and prefetch scheduler`.
