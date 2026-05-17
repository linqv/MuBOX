# Continuous Prefetch Storm And PDF Tuning Design

## Goal

Fix the cancellation storm seen in vertical continuous reading, then apply PDF-specific render tuning. The cancellation fix is format-agnostic and applies to local archives, local documents, and remote readers that use page-file prefetch. PDF additionally renders lower-cost page images and prefetches one more forward page.

## Evidence

The 2026-05-17 phone logs from `/sdcard/漫画/` show PDF reading loaded 85 pages with 162 `prefetch_cancelled` events, mostly `reason=outside_window`. The same log shows 43 `page_not_ready` events, mostly `likelyCause=prefetch_too_late`, and many later `load_page_cache_hit` lines. This pattern points to vertical continuous reading repeatedly advancing the visible window and cancelling nearby page prefetches before the result is published to UI state.

The previous WebDAV CBZ/ZIP work already fixed a related issue for planned range prefetch by retaining useful nearby work instead of cancelling it on every viewport change. The page-file prefetch path still uses a strict desired window, so it can thrash when `continuous_visible` events arrive for adjacent pages.

## Non-Goals

- Do not change 7z or TAR extraction behavior.
- Do not parallelize MuPDF document rendering; `sessionMutex` continues to serialize page loads.
- Do not add a new settings UI for PDF quality in this pass.
- Do not alter remote range byte-cache eviction rules except through existing page-file prefetch behavior.

## Design

### Page Prefetch Retention

`ReaderViewModel.reconcilePagePrefetches` will stop treating the current desired window as a hard cancellation boundary for every reason. For continuous vertical demand, active page prefetch jobs will be protected by a wider retention window around the demanded page.

For `reason == "continuous_visible"`, the retained page set is:

- the normal desired page window for the active session;
- extra continuous-retention pages from `pageIndex - 2` through `pageIndex + forwardPrefetchPages + 2`, clamped to the document bounds;
- only currently active page prefetch jobs inside that retained set.

The implementation should keep stale-generation cancellation unchanged. It should still cancel work that is clearly far outside the retained window, and it should keep logging cancellations as `outside_window` when cancellation is intentional.

For `reason == "select_page"`, the existing retain/promote behavior remains the source of truth. For other reasons, the current strict desired-window behavior remains unchanged unless a test proves it is part of the continuous-reader storm.

### Publication Rule

If a retained prefetch completes in the active generation, it must still publish its file into `uiState.pageFiles`. This is important for MuPDF/PDF because cancelling a coroutine after native rendering has started may not stop the render immediately; throwing away the completed file causes avoidable not-ready waits.

### PDF Render Profile

PDF will use a lower-cost render profile:

- forward page prefetch count: `3`;
- backward page prefetch count: `0`;
- JPEG quality lower than the current `92`, targeting `87`;
- max pixels lower than the current `4_000_000`, targeting `3_000_000`.

EPUB, MOBI, and AZW3 keep the current document defaults in this pass. The profile is PDF-only because the evidence comes from a large fixed-layout PDF and reflowable documents can have different quality needs.

### Logging

Existing logs remain compatible. Detail mode should continue to emit:

- `prefetch_retained` when active useful work is kept;
- `prefetch_cancelled reason=outside_window` only for work outside the retained window;
- `mupdf_render_done` with `maxPixels` and `quality`, now reflecting the PDF profile.

No new high-volume log stream is required. Retained continuous work should reuse the existing `prefetch_retained` event with `reason=continuous_visible`.

## Testing

Add focused unit tests around `ReaderViewModel`:

- continuous-visible movement retains nearby active page prefetch instead of cancelling it;
- far outside-window page prefetch is still cancelled;
- retained page prefetch publishes its completed file to `uiState.pageFiles`;
- existing select-page promotion behavior remains intact.

Add focused unit tests around `MuPdfReaderSession`:

- PDF uses forward prefetch count `3`;
- PDF passes the tuned max-pixels and JPEG quality values to the document;
- non-PDF MuPDF formats keep existing defaults.

Run:

- `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfRenderScaleTest`

Manual validation on device:

- Open the same PDF in vertical continuous mode.
- Scroll through roughly the same first 80 pages.
- Expected: `prefetch_cancelled reason=outside_window` drops sharply from the prior 162 events, `page_not_ready likelyCause=prefetch_too_late` drops, and `mupdf_render_done` reports `maxPixels=3000000 quality=87` for PDF pages.
