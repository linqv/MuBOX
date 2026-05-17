# Continuous Prefetch Storm And PDF Tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix vertical continuous reading page-prefetch cancellation storms across formats, then tune PDF MuPDF output cost and forward prefetch depth.

**Architecture:** Keep `ReaderViewModel` as the owner of page-file prefetch reconciliation. Add a continuous-reading retention window for active page prefetch jobs, while preserving stale-generation cancellation and existing select-page promotion behavior. Keep MuPDF rendering serialized, but add a PDF-only render profile with lower pixel and JPEG budgets plus a 3-page forward prefetch window.

**Tech Stack:** Kotlin, Android ViewModel, coroutines, Compose reader events, MuPDF Java binding, JUnit unit tests.

---

## File Structure

- Modify `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`: add continuous page-prefetch retention window and retained logging.
- Modify `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`: add blocking page-prefetch tests for retained and far-cancelled active jobs.
- Modify `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt`: define PDF render profile constants.
- Modify `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt`: choose format-specific max pixels, JPEG quality, and forward prefetch count.
- Modify `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt`: assert PDF profile and non-PDF defaults.

## Task 1: ReaderViewModel Continuous Prefetch Retention Tests

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`

- [ ] **Step 1: Add a blocking page-prefetch fake session**

Add this helper near the other private fake sessions in `ReaderViewModelTest`:

```kotlin
private class BlockingPagePrefetchSession(
    override val pageCount: Int,
    override val forwardPrefetchPageCount: Int = 2,
    override val backwardPrefetchPageCount: Int = 0,
    override val advancePrefetchOnPageDemand: Boolean = true,
    private val blockingPage: Int,
    private val prefetchStarted: CountDownLatch,
    private val releasePrefetch: CountDownLatch,
) : ComicReaderSession {
    val loadedPages = mutableListOf<Int>()

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        synchronized(loadedPages) {
            loadedPages += pageIndex
        }
        if (pageIndex == blockingPage) {
            prefetchStarted.countDown()
            releasePrefetch.await(2, TimeUnit.SECONDS)
        }
        outputFile.writeText("page-$pageIndex")
        return outputFile
    }

    override fun close() = Unit
}
```

- [ ] **Step 2: Add failing test for retaining nearby continuous-visible prefetch**

Add this test in `ReaderViewModelTest` near the existing prefetch tests:

```kotlin
@Test
fun continuousVisibleRetainsNearbyActivePagePrefetch() = runTest(dispatcher) {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val ioDispatcher = executor.asCoroutineDispatcher()
    val prefetchStarted = CountDownLatch(1)
    val releasePrefetch = CountDownLatch(1)
    val session = BlockingPagePrefetchSession(
        pageCount = 12,
        blockingPage = 9,
        prefetchStarted = prefetchStarted,
        releasePrefetch = releasePrefetch,
    )
    val viewModel = ReaderViewModel(
        openSession = { session },
        ioDispatcher = ioDispatcher,
    )
    try {
        viewModel.openLocal("/tmp/book.pdf", temp.root, initialPage = 5)
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.uiState.pageFiles.containsKey(5)
        }

        viewModel.selectPage(7)
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            prefetchStarted.count == 0L
        }

        viewModel.reportPageDemand(5, "continuous_visible")
        dispatcher.scheduler.advanceUntilIdle()
        releasePrefetch.countDown()

        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.uiState.pageFiles.containsKey(9)
        }

        assertTrue(
            sink.lines.any { it.contains("prefetch_retained reason=continuous_visible page=5 pages=[9]") },
        )
        assertTrue(
            sink.lines.none {
                it.contains("prefetch_cancelled reason=outside_window page=5 pages=[9]")
            },
        )
    } finally {
        releasePrefetch.countDown()
        dispatcher.scheduler.advanceUntilIdle()
        ioDispatcher.close()
        executor.shutdownNow()
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    }
}
```

- [ ] **Step 3: Add failing test for still cancelling far outside-window prefetch**

Add this second test:

```kotlin
@Test
fun continuousVisibleCancelsFarActivePagePrefetch() = runTest(dispatcher) {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val ioDispatcher = executor.asCoroutineDispatcher()
    val prefetchStarted = CountDownLatch(1)
    val releasePrefetch = CountDownLatch(1)
    val session = BlockingPagePrefetchSession(
        pageCount = 12,
        blockingPage = 10,
        prefetchStarted = prefetchStarted,
        releasePrefetch = releasePrefetch,
    )
    val viewModel = ReaderViewModel(
        openSession = { session },
        ioDispatcher = ioDispatcher,
    )
    try {
        viewModel.openLocal("/tmp/book.pdf", temp.root, initialPage = 5)
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.uiState.pageFiles.containsKey(5)
        }

        viewModel.selectPage(8)
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            prefetchStarted.count == 0L
        }

        viewModel.reportPageDemand(5, "continuous_visible")
        dispatcher.scheduler.advanceUntilIdle()
        releasePrefetch.countDown()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            sink.lines.any {
                it.contains("prefetch_cancelled reason=outside_window page=5 pages=[10]")
            },
        )
        assertTrue(!viewModel.uiState.pageFiles.containsKey(10))
    } finally {
        releasePrefetch.countDown()
        dispatcher.scheduler.advanceUntilIdle()
        ioDispatcher.close()
        executor.shutdownNow()
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    }
}
```

- [ ] **Step 4: Run tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest.continuousVisibleRetainsNearbyActivePagePrefetch --tests com.example.comicdav.feature.reader.ReaderViewModelTest.continuousVisibleCancelsFarActivePagePrefetch
```

