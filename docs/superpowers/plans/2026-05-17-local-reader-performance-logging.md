# Local Reader Performance Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add summary/detail logging modes and local reader performance diagnostics for native archives and MuPDF documents.

**Architecture:** Build a small logging-mode foundation first, then instrument independent local-reader paths. Keep the default log compact by routing high-frequency events to detail mode and emitting one local session performance summary. Use redacted identifiers instead of raw URIs, paths, or full file names.

**Tech Stack:** Android Kotlin, Jetpack Compose, DataStore Preferences, JUnit 4/Robolectric, Kotlin coroutines, Rust comic-core only if compact native snapshots are added.

---

## File Map

- `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`: add `ReaderLoggingMode`, migration from the old boolean, and `updateLoggingMode`.
- `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`: replace the logging switch with a mode chooser.
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt`: add mode/category APIs, lazy summary/detail builders, and redaction helpers.
- `app/src/main/java/com/example/comicdav/MainActivity.kt`: apply the selected logging mode, avoid raw identifiers, and pass local-open metadata.
- `app/src/main/java/com/example/comicdav/feature/reader/LocalComicOpener.kt`: time descriptor open and format routing.
- `app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`: time native open and native page-count boundaries.
- `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt`: expose MuPDF render metadata.
- `app/src/main/java/com/example/comicdav/feature/reader/mupdf/RealMuPdfDocumentAdapter.kt`: measure document open/layout/count-pages and render phases.
- `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt`: emit MuPDF render diagnostics and classify failures.
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticsTracker.kt`: collect local session counters.
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`: emit local session performance summaries and route noisy events to detail.
- Existing tests listed in each task: extend focused tests for each changed component.

## Parallelization

Task 1 is the required foundation. After Task 1 is committed, Tasks 2, 3, and 4 can run in parallel because they own mostly disjoint files:

- Task 2 owns `LocalComicOpener.kt`, `ComicEngine.kt`, and their tests.
- Task 3 owns MuPDF adapter/session files and MuPDF tests.
- Task 4 owns `ReaderDiagnosticsTracker.kt`, `ReaderViewModel.kt`, and ViewModel tests.

Task 5 integrates `MainActivity` and call-site routing after those slices land. Task 6 is final verification.

### Task 1: Logging Modes, Lazy APIs, And Redaction

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt`
- Test: `app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt`

- [ ] **Step 1: Write failing settings tests**

Add tests to `AppSettingsStoreTest`:

```kotlin
@Test
fun defaultsToSummaryReaderLoggingMode() = runTest {
    val store = AppSettingsStore(dataStore("app-settings-logging-default.preferences_pb"))

    assertEquals(ReaderLoggingMode.SUMMARY, store.settings.first().readerLoggingMode)
    assertTrue(store.settings.first().loggingEnabled)
}

@Test
fun persistsReaderLoggingModeWithStablePreferenceKey() = runTest {
    val dataStore = dataStore("app-settings-logging-mode.preferences_pb")
    val store = AppSettingsStore(dataStore)

    store.updateReaderLoggingMode(ReaderLoggingMode.DETAIL)

    assertEquals(ReaderLoggingMode.DETAIL, AppSettingsStore(dataStore).settings.first().readerLoggingMode)
    assertEquals("DETAIL", dataStore.data.first()[stringPreferencesKey("reader_logging_mode")])
}

@Test
fun migratesOldDisabledLoggingBooleanToOffMode() = runTest {
    val dataStore = dataStore("app-settings-logging-migration.preferences_pb")
    dataStore.edit { preferences ->
        preferences[booleanPreferencesKey("logging_enabled")] = false
    }

    assertEquals(ReaderLoggingMode.OFF, AppSettingsStore(dataStore).settings.first().readerLoggingMode)
}
```

