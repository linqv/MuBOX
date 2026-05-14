# Reader Diagnostic Folder Logs Design

## Goal

Change reader diagnostics from manually saving a single log file to choosing a log folder once. After a folder is configured, every reader open creates a timestamped log file automatically. The log must provide enough timing evidence to diagnose slow first-image display and pages that are not ready before the reader swipes to them.

## Current Behavior

- The reader toolbar has a `Log` action wired to `ActivityResultContracts.CreateDocument("text/plain")`.
- The selected file URI becomes the active `ReaderDiagnosticLog` sink.
- Reader logs include open, page extraction, viewport update, pager state, prefetch, and crash events.
- Timing is implicit in timestamps, so cause analysis requires manual comparison.
- UI image display uses Coil `AsyncImage`, but image load start/success/failure is not logged.

## Proposed Behavior

- The toolbar log action becomes a folder picker using `ActivityResultContracts.OpenDocumentTree`.
- The app persists read/write URI permission for the selected folder.
- The selected folder URI is stored in app preferences and reused across app launches.
- Each reader open creates a new file named `comicdav-reader-yyyyMMdd-HHmmss-SSS.log` in the selected folder.
- If no folder is configured, diagnostics continue through the no-op sink until the user chooses one.
- If log-file creation fails, the app logs failure to the current sink if possible and continues opening the reader.

## Diagnostic Timing

Add structured timing events around the existing reader pipeline:

- Remote open: start, result, and duration.
- Session start: start, initial page, page count, and duration until initial page file is ready.
- Page file loading: cache hit, extract start, extract done, duration, and output size.
- First image rendering: first image load start, success, failure, and duration from reader open.
- Pager/page readiness: when the pager reports or targets a page whose file is not ready, record wait start; when the file becomes available and image rendering succeeds, record wait end.
- Prefetch effectiveness: planned pages, cancellation caused by a new selection, per-page completion duration, and whether the page was ready before selection.

## Analysis Output

Keep analysis simple and log based. After the first image is displayed, emit one summary line:

`analysis first_image totalMs=... likelyCause=...`

The likely cause is selected from the slowest known segment: remote/session open, page extraction, file cache miss, or image decode/render.

For later pages that are not ready before selection, emit:

`analysis page_not_ready page=... waitMs=... likelyCause=...`

The likely cause is selected from available evidence:

- `not_prefetched` if the page was never in the prefetch plan before selection.
- `prefetch_cancelled` if prefetch was cancelled before the page completed.
- `prefetch_too_late` if prefetch started after or near selection.
- `extract_slow` if page extraction dominates the wait.
- `image_decode_slow` if the file was ready but Coil rendering dominates the wait.

## Components

- `ReaderDiagnosticLog.kt`
  - Add a folder-backed sink that creates timestamped files.
  - Add small formatting helpers for durations and analysis lines.
- `MainActivity.kt`
  - Replace the document creator launcher with a folder picker.
  - Persist the folder URI in preferences.
  - Create a new log file when opening local or remote reader sessions.
- `ReaderViewModel.kt`
  - Add timing for open, initial page readiness, page load, prefetch, and selection waits.
  - Preserve existing behavior and threading.
- `ReaderScreen.kt`
  - Add Coil image callbacks to log image load start, success, and failure.
  - Report page readiness gaps without changing pager behavior.
- Tests
  - Cover log filename generation, folder sink creation behavior, first-image analysis formatting, and page-not-ready cause selection.

## Error Handling

- Folder permission or file creation failure must not block opening a comic.
- Log write failures stay contained in `ReaderDiagnosticLog`.
- Existing crash logging remains best-effort.
- No network or reader behavior changes are included in this change; this is diagnostic instrumentation plus automatic file creation.

## Validation

- Run `./gradlew :app:testDebugUnitTest`.
- If available, run a manual reader check:
  - choose a log folder;
  - open a comic;
  - confirm a timestamped log file appears;
  - swipe ahead before prefetch completes;
  - confirm first-image and page-not-ready analysis lines are written.
