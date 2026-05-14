# Reader Diagnostic Folder Logs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Choose a diagnostic log folder once, automatically create one timestamped reader log per open, and add timing analysis for slow first-image display and pages that are not ready before swiping.

**Architecture:** Keep SAF folder/file creation in `MainActivity` and `ReaderDiagnosticLog.kt`, keep pure analysis formatting in `ReaderDiagnosticLog.kt`, and keep reader pipeline evidence in `ReaderViewModel`. `ReaderScreen` only reports pager demand and Coil image lifecycle events; it does not decide root cause.

**Tech Stack:** Android Kotlin, Jetpack Compose, Activity Result API, Android SAF `DocumentsContract`, Coil 3 `AsyncImage`, kotlinx-coroutines-test, JUnit 4.

---

### Task 1: Folder-Backed Reader Log Files

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt`

- [ ] **Step 1: Write failing filename and metadata tests**

Add tests to `ReaderDiagnosticLogTest.kt`:

```kotlin
@Test
fun timestampedReaderLogFileNameUsesStableSortableFormat() {
    val timestamp = java.time.ZonedDateTime.of(
        2026, 5, 14, 9, 8, 7, 123_000_000, java.time.ZoneOffset.UTC,
    )

    assertEquals(
        "comicdav-reader-20260514-090807-123.log",
        timestampedReaderLogFileName(timestamp),
    )
}

@Test
fun readerLogFileMetadataKeepsFileNameAndUri() {
    val uri = android.net.Uri.parse("content://logs/tree/file")
    val sink = object : ReaderLogSink {
        override fun log(line: String) = Unit
        override fun logBlocking(line: String) = Unit
    }

    val file = ReaderLogFile(fileName = "comicdav-reader-20260514-090807-123.log", uri = uri, sink = sink)

    assertEquals("comicdav-reader-20260514-090807-123.log", file.fileName)
    assertEquals(uri, file.uri)
    assertEquals(sink, file.sink)
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest`

Expected: FAIL because `timestampedReaderLogFileName` and `ReaderLogFile` do not exist.

- [ ] **Step 3: Implement log file helpers and SAF sink creation**

Add to `ReaderDiagnosticLog.kt`:

```kotlin
data class ReaderLogFile(
    val fileName: String,
    val uri: Uri,
    val sink: ReaderLogSink,
)

fun timestampedReaderLogFileName(now: ZonedDateTime): String {
    val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US))
    return "comicdav-reader-$stamp.log"
}

fun createReaderLogFile(
    context: Context,
    folderTreeUri: Uri,
    scope: CoroutineScope,
    now: ZonedDateTime = ZonedDateTime.now(),
): ReaderLogFile {
    val resolver = context.applicationContext.contentResolver
    val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        folderTreeUri,
        DocumentsContract.getTreeDocumentId(folderTreeUri),
    )
    val fileName = timestampedReaderLogFileName(now)
    val fileUri = requireNotNull(
        DocumentsContract.createDocument(resolver, parentDocumentUri, "text/plain", fileName),
    ) { "Could not create reader log file in selected folder" }
    return ReaderLogFile(fileName, fileUri, ContentUriReaderLogSink(context, fileUri, scope))
}
```

- [ ] **Step 4: Update `MainActivity` to choose and persist a folder**

Replace `CreateDocument("text/plain")` with `OpenDocumentTree`. Store the URI string in `SharedPreferences` named `reader_diagnostics`, key `log_folder_uri`. On folder selection call `takePersistableUriPermission` with read and write flags, save the URI, create a log file immediately, set the sink, and log `log_folder_selected`.

Before `readerViewModel.openLocal(...)` and before `readerViewModel.openRemote(...)`, call a helper that reads the stored folder URI, creates a new timestamped log file, sets the sink, and writes `log_file_created fileName=... uri=...`. If there is no stored folder, keep the no-op sink.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest`

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt app/src/main/java/com/example/comicdav/MainActivity.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt
git commit -m "feat: create timestamped reader logs in selected folder"
```

### Task 2: Pure Diagnostic Analysis Helpers

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt`

- [ ] **Step 1: Write failing analysis tests**

Add tests:

```kotlin
@Test
fun firstImageAnalysisUsesLargestKnownSegment() {
    val line = formatFirstImageAnalysis(
        FirstImageTiming(
            page = 0,
            totalMs = 1_500,
            remoteOpenMs = 300,
            sessionInitialPageMs = 250,
            pageExtractMs = 900,
            imageRenderMs = 50,
            cacheHit = false,
        ),
    )

    assertEquals(
        "analysis first_image page=0 totalMs=1500 likelyCause=page_extract remoteOpenMs=300 sessionInitialPageMs=250 pageExtractMs=900 imageRenderMs=50 cacheHit=false",
        line,
    )
}

@Test
fun pageNotReadyAnalysisPrefersMissingPrefetchEvidence() {
    val line = formatPageNotReadyAnalysis(
        PageNotReadyTiming(
            page = 3,
            waitMs = 700,
            wasPrefetchPlanned = false,
            wasPrefetchCancelled = false,
            prefetchStartedBeforeDemand = false,
            extractMs = 120,
            imageRenderMs = 80,
        ),
    )

    assertEquals(
        "analysis page_not_ready page=3 waitMs=700 likelyCause=not_prefetched wasPrefetchPlanned=false wasPrefetchCancelled=false prefetchStartedBeforeDemand=false extractMs=120 imageRenderMs=80",
        line,
    )
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest`