- [ ] **Step 2: Run settings tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest
```

Expected: compile failure because `ReaderLoggingMode`, `readerLoggingMode`, and `updateReaderLoggingMode` do not exist.

- [ ] **Step 3: Implement settings mode**

Add to `AppSettingsStore.kt`:

```kotlin
enum class ReaderLoggingMode {
    OFF,
    SUMMARY,
    DETAIL,
}
```

Update `AppSettings`:

```kotlin
data class AppSettings(
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val readerLoggingMode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val autoPageEnabled: Boolean = false,
    val autoPageSpeedMillis: Int = 5_000,
    val screenRotationLockEnabled: Boolean = false,
    val volumeKeysTurnPagesEnabled: Boolean = false,
    val diskCacheLimitGb: Int = 1,
) {
    val loggingEnabled: Boolean
        get() = readerLoggingMode != ReaderLoggingMode.OFF
}
```

Use this mapping in `settings`:

```kotlin
readerLoggingMode = preferences[READER_LOGGING_MODE]
    .toEnumOrDefault<ReaderLoggingMode>(null)
    ?: if (preferences[LOGGING_ENABLED] == false) ReaderLoggingMode.OFF else ReaderLoggingMode.SUMMARY,
```

Add:

```kotlin
suspend fun updateReaderLoggingMode(mode: ReaderLoggingMode) {
    dataStore.edit { preferences ->
        preferences[READER_LOGGING_MODE] = mode.name
        preferences[LOGGING_ENABLED] = mode != ReaderLoggingMode.OFF
    }
}

suspend fun updateLoggingEnabled(enabled: Boolean) {
    updateReaderLoggingMode(if (enabled) ReaderLoggingMode.SUMMARY else ReaderLoggingMode.OFF)
}
```

Add preference key:

```kotlin
val READER_LOGGING_MODE = stringPreferencesKey("reader_logging_mode")
```

Change the enum helper to accept a nullable default:

```kotlin
private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T?): T? {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
}
```

- [ ] **Step 4: Update existing settings tests**

Replace `loggingEnabled = true/false` constructor expectations with `readerLoggingMode = ReaderLoggingMode.SUMMARY/OFF` while keeping `assertTrue(settings.loggingEnabled)` and `assertFalse(settings.loggingEnabled)` where useful.

- [ ] **Step 5: Write failing diagnostic log API tests**

Add tests to `ReaderDiagnosticLogTest`:

```kotlin
@Test
fun summaryModeWritesSummaryButSkipsDetailBuilders() {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    var detailBuilt = false
    try {
        ReaderDiagnosticLog.summary(ReaderLogCategory.SESSION) { "reader_open pageCount=2" }
        ReaderDiagnosticLog.detail(ReaderLogCategory.UI) {
            detailBuilt = true
            "pager current=1"
        }
    } finally {
        ReaderDiagnosticLog.clearSink()
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    }

    assertTrue(sink.lines.single().contains("level=summary category=SESSION reader_open pageCount=2"))
    assertFalse(detailBuilt)
}

@Test
fun detailModeWritesDetailEvents() {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
    try {
        ReaderDiagnosticLog.detail(ReaderLogCategory.UI) { "pager current=1" }
    } finally {
        ReaderDiagnosticLog.clearSink()
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    }

    assertTrue(sink.lines.single().contains("level=detail category=UI pager current=1"))
}

@Test
fun redactionRemovesRawUriPathAndFileName() {
    val line = redactReaderLogText(
        "uri=content://provider/private/book.cbz path=/secret/books/book.cbz fileName=Secret Book.cbz",
    )

    assertFalse(line.contains("content://provider/private/book.cbz"))
    assertFalse(line.contains("/secret/books/book.cbz"))
    assertFalse(line.contains("Secret Book.cbz"))
    assertTrue(line.contains("uriId=local:"))
    assertTrue(line.contains("pathId=path:"))
    assertTrue(line.contains("fileExt=cbz"))
}
```

Add this private collecting sink in the test file:

```kotlin
private class CollectingReaderLogSink : ReaderLogSink {
    val lines = mutableListOf<String>()
    override fun log(line: String) { lines += line }
    override fun logBlocking(line: String) { lines += line }
}
```

- [ ] **Step 6: Run diagnostic tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest
```

Expected: compile failure because `setMode`, `summary`, `detail`, `ReaderLogCategory`, and `redactReaderLogText` do not exist.

- [ ] **Step 7: Implement diagnostic mode API**

In `ReaderDiagnosticLog.kt`, import `ReaderLoggingMode`, add:

