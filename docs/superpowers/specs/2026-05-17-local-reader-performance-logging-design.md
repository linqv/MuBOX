# Local Reader Performance Logging Design

## Goal

Upgrade reader diagnostics so local file performance can be optimized without turning normal logs into high-volume trace dumps. The first phase focuses on local archive and document reading: CBZ/ZIP, CB7/7Z, CBT/TAR, PDF, EPUB, MOBI, and AZW3.

The default log should answer:

- What local format and reader engine was used?
- How long did opening, first page preparation, page extraction/rendering, image loading, and cache reuse take?
- Which page or phase was slowest?
- How much output and cache data did the session produce?
- Was the run affected by memory pressure or unusually large rendered output?

## Current Behavior

- `ReaderDiagnosticLog` is a global singleton with one sink and raw timestamped string events.
- Settings expose one `loggingEnabled` boolean, defaulting to enabled.
- Summary events and high-frequency detail events share the same `event(String)` API.
- The SAF log sink opens and closes the output stream for every line.
- Local direct opens log only post-open ready or failure events in `MainActivity`.
- `ReaderViewModel.loadPages` logs cache hits and page extraction timing, but it does not distinguish native archive extraction from MuPDF page rendering.
- MuPDF open/layout/count-pages and render internals are not timed.
- Rust/JNI diagnostics exist, but local archive sessions do not expose useful local archive performance details yet.
- Raw URIs, remote paths, and file names can be written directly to logs.

## Proposed Behavior

Introduce three logging modes:

- `OFF`: no diagnostics sink is created for normal reader logging.
- `SUMMARY`: default. Writes lifecycle, failures, local performance summaries, first-image analysis, page-not-ready analysis, and resource summaries.
- `DETAIL`: writes everything in `SUMMARY` plus high-frequency pager, demand, image start/success/failure, prefetch, and range/cache detail.

The existing boolean setting migrates conservatively:

- missing setting or old `logging_enabled=true` becomes `SUMMARY`;
- old `logging_enabled=false` becomes `OFF`.

The settings UI replaces the single switch with a mode selector: Off, Summary, Detail.

## Event API

Keep the logging surface small and compatible with existing call sites:

- `summary(category) { "...fields..." }`
- `detail(category) { "...fields..." }`
- `error(category, event, throwable)`
- `errorBlocking(category, event, throwable)`

Message builders are lazy. In `SUMMARY`, detail builders are not invoked. In `OFF`, summary/detail builders are not invoked. Errors remain available when a sink exists and logging is not off.

Initial categories:

- `SESSION`
- `LOCAL_FILE`
- `PAGE_LOAD`
- `IMAGE`
- `PREFETCH`
- `RANGE_CACHE`
- `UI`

Existing raw `event(String)` can remain temporarily as a compatibility wrapper, but new and migrated events should use summary/detail methods.

## Local Performance Summary

Each local reader session writes one opening summary and one close or final summary. The first phase can compute the final summary from in-process counters without needing a new database or persistent analytics store.

Opening summary fields:

- `sourceType`: library, directory, remote-cache, or unknown.
- `sourceId`: short redacted ID.
- `fileExt`
- `format`: ZIP, 7Z, TAR, PDF, EPUB, MOBI, AZW3.
- `engine`: native-archive or mupdf.
- `sizeBytes`
- `pageCount`
- `openTotalMs`
- `descriptorOpenMs` when known.
- `nativeOpenMs` or `documentOpenMs` when known.
- `layoutMs` and `countPagesMs` for MuPDF documents.

Per-session summary fields:

- `firstImageTotalMs`
- `initialPageLoadMs`
- `cacheHits`
- `cacheMisses`
- `pagesLoaded`
- `slowestPage`
- `slowestPageReason`: cache-read, archive-extract, mupdf-render, image-load, unknown.
- `slowestPageMs`
- `totalOutputBytes`
- `largestOutputBytes`
- `pageCacheLimitBytes`
- `pageCachePruneMs`
- `pageCachePrunedBytes`
- `jvmHeapUsedBytes`
- `nativeHeapUsedBytes` when available on Android.

## Local Archive Signals

For CBZ/ZIP, CB7/7Z, and CBT/TAR, phase one records Android/JNI and page-load boundary timings:

- local descriptor open duration and `statSize`;
- native `openLocalFd` duration;
- native page-count duration through `openChecked`;
- app-level page cache hit or miss;
- extract total duration from `ReaderViewModel.loadPages`;
- output file size;
- cache prune duration and bytes pruned when available.

Rust internal archive breakdown is intentionally deferred to phase two. It should later expose a compact native diagnostics snapshot rather than logging inside hot Rust loops. Candidate fields are index build duration, entry counts, compressed/uncompressed totals, read bytes, decompression duration, and write duration.

## MuPDF Document Signals

For PDF, EPUB, MOBI, and AZW3, phase one adds explicit MuPDF timings:

- `Document.openDocument` duration;
- password check duration and encrypted/DRM failure phase;
- reflow layout duration for reflowable formats;
- page count duration;
- page bounds;
- render scale;
- estimated rendered pixels;
- estimated RGB bytes;
- pixmap render duration;
- JPEG save duration;
- JPEG output bytes;
- configured max pixels and JPEG quality;
- OOM and partial-output cleanup status on failure.