Expected: the retain test fails because page 9 is cancelled as `outside_window` before it can publish.

## Task 2: ReaderViewModel Continuous Retention Implementation

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`

- [ ] **Step 1: Change `prefetchNeighbors` to compute a retention window**

In `prefetchNeighbors`, replace the call to `reconcilePagePrefetches(...)` with:

```kotlin
val retentionWindow = retainedPagePrefetchWindow(
    pageIndex = pageIndex,
    pageCount = activeSession.pageCount,
    forwardPages = forwardPrefetchPages,
    desiredWindow = desiredWindow,
    reason = reason,
)
reconcilePagePrefetches(
    selectedPage = pageIndex,
    retentionWindow = retentionWindow,
    reason = reason,
)
```

- [ ] **Step 2: Add the retained-window helper**

Add this private helper near `reconcilePagePrefetches`:

```kotlin
private fun retainedPagePrefetchWindow(
    pageIndex: Int,
    pageCount: Int,
    forwardPages: Int,
    desiredWindow: Set<Int>,
    reason: String,
): Set<Int> {
    if (reason != "continuous_visible") return desiredWindow
    val firstPage = (pageIndex - CONTINUOUS_PAGE_PREFETCH_RETENTION_BEHIND).coerceAtLeast(0)
    val lastPage = (pageIndex + forwardPages + CONTINUOUS_PAGE_PREFETCH_RETENTION_AHEAD)
        .coerceAtMost(pageCount - 1)
    if (lastPage < firstPage) return desiredWindow
    return desiredWindow + (firstPage..lastPage)
}
```

- [ ] **Step 3: Update reconciliation to cancel against retention window**

Change `reconcilePagePrefetches` signature and body to:

```kotlin
private fun reconcilePagePrefetches(
    selectedPage: Int,
    retentionWindow: Set<Int>,
    reason: String,
) {
    val activePages = prefetchJobs
        .filter { (_, job) -> job.isActive }
        .keys
        .toSet()
    val retainedPages = activePages.intersect(retentionWindow)
    val cancelledPages = activePages.subtract(retentionWindow)

    if (retainedPages.isNotEmpty() && (reason.startsWith("select_page") || reason == "continuous_visible")) {
        ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
            "prefetch_retained reason=$reason page=$selectedPage pages=${retainedPages.sorted()}"
        }
    }
    cancelPagePrefetches(
        reason = "outside_window",
        pages = cancelledPages.toList(),
        selectedPage = selectedPage,
    )
}
```

- [ ] **Step 4: Add constants**

Add these constants inside `ReaderViewModel.Companion`:

```kotlin
const val CONTINUOUS_PAGE_PREFETCH_RETENTION_BEHIND = 2
const val CONTINUOUS_PAGE_PREFETCH_RETENTION_AHEAD = 2
```

- [ ] **Step 5: Run focused ViewModel tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest.continuousVisibleRetainsNearbyActivePagePrefetch --tests com.example.comicdav.feature.reader.ReaderViewModelTest.continuousVisibleCancelsFarActivePagePrefetch --tests com.example.comicdav.feature.reader.ReaderViewModelTest.selectPagePromotesExistingPrefetchInsteadOfCancellingForSelection
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt
git commit -m "fix: retain continuous page prefetches"
```

## Task 3: PDF MuPDF Render Profile Tests

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt`

- [ ] **Step 1: Replace the existing prefetch-window test**

Replace `pagePrefetchWindowIsLimitedForMuPdfDocuments` with:

```kotlin
@Test
fun pdfPrefetchesThreeForwardPages() {
    val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 1), LocalDocumentFormat.Pdf)

    assertEquals(3, session.forwardPrefetchPageCount)
    assertEquals(0, session.backwardPrefetchPageCount)
}