```kotlin
enum class ReaderLogCategory {
    SESSION,
    LOCAL_FILE,
    PAGE_LOAD,
    IMAGE,
    PREFETCH,
    RANGE_CACHE,
    UI,
}
```

Add mode state:

```kotlin
@Volatile
private var mode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY

fun setMode(nextMode: ReaderLoggingMode) {
    mode = nextMode
}
```

Add lazy APIs:

```kotlin
fun summary(category: ReaderLogCategory, event: () -> String) {
    if (mode == ReaderLoggingMode.OFF) return
    write("summary", category, event)
}

fun detail(category: ReaderLogCategory, event: () -> String) {
    if (mode != ReaderLoggingMode.DETAIL) return
    write("detail", category, event)
}

private fun write(level: String, category: ReaderLogCategory, event: () -> String) {
    runCatching {
        sink.log(formatReaderLogLine("level=$level category=${category.name} ${redactReaderLogText(event())}"))
    }
}
```

Keep compatibility:

```kotlin
fun event(event: String) {
    summary(ReaderLogCategory.SESSION) { event }
}
```

Update `error` and `errorBlocking` to write `level=error category=<CATEGORY>` through redaction. Keep existing overloads:

```kotlin
fun error(category: ReaderLogCategory, event: String, error: Throwable) {
    if (mode == ReaderLoggingMode.OFF) return
    runCatching {
        sink.log(formatReaderLogLine("level=error category=${category.name} ${redactReaderLogText(formatThrowable(event, error))}"))
    }
}

fun error(event: String, error: Throwable) = error(ReaderLogCategory.SESSION, event, error)

fun errorBlocking(category: ReaderLogCategory, event: String, error: Throwable) {
    if (mode == ReaderLoggingMode.OFF) return
    runCatching {
        sink.logBlocking(formatReaderLogLine("level=error category=${category.name} ${redactReaderLogText(formatThrowable(event, error))}"))
    }
}

fun errorBlocking(event: String, error: Throwable) = errorBlocking(ReaderLogCategory.SESSION, event, error)
```

Implement `redactReaderLogText` using `MessageDigest.getInstance("SHA-256")`, replacing `uri=<value>`, `path=<value>`, `folderUri=<value>`, and `fileName=<value>` tokens with short IDs and extension where available.

- [ ] **Step 8: Update settings UI**

Change `SettingsScreen` parameter from:

```kotlin
onLoggingEnabledChange: (Boolean) -> Unit,
```

to:

```kotlin
onReaderLoggingModeChange: (ReaderLoggingMode) -> Unit,
```

Replace the logging switch with:

```kotlin
ChoiceRow(
    title = "诊断日志",
    options = ReaderLoggingMode.entries,
    selected = settings.readerLoggingMode,
    label = ReaderLoggingMode::label,
    onSelected = onReaderLoggingModeChange,
)
```

Add:

```kotlin
private fun ReaderLoggingMode.label(): String =
    when (this) {
        ReaderLoggingMode.OFF -> "关闭"
        ReaderLoggingMode.SUMMARY -> "摘要"
        ReaderLoggingMode.DETAIL -> "详细"
    }
```

Update `MainActivity` call site to pass:

```kotlin
onReaderLoggingModeChange = { mode ->
    scope.launch { appSettingsStore.updateReaderLoggingMode(mode) }
}
```

- [ ] **Step 9: Run focused tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest --tests com.example.comicdav.feature.settings.SettingsScreenTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticLog.kt app/src/main/java/com/example/comicdav/MainActivity.kt app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenTest.kt
git commit -m "feat: add reader logging modes"
```

### Task 2: Local Archive Open Boundary Diagnostics

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/LocalComicOpener.kt`
- Modify: `app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/LocalComicOpenerTest.kt`
- Test: `app/src/test/java/com/example/comicdav/nativebridge/ComicEngineTest.kt`

- [ ] **Step 1: Write failing LocalComicOpener diagnostic test**

Add to `LocalComicOpenerTest`:

