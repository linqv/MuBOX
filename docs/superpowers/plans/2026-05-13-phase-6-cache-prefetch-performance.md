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

## Step 6 Addendum: Range Reuse Optimization Roadmap

The reader diagnostics from `/home/lin/logs/comicdav` show that later-page jank is caused by page data not being ready before the pager settles. Image decode is usually tens of milliseconds; remote page extraction is usually hundreds of milliseconds to multiple seconds. The next optimization should reuse downloaded byte ranges before attempting deeper archive-level changes.

### Phase 6A: First Version - Kotlin Range Window Cache

**Goal:** Reuse already downloaded remote byte ranges when adjacent pages share or overlap the same larger range, without changing Rust page extraction.

**Files:**

- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt`
- Modify: `/home/lin/webcomic/app/src/test/java/com/example/comicdav/network/WebDavRangeProviderTest.kt`
- Optionally modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt`

**Design:**

- Replace the single `cachedWindow` in `WebDavRangeProvider` with a small LRU `RangeWindowCache`.
- Each window stores `start`, `endInclusive`, `bytes`, and `lastAccessSequence`.
- `readRange(fileId, start, endInclusive)` first asks the cache for a covering window.
- If a covering window exists, return `slice(start, endInclusive)` and log or count a `range_cache_hit`.
- If no covering window exists, request `start..expandedEnd` from WebDAV, where `expandedEnd = min(size - 1, endInclusive + readAheadBytes)`.
- Store the expanded response as a new window, then evict least-recently-used windows until total cached bytes are under `maxCacheBytes`.
- First default values:
  - `readAheadBytes = 4 * 1024 * 1024`
  - `maxCacheBytes = 64 * 1024 * 1024`
- Keep the cache memory-only. Do not persist raw range bytes to disk in this first version.

**Tests:**

- [ ] Write test: a second range fully covered by a previous expanded window is served without another WebDAV request.
- [ ] Write test: multiple non-overlapping windows are retained until `maxCacheBytes` is exceeded.
- [ ] Write test: LRU eviction removes the least recently accessed window when capacity is exceeded.
- [ ] Write test: a request larger than `readAheadBytes` still returns exactly the requested bytes.
- [ ] Write test: cache miss calls `WebDavClient.readRange(path, start, expandedEnd)` with the expanded end clamped to `size - 1`.

**Implementation steps:**

- [ ] Create a private `RangeWindowCache` class inside `WebDavRangeProvider.kt`.
- [ ] Move `CachedWindow.slice()` into the cache implementation.
- [ ] Add constructor parameters `readAheadBytes` and `maxCacheBytes` with defaults.
- [ ] Keep synchronization around cache access and mutation.
- [ ] Add lightweight diagnostics counters or log lines for cache hits, misses, stores, and evictions.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.network.WebDavRangeProviderTest`.
- [ ] Run full `./gradlew :app:testDebugUnitTest`.

**Manual validation:**

- Build an `arm64-v8a` debug APK.
- Open the same large remote CBZ and swipe sequentially.
- Compare `/home/lin/logs/comicdav` before and after:
  - fewer WebDAV range requests for adjacent pages;
  - more `range_cache_hit` events;
  - lower `analysis page_not_ready waitMs`;
  - fewer cases where prefetch has downloaded data that selection cannot reuse.

**Risks and limits:**

- If adjacent pages are not physically close in the ZIP, read-ahead may waste bandwidth.
- A memory cache can help only while the process/session is alive.
- This does not make Rust's `plan_ranges()` actively fetch merged ranges; it only reuses byte windows requested through normal page extraction.

### Phase 6B: Long-Term - Rust Planned Merged Range Prefetch

**Goal:** Turn Rust's archive-aware range planner into an executable prefetch plan so Kotlin can fetch merged byte ranges before page extraction needs them.

**Files:**

- Modify: `/home/lin/webcomic/comic-core/src/ffi.rs`
- Modify: `/home/lin/webcomic/comic-core/src/scheduler/range_planner.rs`
- Modify: `/home/lin/webcomic/comic-core/src/scheduler/prefetch.rs`
- Modify: `/home/lin/webcomic/comic-core/tests/cache_scheduler.rs`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/ComicNative.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt`
- Modify: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- Modify: corresponding unit tests under `/home/lin/webcomic/app/src/test/java/com/example/comicdav/`

**Design:**

- Rust exposes a viewport prefetch plan containing merged byte ranges, not only `planned_request_count`.
- The plan is based on:
  - current page;
  - network class;
  - existing `plan_prefetch()` page priorities;
  - existing `plan_ranges()` merge gap and max merged size.
- Each planned merged range includes:
  - `start`;
  - `endInclusive`;
  - covered page indexes;
  - priority.
- Kotlin calls the new native API after viewport changes and when pager target changes.
- Kotlin schedules background prefetch of the planned merged ranges through `WebDavRangeProvider`.
- `WebDavRangeProvider` stores planned merged ranges into the same `RangeWindowCache` from Phase 6A.
- Later `loadPageToFile(page)` calls still use the current Rust `read_range()` path, but those reads hit `RangeWindowCache` when the planned range is already present.

**Tests:**

- [ ] Rust test: adjacent page compressed ranges merge into one planned range when gap and size limits allow.
- [ ] Rust test: planned range records all covered page indexes.
- [ ] Rust test: ranges exceeding `MAX_MERGED_BYTES` remain split.
- [ ] Kotlin test: viewport plan requests are passed to `WebDavRangeProvider` as background prefetch ranges.
- [ ] Kotlin test: a later `readRange()` fully covered by a prefetched planned range returns from cache without a new WebDAV call.
- [ ] Kotlin test: a stale viewport plan is ignored after session generation changes.

**Implementation steps:**

- [ ] Add a Rust serializable plan type such as `PlannedRangeDto { start, end_inclusive, pages, priority }`.
- [ ] Add a native function that returns the plan as JSON or a compact string.
- [ ] Add Kotlin native facade methods to retrieve planned ranges for a session.
- [ ] Add a Kotlin model `PlannedRemoteRange`.
- [ ] Add `WebDavRangeProvider.prefetchRange(start, endInclusive)` that fills `RangeWindowCache` without returning page bytes.
- [ ] Add a ViewModel-side scheduler that cancels stale plan jobs by generation but keeps current and next-page planned ranges alive.
- [ ] Log planned range count, planned bytes, cache hit count, and stale-plan cancellation.
- [ ] Run `cargo test` in `/home/lin/webcomic/comic-core`.
- [ ] Run `./gradlew :app:testDebugUnitTest`.

**Manual validation:**

- Open a large remote CBZ.
- Confirm diagnostics show planned merged ranges covering multiple pages.
- Swipe sequentially at the same pace used in the failing logs.
- Success target:
  - next-page range cache hit rate at least 80% on stable Wi-Fi;
  - `page_not_ready` wait is rare for sequential reading;
  - when `page_not_ready` remains, likely cause is network throughput rather than duplicate or missed range reuse.

**Risks and limits:**

- Native plan serialization must be versioned or kept narrow so Kotlin/Rust changes do not drift.
- Prefetching large merged ranges can waste bandwidth if the user jumps around.
- Background merged range prefetch must be generation-aware to avoid filling cache for a closed or replaced session.
- Memory pressure still needs the LRU limit from Phase 6A.

## Verification

- [x] Run `cargo test` in `/home/lin/webcomic/comic-core`.
- [x] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Manually verify second open uses index cache.
- [ ] Manually verify sequential reading on Wi-Fi reaches at least 80% next-page cache hits in diagnostics.
- [ ] Commit: `feat: add comic cache and prefetch scheduler`.