The existing `imageRenderMs` naming should be treated as image load/display timing, not MuPDF render timing. New summary names should use `mupdfRenderMs` and `imageLoadMs` to avoid mixing the two phases.

## Detail Mode

`DETAIL` keeps the existing diagnostic depth but moves noisy events out of normal logs:

- pager snapshots;
- page demand events;
- image load start/success/failure for every page;
- page prefetch start/done/cancelled/retained;
- planned range prefetch detail;
- WebDAV range cache hit/miss/store/evict detail;
- per-page local extract/render start/done.

`SUMMARY` keeps enough information to diagnose local performance without high-volume event streams.

## Privacy

Logs must not write raw SAF URIs, remote paths, log folder URIs, or full file names.

Use a central redaction helper before writing a line:

- `uriId=local:<shortHash>`
- `pathId=remote:<shortHash>`
- `fileId=file:<shortHash>`
- preserve `fileExt`, `format`, `sizeBytes`, `pageCount`, byte ranges, durations, and cache status;
- sanitize throwable messages and stack traces for raw URI/path/name patterns;
- log selected log files as `logSink=content-uri` plus generated file name, not the raw content URI.

Short IDs should be stable within a log session. Cross-session stability is not required for first-phase diagnostics.

## Sink Performance

The first phase must reduce logging overhead without overbuilding the sink. Required phase-one work:

- avoid string allocation for disabled detail lines through lazy builders;
- keep summary and error writes non-droppable;
- preserve crash logging through `errorBlocking`.

Replacing per-line SAF open/append/close with a long-lived writer coroutine is optional in phase one. If it is implemented, it must include:

- a bounded queue;
- droppable detail lines under pressure;
- non-droppable summary and error lines;
- a summary line such as `detail_dropped count=N` when detail backpressure occurs;
- a blocking flush path for crash logging.

If the writer rewrite is deferred, phase one still delivers the main performance benefit through mode gating and lazy builders.

## Components

- `AppSettingsStore`
  - Add `ReaderLoggingMode`.
  - Migrate from the existing boolean key.
  - Persist a stable string mode key.
- `SettingsScreen`
  - Replace the logging switch with a mode selector.
- `ReaderDiagnosticLog`
  - Add mode, category, lazy summary/detail APIs, redaction helpers, and compatibility wrappers.
  - Keep no-op behavior when no sink is configured.
- `MainActivity`
  - Apply logging mode when creating or clearing the log sink.
  - Stop logging raw local and remote identifiers.
  - Capture local open source metadata before calling `LocalComicOpener`.
- `LocalComicOpener`
  - Record local descriptor open and format routing timings.
- `ComicEngine`
  - Record JNI open and page-count boundary timings for local archive sessions.
- `ReaderViewModel`
  - Keep page cache/load timing, add session counters, and emit local performance summaries.
  - Classify cache hit, archive extract, MuPDF render, and image load timing separately where possible.
- `MuPdfReaderSession` and `RealMuPdfDocumentAdapter`
  - Add document open and render phase timing hooks.
- Rust `comic-core`
  - No required phase-one behavior change unless a compact diagnostics snapshot can be added without hot-loop logging.

## Error Handling

- Logging failure must not block opening or reading local files.
- If a local open fails, emit a summary error with source ID, extension, format if known, phase, duration, and sanitized exception.
- If a page render/extract fails, emit page, format, engine, phase, duration, output cleanup status, and sanitized exception.
- OOM should be recorded as a distinct failure class with memory snapshot fields where available.

## Testing

Unit tests:

- `AppSettingsStoreTest`: default mode is `SUMMARY`; old boolean false maps to `OFF`; mode key is stable.
- `ReaderDiagnosticLogTest`: summary/detail/off gating; detail builder not invoked in summary; raw URI/path/name redaction; throwable sanitization.
- `LocalComicOpenerTest`: local archive and document open summaries include format, size, and duration fields without raw names.
- `ComicEngineTest`: local FD open boundary diagnostics and native page-count timing can be captured.
- `ReaderViewModelTest`: summary mode excludes pager/range/detail noise and emits session/page-load summary; detail mode includes high-frequency events.
- `MuPdfReaderSessionTest`: render success, cache skip, OOM, cancellation, and partial-output cleanup diagnostics.

Rust tests:

- Keep existing archive behavior tests stable.
- Add native diagnostics snapshot tests only if phase one exposes new Rust metrics.

Validation commands:

- `./gradlew :app:testDebugUnitTest`
- `cd comic-core && cargo test` if Rust diagnostics are changed.

Manual validation when a device is available:

- Open one CBZ/ZIP, one 7Z/CB7 or TAR/CBT, and one PDF/EPUB.
- Confirm summary mode creates compact logs with one opening summary and one performance summary.
- Confirm detail mode includes per-page/page-demand detail.
- Confirm logs do not contain raw SAF URIs, remote paths, or full file names.

## Non-Goals

- No UI analytics dashboard in this phase.
- No persistent metrics database.
- No full Rust hot-loop trace logging.
- No change to decoding, rendering, prefetch, or cache behavior except instrumentation.
- No automatic upload or sharing of logs.