```kotlin
@Test
fun openerReportsArchiveOpenDiagnosticsWithoutRawFileName() {
    val archive = temp.newFile("Secret Book.cbz").apply { writeBytes(ByteArray(64)) }
    val lines = mutableListOf<String>()
    val opener = LocalComicOpener(
        context = ApplicationProvider.getApplicationContext(),
        openSession = { _, _, _ -> FakeReaderSession(pageCount = 3) },
        logDiagnostic = { lines += it },
        elapsedRealtimeMs = sequenceOf(100L, 115L, 160L).iterator()::next,
    )

    opener.open(Uri.fromFile(archive), archive.name)

    val line = lines.single()
    assertTrue(line.contains("local_open_done"))
    assertTrue(line.contains("engine=native-archive"))
    assertTrue(line.contains("format=ZIP"))
    assertTrue(line.contains("sizeBytes=64"))
    assertTrue(line.contains("descriptorOpenMs=15"))
    assertTrue(line.contains("openSessionMs=45"))
    assertTrue(line.contains("pageCount=3"))
    assertFalse(line.contains("Secret Book.cbz"))
}
```

- [ ] **Step 2: Run LocalComicOpener test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.LocalComicOpenerTest
```

Expected: compile failure because `logDiagnostic` and `elapsedRealtimeMs` constructor parameters do not exist.

- [ ] **Step 3: Implement LocalComicOpener diagnostics**

Add constructor parameters:

```kotlin
private val logDiagnostic: (String) -> Unit = { line ->
    ReaderDiagnosticLog.summary(ReaderLogCategory.LOCAL_FILE) { line }
},
private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
```

Around archive open:

```kotlin
val startedAtMs = elapsedRealtimeMs()
val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("无法读取所选文件")
val descriptorOpenedAtMs = elapsedRealtimeMs()
val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
val fd = descriptor.detachFd()
val session = openSession(fd, size, format)
val openedAtMs = elapsedRealtimeMs()
logDiagnostic(
    "local_open_done engine=native-archive format=${format.name.uppercase()} " +
        "fileExt=${fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()} " +
        "sizeBytes=$size pageCount=${session.pageCount} " +
        "descriptorOpenMs=${descriptorOpenedAtMs - startedAtMs} " +
        "openSessionMs=${openedAtMs - descriptorOpenedAtMs} totalMs=${openedAtMs - startedAtMs}",
)
return session
```

Use equivalent document fields with `engine=mupdf` for document formats.

- [ ] **Step 4: Write failing ComicEngine boundary test**

Add to `ComicEngineTest`:

```kotlin
@Test
fun openLocalFdReportsNativeOpenAndPageCountTiming() {
    val lines = mutableListOf<String>()
    val native = FakeComicNative(openHandle = 8, pageCount = 5)
    val engine = ComicEngine(
        native = native,
        logDiagnostic = { lines += it },
        elapsedRealtimeMs = sequenceOf(10L, 35L, 42L).iterator()::next,
    )

    engine.openLocalFd(fd = 11, size = 2048, format = "zip")

    assertEquals(
        listOf("native_open_local_fd_done format=zip sizeBytes=2048 nativeOpenMs=25 pageCountMs=7 pageCount=5"),
        lines,
    )
}
```

- [ ] **Step 5: Implement ComicEngine boundary diagnostics**

Add constructor parameters:

```kotlin
private val logDiagnostic: (String) -> Unit = { line ->
    ReaderDiagnosticLog.summary(ReaderLogCategory.LOCAL_FILE) { line }
},
private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
```

In `openLocalFd`, measure native open and pass timing context into `openChecked`:

```kotlin
val startedAtMs = elapsedRealtimeMs()
val handle = native.openLocalFd(fd, size, format)
val openedAtMs = elapsedRealtimeMs()
return openChecked(handle, diagnostics = NativeOpenDiagnostics("native_open_local_fd_done", format, size, openedAtMs - startedAtMs))
```

After `pageCount`, emit:

```kotlin
logDiagnostic("${diagnostics.event} format=${diagnostics.format} sizeBytes=${diagnostics.sizeBytes} nativeOpenMs=${diagnostics.nativeOpenMs} pageCountMs=$pageCountMs pageCount=$pageCount")
```

- [ ] **Step 6: Run focused tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.LocalComicOpenerTest --tests com.example.comicdav.nativebridge.ComicEngineTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/LocalComicOpener.kt app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt app/src/test/java/com/example/comicdav/feature/reader/LocalComicOpenerTest.kt app/src/test/java/com/example/comicdav/nativebridge/ComicEngineTest.kt
git commit -m "feat: log local archive open timings"
```

