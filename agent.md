# Agent Handoff

## Current State

- Repository root: `/home/lin/webcomic`
- Main branch: `master`
- Latest integrated branch before Phase 6/7 merge: `feature/phase-5-remote-range-reader`
- Active follow-up branches:
  - `feature/phase-6-cache-prefetch-performance`
  - `feature/phase-7-compatibility-hardening`
- Phase 6 and Phase 7 are stacked: Phase 7 is based on Phase 6.

## Completed Work

Phase 5 added remote Range reading and has been merged into `master`.

Phase 6 adds cache and scheduling foundations:

- Rust index cache keyed by comic key, file size, and validator.
- Rust page cache path helper and LRU capacity cleanup.
- Rust range planner with 64 KiB merge gap and 8 MiB max merged range.
- Rust prefetch scheduler for current, next, previous, forward window, and backward window pages.
- JNI/Kotlin viewport update hook and native diagnostics string with planned request count.

Phase 7 compatibility hardening completed for archive parsing:

- ZIP64 EOCD locator and record parsing.
- ZIP64 central directory entry extra-field parsing for sizes and local header offsets.
- Data descriptor entries are covered by regression tests and extract using Central Directory sizes.
- GBK filename fallback when the UTF-8 filename flag is not set.
- Explicit unsupported errors for encrypted ZIP and split ZIP entries.

## Important Files

- `comic-core/src/zip/eocd.rs`
- `comic-core/src/zip/central_directory.rs`
- `comic-core/src/zip/zip64.rs`
- `comic-core/src/cbz/page.rs`
- `comic-core/src/cache/index_cache.rs`
- `comic-core/src/cache/page_cache.rs`
- `comic-core/src/scheduler/range_planner.rs`
- `comic-core/src/scheduler/prefetch.rs`
- `comic-core/tests/cbz_local.rs`
- `comic-core/tests/cache_scheduler.rs`
- `app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`
- `app/src/main/java/com/example/comicdav/nativebridge/ComicNative.kt`
- `app/src/main/java/com/example/comicdav/feature/reader/OpenComicUseCase.kt`
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`

## Verification Already Run

From `/home/lin/webcomic/.worktrees/phase-7-compatibility-hardening/comic-core`:

```bash
cargo test
```

Result: passed.

From `/home/lin/webcomic/.worktrees/phase-7-compatibility-hardening`:

```bash
./gradlew :app:testDebugUnitTest
```

Result: passed.

## Not Done

- Manual WebDAV verification is still deferred.
- Android instrumentation smoke test has not been run.
- Phase 7 UI tasks are not implemented: settings screen, share/export diagnostics UI, cache management UI, and page error view.
- WebDAV failure hardening beyond the existing tests remains future work: HEAD fallback to PROPFIND, self-signed certificate toggle, and broader server compatibility notes.

## Recommended Next Steps

1. Merge the stacked branches in order if final automated verification passes on `master`:

```bash
git -C /home/lin/webcomic merge --ff-only feature/phase-6-cache-prefetch-performance
git -C /home/lin/webcomic merge --ff-only feature/phase-7-compatibility-hardening
```

2. Re-run on `master` after merge:

```bash
cd /home/lin/webcomic/comic-core
cargo test

cd /home/lin/webcomic
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

3. When device/WebDAV access is available, test:

- Second open of the same remote comic uses index cache.
- ZIP64 archive opens.
- Data descriptor archive opens.
- GBK filename archive indexes correctly.
- Non-Range or broken-Range server still falls back cleanly.