@Test
fun nonPdfDocumentsKeepTwoForwardPrefetchPages() {
    val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 1), LocalDocumentFormat.Epub)

    assertEquals(2, session.forwardPrefetchPageCount)
    assertEquals(0, session.backwardPrefetchPageCount)
}
```

- [ ] **Step 2: Add failing PDF render profile test**

Add:

```kotlin
@Test
fun pdfUsesTunedRenderProfile() {
    val output = File(temp.root, "page-1.img")
    val document = FakeMuPdfDocument(pageCount = 2)
    val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

    session.loadPageToFile(1, output)

    assertEquals(PDF_MUPDF_RENDER_MAX_PIXELS, document.renderedMaxPixels)
    assertEquals(PDF_MUPDF_RENDER_JPEG_QUALITY, document.renderedJpegQuality)
}
```

- [ ] **Step 3: Add non-PDF defaults test**

Add:

```kotlin
@Test
fun nonPdfDocumentsKeepDefaultRenderProfile() {
    val output = File(temp.root, "page-1.img")
    val document = FakeMuPdfDocument(pageCount = 2)
    val session = MuPdfReaderSession(document, LocalDocumentFormat.Epub)

    session.loadPageToFile(1, output)

    assertEquals(DEFAULT_MUPDF_RENDER_MAX_PIXELS, document.renderedMaxPixels)
    assertEquals(DEFAULT_MUPDF_RENDER_JPEG_QUALITY, document.renderedJpegQuality)
}
```

- [ ] **Step 4: Run tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest.pdfPrefetchesThreeForwardPages --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest.pdfUsesTunedRenderProfile --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest.nonPdfDocumentsKeepDefaultRenderProfile
```

Expected: PDF-specific tests fail because current session still uses 2 pages, 4,000,000 max pixels, and quality 92.

## Task 4: PDF MuPDF Render Profile Implementation

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt`

- [ ] **Step 1: Add PDF render constants**

In `MuPdfDocumentAdapter.kt`, add:

```kotlin
const val PDF_MUPDF_RENDER_MAX_PIXELS: Int = 3_000_000
const val PDF_MUPDF_RENDER_JPEG_QUALITY: Int = 87
```

Keep the existing default constants unchanged:

```kotlin
const val DEFAULT_MUPDF_RENDER_MAX_PIXELS: Int = 4_000_000
const val DEFAULT_MUPDF_RENDER_JPEG_QUALITY: Int = 92
```

- [ ] **Step 2: Add format-specific defaults in `MuPdfReaderSession`**

Change the constructor parameters in `MuPdfReaderSession.kt` to include `jpegQuality`:

```kotlin
class MuPdfReaderSession(
    private val document: MuPdfDocumentHandle,
    private val format: LocalDocumentFormat,
    private val maxPixels: Int = defaultMuPdfRenderMaxPixels(format),
    private val jpegQuality: Int = defaultMuPdfRenderJpegQuality(format),
    private val logDiagnostic: (() -> String) -> Unit = { event ->
        ReaderDiagnosticLog.detail(ReaderLogCategory.PAGE_LOAD, event)
    },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ComicReaderSession {
```

Then add these helpers near the bottom of the file:

```kotlin
private fun defaultMuPdfRenderMaxPixels(format: LocalDocumentFormat): Int =
    if (format == LocalDocumentFormat.Pdf) {
        PDF_MUPDF_RENDER_MAX_PIXELS
    } else {
        DEFAULT_MUPDF_RENDER_MAX_PIXELS
    }

private fun defaultMuPdfRenderJpegQuality(format: LocalDocumentFormat): Int =
    if (format == LocalDocumentFormat.Pdf) {
        PDF_MUPDF_RENDER_JPEG_QUALITY
    } else {
        DEFAULT_MUPDF_RENDER_JPEG_QUALITY
    }
```

- [ ] **Step 3: Apply PDF prefetch and quality values**

In `MuPdfReaderSession`, replace the prefetch and render quality properties with:

```kotlin
override val forwardPrefetchPageCount: Int =
    if (format == LocalDocumentFormat.Pdf) 3 else 2
override val backwardPrefetchPageCount: Int = 0
override val advancePrefetchOnPageDemand: Boolean = true
```

In `loadPageToFile`, pass `jpegQuality`:

```kotlin
renderMetrics = document.renderPageToJpeg(
    pageIndex,
    outputFile,
    maxPixels,
    jpegQuality,
)
```

In `formatMuPdfRenderDone(...)`, pass `quality = jpegQuality`.

- [ ] **Step 4: Run focused MuPDF tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfRenderScaleTest
```

Expected: all selected MuPDF unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt
git commit -m "perf: tune pdf mupdf render profile"
```

## Task 5: Full Focused Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run focused regression suite**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfRenderScaleTest
```

Expected: all selected tests pass. If the command fails, stop at the first failing test and record the exact test name plus assertion or compiler error before making another code change.

- [ ] **Step 2: Check git state**

Run:

```bash
git status --short
```

Expected: clean working tree after the implementation commits.

- [ ] **Step 3: Manual device validation**

Install or run the app build used for device testing, open the same PDF from `/sdcard/漫画/25.1.pdf`, use vertical continuous mode, and scroll through roughly the first 80 pages.

Expected detail-log changes:

```text
mupdf_render_done ... maxPixels=3000000 quality=87
prefetch_retained reason=continuous_visible ...
```

Expected metric movement compared with the previous PDF log:

```text
prefetch_cancelled reason=outside_window << 162
page_not_ready likelyCause=prefetch_too_late << 40
```