### Task 3: MuPDF Document Open And Render Diagnostics

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/RealMuPdfDocumentAdapter.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfRenderScaleTest.kt`

- [ ] **Step 1: Write failing MuPdfReaderSession diagnostics test**

Add to `MuPdfReaderSessionTest`:

```kotlin
@Test
fun loadPageToFileReportsRenderDiagnostics() {
    val output = File(temp.root, "page-0.jpg")
    val lines = mutableListOf<String>()
    val document = FakeMuPdfDocument(pageCount = 2)
    val session = MuPdfReaderSession(
        document = document,
        format = LocalDocumentFormat.Pdf,
        logDiagnostic = { lines += it },
        elapsedRealtimeMs = sequenceOf(100L, 140L).iterator()::next,
    )

    session.loadPageToFile(0, output)

    val line = lines.single()
    assertTrue(line.contains("mupdf_render_done"))
    assertTrue(line.contains("format=PDF"))
    assertTrue(line.contains("page=0"))
    assertTrue(line.contains("renderMs=40"))
    assertTrue(line.contains("outputBytes=${output.length()}"))
    assertTrue(line.contains("maxPixels=$DEFAULT_MUPDF_RENDER_MAX_PIXELS"))
    assertTrue(line.contains("quality=$DEFAULT_MUPDF_RENDER_JPEG_QUALITY"))
}
```

- [ ] **Step 2: Run MuPDF session test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest
```

Expected: compile failure because `logDiagnostic` and `elapsedRealtimeMs` constructor parameters do not exist.

- [ ] **Step 3: Implement MuPdfReaderSession render diagnostics**

Add constructor parameters:

```kotlin
private val logDiagnostic: (String) -> Unit = { line ->
    ReaderDiagnosticLog.summary(ReaderLogCategory.PAGE_LOAD) { line }
},
private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
```

Wrap render:

```kotlin
val startedAtMs = elapsedRealtimeMs()
document.renderPageToJpeg(pageIndex, outputFile, maxPixels, DEFAULT_MUPDF_RENDER_JPEG_QUALITY)
val renderMs = (elapsedRealtimeMs() - startedAtMs).coerceAtLeast(0L)
logDiagnostic(
    "mupdf_render_done format=${format.displayName} page=$pageIndex pageCount=$pageCount " +
        "renderMs=$renderMs outputBytes=${outputFile.length()} maxPixels=$maxPixels " +
        "quality=$DEFAULT_MUPDF_RENDER_JPEG_QUALITY",
)
```

On failure, log `mupdf_render_failed` with `failureClass`, `deletedPartial`, and sanitized error through `ReaderDiagnosticLog.error` if practical.

- [ ] **Step 4: Add render metadata type**

In `MuPdfDocumentAdapter.kt`, add:

```kotlin
data class MuPdfRenderMetrics(
    val boundsWidth: Float,
    val boundsHeight: Float,
    val scale: Float,
    val estimatedPixels: Long,
    val estimatedRgbBytes: Long,
    val pixmapMs: Long,
    val jpegMs: Long,
)
```

Change `renderPageToJpeg` to return `MuPdfRenderMetrics?`. Fake tests can return `null` until real adapter metrics are asserted.

- [ ] **Step 5: Instrument RealMuPdfDocumentAdapter render phases**

In `RealMuPdfDocumentHandle.renderPageToJpeg`, measure:

```kotlin
val pixmapStartedAtMs = elapsedRealtimeMs()
val pixmap = page.toPixmap(Matrix.Scale(scale), ColorSpace.DeviceRGB, false)
val pixmapMs = elapsedRealtimeMs() - pixmapStartedAtMs
val jpegStartedAtMs = elapsedRealtimeMs()
pixmap.saveAsJPEG(outputFile.absolutePath, quality)
val jpegMs = elapsedRealtimeMs() - jpegStartedAtMs
```