Expected: FAIL because timing types and formatters do not exist.

- [ ] **Step 3: Implement minimal analysis helpers**

Add `FirstImageTiming`, `PageNotReadyTiming`, `formatFirstImageAnalysis`, `formatPageNotReadyAnalysis`, and private cause selectors. Cause priority for page readiness is: not prefetched, cancelled, too late, extract slow, image decode slow, unknown.

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest`

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt
git commit -m "feat: add reader diagnostic analysis formatting"
```

### Task 3: Reader Pipeline Timing and Prefetch Evidence

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel logging test**

Add a collecting sink to `ReaderViewModelTest.kt` and a test that selects a page before it is loaded:

```kotlin
@Test
fun selectingUnreadyPageEmitsPageNotReadyAnalysisAfterImageSuccess() = runTest(dispatcher) {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    val session = FakeReaderSession(pageCount = 6)
    val clockValues = ArrayDeque(listOf(0L, 10L, 20L, 30L, 100L, 160L, 220L, 280L, 360L))
    val viewModel = ReaderViewModel(
        openSession = { session },
        ioDispatcher = dispatcher,
        elapsedRealtimeMs = { clockValues.removeFirstOrNull() ?: 360L },
    )

    viewModel.openLocal("/tmp/book.cbz", temp.root)
    dispatcher.scheduler.runCurrent()
    viewModel.reportPageDemand(5, "test")
    viewModel.selectPage(5)
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.reportImageLoadStarted(5)
    viewModel.reportImageLoadSucceeded(5)

    assertTrue(sink.lines.any { it.contains("analysis page_not_ready page=5") })
    ReaderDiagnosticLog.clearSink()
}

private class CollectingReaderLogSink : ReaderLogSink {
    val lines = mutableListOf<String>()
    override fun log(line: String) {
        lines += line
    }
    override fun logBlocking(line: String) {
        lines += line
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest`

Expected: FAIL because `elapsedRealtimeMs`, `reportPageDemand`, and image reporting methods do not exist.

- [ ] **Step 3: Implement timing state and public reporting methods**

Add an injectable `elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L }` constructor parameter. Track page load timings, prefetch plan state, page demand wait state, image start times, remote open duration, and whether first image analysis has been emitted. Add methods:

```kotlin
fun reportPageDemand(pageIndex: Int, source: String)
fun reportImageLoadStarted(pageIndex: Int)
fun reportImageLoadSucceeded(pageIndex: Int)
fun reportImageLoadFailed(pageIndex: Int)
```

Use these methods to emit `image_load_start`, `image_load_success`, `image_load_failed`, `analysis first_image`, and `analysis page_not_ready` lines.

- [ ] **Step 4: Add duration logging to existing load paths**

Update calls to `loadPages(...)` with a `reason` string: `initial`, `select`, and `prefetch`. Log cache hits and extraction completion with `durationMs`. Mark prefetch pages planned, started, completed, or cancelled when page selection cancels pending prefetch work.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest`

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt
git commit -m "feat: log reader readiness timing evidence"
```

### Task 4: Compose Pager Demand and Coil Image Lifecycle Reporting

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`

- [ ] **Step 1: Wire callbacks through `ReaderScreen`**

Add parameters:

```kotlin
onPageDemanded: (Int, String) -> Unit,
onImageLoadStarted: (Int) -> Unit,
onImageLoadSucceeded: (Int) -> Unit,
onImageLoadFailed: (Int) -> Unit,
```

In the pager snapshot collector, call `onPageDemanded(snapshot.targetPage, "pager_target")` and `onPageDemanded(snapshot.currentPage, "pager_current")`.

- [ ] **Step 2: Add Coil callbacks**

Update `AsyncImage`:

```kotlin
AsyncImage(
    model = pageFile,
    contentDescription = "Page ${page + 1}",
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Fit,
    onLoading = { onImageLoadStarted(page) },
    onSuccess = { onImageLoadSucceeded(page) },
    onError = { onImageLoadFailed(page) },
)
```

- [ ] **Step 3: Wire callbacks in `MainActivity`**

Pass `readerViewModel::reportPageDemand`, `readerViewModel::reportImageLoadStarted`, `readerViewModel::reportImageLoadSucceeded`, and `readerViewModel::reportImageLoadFailed`.

- [ ] **Step 4: Run full unit tests and commit**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt app/src/main/java/com/example/comicdav/MainActivity.kt
git commit -m "feat: report pager demand and image lifecycle"
```

### Task 5: Final Verification

**Files:**
- Verify all modified files.

- [ ] **Step 1: Run full Android unit test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 2: Inspect final diff**

Run: `git diff --stat master...HEAD`

Expected: changes are limited to the design/plan docs, reader diagnostics, reader screen, reader view model, main activity, and tests.

- [ ] **Step 3: Prepare completion summary**

Summarize the selected log folder behavior, timestamped file creation, first-image analysis, page-not-ready analysis, and verification output.