Return `MuPdfRenderMetrics(boundsWidth, boundsHeight, scale, estimatedPixels, estimatedRgbBytes, pixmapMs, jpegMs)`.

- [ ] **Step 6: Run focused tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfRenderScaleTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt app/src/main/java/com/example/comicdav/feature/reader/mupdf/RealMuPdfDocumentAdapter.kt app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfRenderScaleTest.kt
git commit -m "feat: log mupdf render timings"
```

### Task 4: Reader Session Performance Summary

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticsTracker.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`

- [ ] **Step 1: Write failing summary test**

Add to `ReaderViewModelTest`:

```kotlin
@Test
fun closeReaderEmitsLocalPerformanceSummary() = runTest(dispatcher) {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    val session = FakeReaderSession(pageCount = 2)
    val viewModel = ReaderViewModel(openSession = { session }, ioDispatcher = dispatcher)

    viewModel.openLocal("/tmp/book.cbz", temp.root, comicKey = "local-key")
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.selectPage(1)
    dispatcher.scheduler.advanceUntilIdle()
    viewModel.closeReader()
    dispatcher.scheduler.advanceUntilIdle()

    val summary = sink.lines.lastOrNull { it.contains("local_session_summary") }.orEmpty()
    assertTrue(summary.contains("pagesLoaded=2"))
    assertTrue(summary.contains("cacheMisses=2"))
    assertTrue(summary.contains("largestOutputBytes="))
    assertTrue(summary.contains("slowestPage="))
    ReaderDiagnosticLog.clearSink()
    ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
}
```

- [ ] **Step 2: Run ViewModel test and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest
```

Expected: test failure because no `local_session_summary` is emitted.

- [ ] **Step 3: Add summary counters**

In `ReaderDiagnosticsTracker`, add counters for:

```kotlin
data class LocalSessionPerformanceSummary(
    val pagesLoaded: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val totalOutputBytes: Long,
    val largestOutputBytes: Long,
    val slowestPage: Int?,
    val slowestPageMs: Long,
    val slowestPageReason: String,
)
```

Update `recordPageLoadTiming` to maintain cache hits, misses, output bytes, and slowest page.

Add:

```kotlin
fun localSessionSummary(): LocalSessionPerformanceSummary =
    LocalSessionPerformanceSummary(
        pagesLoaded = pageLoadTimings.size,
        cacheHits = pageLoadTimings.values.count { it.cacheHit },
        cacheMisses = pageLoadTimings.values.count { !it.cacheHit },
        totalOutputBytes = pageLoadTimings.values.sumOf { it.fileSize },
        largestOutputBytes = pageLoadTimings.values.maxOfOrNull { it.fileSize } ?: 0L,
        slowestPage = pageLoadTimings.maxByOrNull { it.value.extractMs ?: 0L }?.key,
        slowestPageMs = pageLoadTimings.values.maxOfOrNull { it.extractMs ?: 0L } ?: 0L,
        slowestPageReason = pageLoadTimings.maxByOrNull { it.value.extractMs ?: 0L }
            ?.let { if (it.value.cacheHit) "cache-read" else "archive-extract" }
            ?: "unknown",
    )
```

- [ ] **Step 4: Emit summary on close**

In `ReaderViewModel.closeCurrentSession`, before resetting state or after cancelling jobs, emit:

```kotlin
val summary = diagnostics.localSessionSummary()
ReaderDiagnosticLog.summary(ReaderLogCategory.SESSION) {
    "local_session_summary pagesLoaded=${summary.pagesLoaded} cacheHits=${summary.cacheHits} " +
        "cacheMisses=${summary.cacheMisses} totalOutputBytes=${summary.totalOutputBytes} " +
        "largestOutputBytes=${summary.largestOutputBytes} slowestPage=${summary.slowestPage ?: "none"} " +
        "slowestPageMs=${summary.slowestPageMs} slowestPageReason=${summary.slowestPageReason}"
}
```

Only emit when at least one local page was loaded.

- [ ] **Step 5: Route noisy events to detail**

Change these existing calls from `event` to `detail`:

```kotlin
ReaderDiagnosticLog.detail(ReaderLogCategory.UI) { "select_page_requested page=$pageIndex generation=$generation" }
ReaderDiagnosticLog.detail(ReaderLogCategory.IMAGE) { "image_load_start page=$pageIndex" }
ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) { "prefetch_page_start page=$page" }
ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) { "planned_range_prefetch_start start=${range.start} end=${range.endInclusive} pages=${range.pages} priority=${range.priority}" }
```

Keep success/failure summaries and analysis lines in summary.

- [ ] **Step 6: Run focused tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderDiagnosticsTracker.kt app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt
git commit -m "feat: summarize local reader performance"
```

### Task 5: MainActivity Integration And Identifier Privacy

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt`
- Test: `app/src/test/java/com/example/comicdav/network/WebDavRangeProviderTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt`

- [ ] **Step 1: Write failing off-mode gating test**

Add this lower-level `ReaderDiagnosticLogTest`:

```kotlin
@Test
fun offModeDoesNotInvokeSummaryOrDetailBuilders() {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    ReaderDiagnosticLog.setMode(ReaderLoggingMode.OFF)
    var summaryBuilt = false
    var detailBuilt = false
    try {
        ReaderDiagnosticLog.summary(ReaderLogCategory.SESSION) {
            summaryBuilt = true
            "summary"
        }
        ReaderDiagnosticLog.detail(ReaderLogCategory.UI) {
            detailBuilt = true
            "detail"
        }
    } finally {
        ReaderDiagnosticLog.clearSink()
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
    }

    assertFalse(summaryBuilt)
    assertFalse(detailBuilt)
    assertTrue(sink.lines.isEmpty())
}
```

- [ ] **Step 2: Apply mode in MainActivity**

Add:

```kotlin
LaunchedEffect(appSettings.readerLoggingMode) {
    ReaderDiagnosticLog.setMode(appSettings.readerLoggingMode)
    if (appSettings.readerLoggingMode == ReaderLoggingMode.OFF) {
        ReaderDiagnosticLog.clearSink()
    }
}
```

Change every `startReaderLogFile` call that passes `loggingEnabled = appSettings.loggingEnabled` to use:

```kotlin
loggingEnabled = appSettings.readerLoggingMode != ReaderLoggingMode.OFF
```

- [ ] **Step 3: Remove raw identifiers from MainActivity log events**

Replace examples like:

```kotlin
ReaderDiagnosticLog.error("delete_local_source_files_failed uri=$treeUriText", error)
```

with:

```kotlin
ReaderDiagnosticLog.error(
    ReaderLogCategory.LOCAL_FILE,
    "delete_local_source_files_failed uriId=${readerLogId("local", treeUriText)}",
    error,
)
```

Use `redactReaderLogText` or a helper such as `readerLogId(prefix, raw)` from `ReaderDiagnosticLog.kt`; do not keep raw URI/path/file names in event text.

- [ ] **Step 4: Move WebDAV range diagnostics to detail**

In `WebDavRangeProvider`, change default logger to:

```kotlin
private val logDiagnostic: (String) -> Unit = { line ->
    ReaderDiagnosticLog.detail(ReaderLogCategory.RANGE_CACHE) { line }
}
```

Existing tests that assert range diagnostics should set:

```kotlin
ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
```

and reset it in `finally`.

- [ ] **Step 5: Run focused tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderDiagnosticLogTest --tests com.example.comicdav.network.WebDavRangeProviderTest
```

Expected: PASS.

Commit:

```bash
git add app/src/main/java/com/example/comicdav/MainActivity.kt app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt app/src/test/java/com/example/comicdav/network/WebDavRangeProviderTest.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderDiagnosticLogTest.kt
git commit -m "feat: apply reader logging privacy controls"
```

### Task 6: Full Verification

**Files:**
- Review all changed files.

- [ ] **Step 1: Run Android unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run Rust tests if Rust changed**

Run:

```bash
cd comic-core && cargo test
```

Expected: all tests pass.

- [ ] **Step 3: Inspect final diff**

Run:

```bash
git status --short
git log --oneline -6
git diff --stat master HEAD
```

Expected: only logging/settings/local diagnostics files and tests changed.
