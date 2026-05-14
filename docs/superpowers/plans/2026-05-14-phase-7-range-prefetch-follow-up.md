# Phase 7 Range Prefetch Follow-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce Phase 6B sequential-reader stalls by eliminating duplicate overlapping WebDAV range requests and limiting low-priority planned range bandwidth contention.

**Architecture:** Keep the native session mutex in place. Add byte-range in-flight coalescing inside `WebDavRangeProvider`, where network request state already lives, while leaving `RangeWindowCache` focused on completed cached byte windows. Add planned range concurrency limits in `ReaderViewModel` so near-page work stays responsive and far-page planned prefetch does not flood the connection.

**Tech Stack:** Kotlin coroutines, `CompletableDeferred`, `kotlinx.coroutines.sync.Semaphore`, JUnit4, Mock/fake `WebDavClient`, Android unit tests.

---

## Files

- Modify: `app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt`
  - Owns WebDAV range reads, completed range cache, and new in-flight request coalescing.
- Modify: `app/src/test/java/com/example/comicdav/network/WebDavRangeProviderTest.kt`
  - Adds deterministic tests for read/prefetch sharing of covering in-flight requests and diagnostics.
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
  - Adds planned range concurrency limits without changing page-level prefetch or session mutex behavior.
- Modify: `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`
  - Adds deterministic planned range ordering/concurrency tests.
- Modify: `docs/superpowers/plans/2026-05-13-phase-7-compatibility-hardening.md`
  - Marks this follow-up plan as the implementation path for Task 5.

## Task 1: WebDavRangeProvider In-Flight Deduplication

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt`
- Modify: `app/src/test/java/com/example/comicdav/network/WebDavRangeProviderTest.kt`

- [ ] **Step 1: Write a failing test for readRange joining a covering prefetch**

Add these imports to `WebDavRangeProviderTest.kt`:

```kotlin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
```

Add this test above `rangeCacheDiagnosticsIncludeHitMissStoreAndEvict()`:

```kotlin
@Test
fun readRangeJoinsCoveringInFlightPrefetchWithoutSecondWebDavRequest() {
    val sink = CollectingReaderLogSink()
    ReaderDiagnosticLog.setSink(sink)
    try {
        val bytes = ByteArray(128) { it.toByte() }
        val release = CompletableDeferred<Unit>()
        val firstReadStarted = CountDownLatch(1)
        val client = BlockingWebDavClient(
            bytes = bytes,
            release = release,
            firstReadStarted = firstReadStarted,
        )
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
        )

        val prefetchThread = Thread {
            provider.prefetchRange(start = 40, endInclusive = 79)
        }
        prefetchThread.start()
        assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))

        val readThreadResult = mutableListOf<ByteArray>()
        val readThread = Thread {
            readThreadResult += provider.readRange(fileId = 1, start = 50, endInclusive = 59)
        }
        readThread.start()
        Thread.sleep(100)

        assertEquals(listOf(40L to 79L), client.rangeCalls)
        release.complete(Unit)
        prefetchThread.join(1_000)
        readThread.join(1_000)

        assertArrayEquals(bytes.sliceArray(50..59), readThreadResult.single())
        assertEquals(listOf(40L to 79L), client.rangeCalls)
        assertTrue(sink.lines.any { it.contains("range_inflight_join") && it.contains("start=50") && it.contains("end=59") })
    } finally {
        ReaderDiagnosticLog.clearSink()
    }
}
```

Add this helper next to `RecordingWebDavClient`:

```kotlin
private class BlockingWebDavClient(
    private val bytes: ByteArray,
    private val release: CompletableDeferred<Unit>,
    private val firstReadStarted: CountDownLatch,
) : WebDavClient {
    val rangeCalls = mutableListOf<Pair<Long, Long>>()

    override suspend fun list(path: String): List<WebDavItem> = emptyList()

    override suspend fun head(path: String): RemoteFileInfo =
        RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
        rangeCalls += start to endInclusive
        firstReadStarted.countDown()
        release.await()
        return bytes.sliceArray(start.toInt()..endInclusive.toInt())
    }

    override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
        error("unused")
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.network.WebDavRangeProviderTest.readRangeJoinsCoveringInFlightPrefetchWithoutSecondWebDavRequest
```

Expected: FAIL because `client.rangeCalls` contains both `40L to 79L` and `50L to 59L`, or because no `range_inflight_join` diagnostic exists.

- [ ] **Step 3: Add in-flight range state and helper types**

In `WebDavRangeProvider.kt`, add these imports:

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
```

Replace the existing single `runBlocking` import if needed so the imports remain sorted by package.

Inside `WebDavRangeProvider`, near `private val cache = RangeWindowCache(maxCacheBytes)`, add:

```kotlin
private val inFlightRanges = mutableListOf<InFlightRange>()
```

Near `PostFetchResult`, add:

```kotlin
private data class InFlightRange(
    val start: Long,
    val endInclusive: Long,
    val deferred: CompletableDeferred<ByteArray>,
) {
    fun covers(reqStart: Long, reqEndInclusive: Long): Boolean =
        reqStart >= start && reqEndInclusive <= endInclusive

    fun slice(bytes: ByteArray, reqStart: Long, reqEndInclusive: Long): ByteArray {
        val from = (reqStart - start).toInt()
        val toExclusive = (reqEndInclusive - start + 1).toInt()
        return bytes.copyOfRange(from, toExclusive)
    }
}

private data class RegisteredFetch(
    val start: Long,
    val endInclusive: Long,
    val deferred: CompletableDeferred<ByteArray>,
)

private sealed class FetchDecision {
    data class Join(val inFlight: InFlightRange) : FetchDecision()
    data class Fetch(val fetch: RegisteredFetch) : FetchDecision()
}
```

- [ ] **Step 4: Add lock-scoped helpers for in-flight lookup and lifecycle**

In `WebDavRangeProvider`, add these private methods before `emitDiagnostic()`:

```kotlin
private fun coveringInFlight(start: Long, endInclusive: Long): InFlightRange? =
    inFlightRanges.firstOrNull { it.covers(start, endInclusive) }

private fun registerInFlight(start: Long, endInclusive: Long): RegisteredFetch {
    val deferred = CompletableDeferred<ByteArray>()
    inFlightRanges += InFlightRange(start = start, endInclusive = endInclusive, deferred = deferred)
    return RegisteredFetch(start = start, endInclusive = endInclusive, deferred = deferred)
}

private fun completeInFlight(fetch: RegisteredFetch, bytes: ByteArray) {
    synchronized(lock) {
        inFlightRanges.removeAll { it.deferred === fetch.deferred }
    }
    fetch.deferred.complete(bytes)
}

private fun failInFlight(fetch: RegisteredFetch, error: Throwable) {
    synchronized(lock) {
        inFlightRanges.removeAll { it.deferred === fetch.deferred }
    }
    fetch.deferred.completeExceptionally(error)
}

private fun awaitInFlight(inFlight: InFlightRange, start: Long, endInclusive: Long): ByteArray {
    emitDiagnostic(
        "range_inflight_join path=$path start=$start end=$endInclusive " +
            "windowStart=${inFlight.start} windowEnd=${inFlight.endInclusive}",
    )
    val bytes = runBlocking { inFlight.deferred.await() }
    return inFlight.slice(bytes, start, endInclusive)
}
```

- [ ] **Step 5: Use in-flight lookup in readRange()**

In `readRange()`, after the cache hit check and before logging `range_cache_miss`, compute `expandedEnd`, then add an in-flight lookup and registration:

```kotlin
val expandedEnd = (endInclusive + readAheadBytes)
    .coerceAtMost(size - 1)
    .coerceAtLeast(endInclusive)
val decision = synchronized(lock) {
    coveringInFlight(start, endInclusive)?.let { existing ->
        FetchDecision.Join(existing)
    } ?: coveringInFlight(start, expandedEnd)?.let { existing ->
        FetchDecision.Join(existing)
    } ?: FetchDecision.Fetch(registerInFlight(start, expandedEnd))
}
if (decision is FetchDecision.Join) {
    return awaitInFlight(decision.inFlight, start, endInclusive)
}
val fetch = (decision as FetchDecision.Fetch).fetch
```

Then wrap the network read and store logic so successful fetches call `completeInFlight(fetch, bytes)` and failures call `failInFlight(fetch, error)`:

```kotlin
val bytes = try {
    runBlocking {
        client.readRange(path, fetch.start, fetch.endInclusive)
    }
} catch (error: Throwable) {
    failInFlight(fetch, error)
    throw error
}
val postFetch = synchronized(lock) {
    val storeResult = cache.store(fetch.start, fetch.endInclusive, bytes)
    val result = cache.find(start, endInclusive)?.bytes
        ?: bytes.copyOfRange(0, (endInclusive - start + 1).toInt())
    PostFetchResult(
        bytes = result,
        storeResult = storeResult,
        cacheBytes = cache.totalBytes(),
        windowCount = cache.windowCount(),
    )
}
completeInFlight(fetch, bytes)
```

Keep existing `range_cache_miss`, `range_cache_store`, and eviction diagnostics, but update stored start/end values to use `fetch.start` and `fetch.endInclusive`.

- [ ] **Step 6: Use in-flight lookup in prefetchRange()**

In `prefetchRange()`, after the cache hit check and before `range_prefetch_start`, add:

```kotlin
val decision = synchronized(lock) {
    coveringInFlight(start, clampedEnd)?.let { existing ->
        FetchDecision.Join(existing)
    } ?: FetchDecision.Fetch(registerInFlight(start, clampedEnd))
}
if (decision is FetchDecision.Join) {
    awaitInFlight(decision.inFlight, start, clampedEnd)
    return synchronized(lock) {
        cache.find(start, clampedEnd) != null
    }
}
val fetch = (decision as FetchDecision.Fetch).fetch
```

Use `fetch.start` and `fetch.endInclusive` for the actual network call, store, diagnostics, and completion. On failure, call `failInFlight(fetch, error)` before rethrowing.

- [ ] **Step 7: Run the focused WebDavRangeProvider test**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.network.WebDavRangeProviderTest.readRangeJoinsCoveringInFlightPrefetchWithoutSecondWebDavRequest
```

Expected: PASS. The test should show one WebDAV call and a `range_inflight_join` diagnostic.

- [ ] **Step 8: Add a second failing test for concurrent readRange dedupe**

Add this test to `WebDavRangeProviderTest.kt`:

```kotlin
@Test
fun concurrentReadRangesJoinTheFirstCoveringFetch() {
    val bytes = ByteArray(128) { it.toByte() }
    val release = CompletableDeferred<Unit>()
    val firstReadStarted = CountDownLatch(1)
    val client = BlockingWebDavClient(
        bytes = bytes,
        release = release,
        firstReadStarted = firstReadStarted,
    )
    val provider = WebDavRangeProvider(
        client = client,
        path = "/books/book.cbz",
        size = bytes.size.toLong(),
        readAheadBytes = 32,
    )

    val firstResult = mutableListOf<ByteArray>()
    val secondResult = mutableListOf<ByteArray>()
    val firstThread = Thread {
        firstResult += provider.readRange(fileId = 1, start = 10, endInclusive = 19)
    }
    val secondThread = Thread {
        secondResult += provider.readRange(fileId = 1, start = 30, endInclusive = 40)
    }

    firstThread.start()
    assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))
    secondThread.start()
    Thread.sleep(100)

    assertEquals(listOf(10L to 51L), client.rangeCalls)
    release.complete(Unit)
    firstThread.join(1_000)
    secondThread.join(1_000)

    assertArrayEquals(bytes.sliceArray(10..19), firstResult.single())
    assertArrayEquals(bytes.sliceArray(30..40), secondResult.single())
    assertEquals(listOf(10L to 51L), client.rangeCalls)
}
```

- [ ] **Step 9: Run all network range provider tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.network.WebDavRangeProviderTest
```

Expected: PASS.

- [ ] **Step 10: Commit Task 1**

Run:

```bash
git add app/src/main/java/com/example/comicdav/network/WebDavRangeProvider.kt app/src/test/java/com/example/comicdav/network/WebDavRangeProviderTest.kt
git commit -m "fix: dedupe in-flight webdav ranges"
```

## Task 2: Planned Range Concurrency Limits

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- Modify: `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`

- [ ] **Step 1: Write a failing test for low-priority planned range serialization**

Add this test to `ReaderViewModelTest.kt` near the existing planned range tests:

```kotlin
@Test
fun lowPriorityPlannedRangesAreSerialized() = runTest(dispatcher) {
    val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
    val ioDispatcher = executor.asCoroutineDispatcher()
    val firstLowPriorityStarted = CountDownLatch(1)
    val releaseLowPriority = CountDownLatch(1)
    val session = ConcurrencyTrackingPlannedRangeSession(
        pageCount = 8,
        plannedRangesByPage = mapOf(
            0 to listOf(
                PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1), priority = 5),
                PlannedRemoteRange(start = 200, endInclusive = 299, pages = listOf(2), priority = 6),
                PlannedRemoteRange(start = 300, endInclusive = 399, pages = listOf(3), priority = 7),
            ),
        ),
        blockingRange = 100L to 199L,
        firstBlockedRangeStarted = firstLowPriorityStarted,
        releaseBlockedRange = releaseLowPriority,
    )
    val viewModel = ReaderViewModel(
        openSession = { session },
        ioDispatcher = ioDispatcher,
    )
    try {
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            firstLowPriorityStarted.count == 0L
        }

        Thread.sleep(150)
        assertEquals(1, session.maxConcurrentPrefetches)
        assertEquals(listOf(100L to 199L), session.prefetchedRanges)

        releaseLowPriority.countDown()
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            session.prefetchedRanges.containsAll(
                listOf(100L to 199L, 200L to 299L, 300L to 399L),
            )
        }

        assertEquals(1, session.maxConcurrentPrefetches)
    } finally {
        executor.shutdownNow()
    }
}
```

Add this fake session near other `ComicReaderSession` fakes:

```kotlin
private class ConcurrencyTrackingPlannedRangeSession(
    override val pageCount: Int,
    private val plannedRangesByPage: Map<Int, List<PlannedRemoteRange>>,
    private val blockingRange: Pair<Long, Long>,
    private val firstBlockedRangeStarted: CountDownLatch,
    private val releaseBlockedRange: CountDownLatch,
) : ComicReaderSession {
    private val lock = Any()
    private var activePrefetches = 0
    var maxConcurrentPrefetches = 0
        private set
    private val recordedPrefetchedRanges = mutableListOf<Pair<Long, Long>>()
    val prefetchedRanges: List<Pair<Long, Long>>
        get() = synchronized(lock) { recordedPrefetchedRanges.toList() }

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        outputFile.writeText("page-$pageIndex")
        return outputFile
    }

    override fun updateViewport(pageIndex: Int, networkClass: Int) = Unit

    override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> =
        plannedRangesByPage[pageIndex].orEmpty()

    override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
        synchronized(lock) {
            activePrefetches += 1
            maxConcurrentPrefetches = maxOf(maxConcurrentPrefetches, activePrefetches)
            recordedPrefetchedRanges += start to endInclusive
        }
        try {
            if (start to endInclusive == blockingRange) {
                firstBlockedRangeStarted.countDown()
                releaseBlockedRange.await(2, TimeUnit.SECONDS)
            }
            return true
        } finally {
            synchronized(lock) {
                activePrefetches -= 1
            }
        }
    }

    override fun diagnostics(): String = ""

    override fun close() = Unit
}
```

- [ ] **Step 2: Run the focused low-priority serialization test and verify it fails**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest.lowPriorityPlannedRangesAreSerialized
```

Expected: FAIL before implementation because additional low-priority jobs enter `prefetchRange()` while the first low-priority range is blocked, making `maxConcurrentPrefetches` greater than 1 and adding more than one range before `releaseLowPriority.countDown()`.

- [ ] **Step 3: Add planned range semaphores**

In `ReaderViewModel.kt`, add this import:

```kotlin
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
```

Near `plannedRangeScope`, add:

```kotlin
private val plannedRangeSemaphore = Semaphore(MAX_PLANNED_RANGE_CONCURRENCY)
private val lowPriorityPlannedRangeSemaphore = Semaphore(MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY)
```

In the companion object, add:

```kotlin
const val HIGH_PRIORITY_PLANNED_RANGE_MAX = 2
const val MAX_PLANNED_RANGE_CONCURRENCY = 2
const val MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY = 1
```

- [ ] **Step 4: Add a helper for gated planned range execution**

In `ReaderViewModel.kt`, add this method near `schedulePlannedRangePrefetches()`:

```kotlin
private suspend fun prefetchPlannedRangeWithLimits(
    session: ComicReaderSession,
    range: PlannedRemoteRange,
): Boolean =
    plannedRangeSemaphore.withPermit {
        if (range.priority > HIGH_PRIORITY_PLANNED_RANGE_MAX) {
            lowPriorityPlannedRangeSemaphore.withPermit {
                session.prefetchRange(range.start, range.endInclusive)
            }
        } else {
            session.prefetchRange(range.start, range.endInclusive)
        }
    }
```

- [ ] **Step 5: Use the helper in schedulePlannedRangePrefetches()**

Replace:

```kotlin
val stored = session.prefetchRange(range.start, range.endInclusive)
```

with:

```kotlin
ReaderDiagnosticLog.event(
    "planned_range_prefetch_start start=${range.start} end=${range.endInclusive} " +
        "pages=${range.pages} priority=${range.priority}",
)
val stored = prefetchPlannedRangeWithLimits(session, range)
```

Keep the existing `planned_range_prefetch_done` diagnostic after the call.

- [ ] **Step 6: Run the focused low-priority serialization test**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest.lowPriorityPlannedRangesAreSerialized
```

Expected: PASS.

- [ ] **Step 7: Add a test for the total planned range concurrency cap**

Add this test:

```kotlin
@Test
fun plannedRangePrefetchesRespectTotalConcurrencyLimit() = runTest(dispatcher) {
    val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
    val ioDispatcher = executor.asCoroutineDispatcher()
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val session = ConcurrencyTrackingPlannedRangeSession(
        pageCount = 8,
        plannedRangesByPage = mapOf(
            0 to listOf(
                PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1), priority = 0),
                PlannedRemoteRange(start = 200, endInclusive = 299, pages = listOf(2), priority = 1),
                PlannedRemoteRange(start = 300, endInclusive = 399, pages = listOf(3), priority = 2),
            ),
        ),
        blockingRange = 100L to 199L,
        firstBlockedRangeStarted = firstStarted,
        releaseBlockedRange = releaseFirst,
    )
    val viewModel = ReaderViewModel(
        openSession = { session },
        ioDispatcher = ioDispatcher,
    )
    try {
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            firstStarted.count == 0L && session.maxConcurrentPrefetches == 2
        }

        Thread.sleep(150)
        assertEquals(2, session.maxConcurrentPrefetches)
        assertEquals(
            listOf(100L to 199L, 200L to 299L),
            session.prefetchedRanges,
        )

        releaseFirst.countDown()
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            session.prefetchedRanges.contains(300L to 399L)
        }
        assertEquals(2, session.maxConcurrentPrefetches)
    } finally {
        executor.shutdownNow()
    }
}
```

- [ ] **Step 8: Run ReaderViewModel tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.ReaderViewModelTest
```

Expected: PASS.

- [ ] **Step 9: Commit Task 2**

Run:

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt
git commit -m "fix: limit planned range prefetch concurrency"
```

## Task 3: Protected Range Eviction Observation Only

**Files:**
- Modify: `docs/superpowers/plans/2026-05-13-phase-7-compatibility-hardening.md`

- [ ] **Step 1: Record the protected-range second-round design**

Under `Task 5: Phase 6B ADB Follow-Up`, add these checklist items:

```markdown
- [ ] If in-flight dedupe and prefetch concurrency limits still leave near-page evictions, pass protected byte ranges into `RangeWindowCache.store()`.
- [ ] Keep `RangeWindowCache` byte-oriented: callers provide protected `LongRange` values, and the cache never learns page indexes.
- [ ] Treat any window intersecting a protected byte range as protected during low-priority prefetch eviction.
- [ ] Do not protect low-priority prefetch windows from current-page read stores; current-page read stores may evict older windows when memory is exhausted.
```

- [ ] **Step 2: Commit the observation-only plan update**

Run:

```bash
git add docs/superpowers/plans/2026-05-13-phase-7-compatibility-hardening.md
git commit -m "docs: plan protected range cache follow-up"
```

## Task 4: Full Verification and ADB Evidence

**Files:**
- No source files.
- Pull phone logs to `/tmp/comicdav-phase7-range-prefetch-logs/` for local analysis.

- [ ] **Step 1: Run the full Android unit test suite**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build and install the debug APK**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug -PtargetAbi=arm64-v8a
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: Gradle build succeeds and `adb install` prints `Success`.

- [ ] **Step 3: Run manual sequential reading on device**

Open at least one large remote CBZ on stable Wi-Fi and sequentially swipe for at least 20 pages at normal reading pace.

Expected diagnostics:

```text
range_inflight_join appears at least once
prefetch_cancelled reason=select_page is 0 or near 0
planned_range_prefetch_start is less bursty than Phase 6B logs
page_not_ready likelyCause=extract_slow decreases compared with Phase 6B sample
```

- [ ] **Step 4: Pull new phone logs**

Run:

```bash
mkdir -p /tmp/comicdav-phase7-range-prefetch-logs
adb shell ls -lt /sdcard/comicdav_log
adb pull /sdcard/comicdav_log/<new-log-file>.txt /tmp/comicdav-phase7-range-prefetch-logs/
```

Replace `<new-log-file>.txt` with each log produced by the manual run.

- [ ] **Step 5: Summarize diagnostics**

Run:

```bash
awk '
/range_inflight_join/ {join++}
/range_cache_hit/ {hit++}
/range_cache_miss/ {miss++}
/range_prefetch_start/ {pfstart++}
/prefetch_cancelled reason=select_page/ {cancelSelect++}
/prefetch_failed/ {prefetchFailed++}
/planned_range_prefetch_failed/ {plannedFailed++}
/analysis page_not_ready/ {
  notready++
  if (match($0, /likelyCause=[^ ]+/)) cause[substr($0,RSTART,RLENGTH)]++
}
END {
  total=hit+miss
  rate=(total?hit*100/total:0)
  printf "range_inflight_join=%d\n", join
  printf "range_cache_hit=%d miss=%d hit_rate=%.1f%%\n", hit, miss, rate
  printf "range_prefetch_start=%d\n", pfstart
  printf "prefetch_cancelled_select=%d\n", cancelSelect
  printf "prefetch_failed=%d planned_failed=%d\n", prefetchFailed, plannedFailed
  printf "page_not_ready=%d\n", notready
  for (c in cause) printf "%s=%d\n", c, cause[c]
}
' /tmp/comicdav-phase7-range-prefetch-logs/*.txt
```

Expected: `range_inflight_join` is nonzero, `prefetch_cancelled_select` remains 0 or near 0, and failures remain 0. If `range_cache_hit` is still far below the 80% target or near-page evictions are visible, open a follow-up implementation plan for protected byte ranges.

- [ ] **Step 6: Commit verification notes if diagnostics are conclusive**

If the logs clearly show improvement, append a short dated note to `docs/superpowers/plans/2026-05-13-phase-7-compatibility-hardening.md` under Task 5 with the measured counters, then commit:

```bash
git add docs/superpowers/plans/2026-05-13-phase-7-compatibility-hardening.md
git commit -m "docs: record phase 7 range prefetch validation"
```

## Self-Review

- Spec coverage: Task 1 covers in-flight dedupe; Task 2 covers low-priority planned range concurrency limits; Task 3 records protected ranges as a second-round design; Task 4 covers automated and manual validation.
- Placeholder scan: no unresolved placeholders are used in implementation steps. The only replaceable token is `<new-log-file>.txt` in an adb command, and the step explicitly says how to replace it with files listed by `adb shell ls`.
- Type consistency: the plan consistently uses `InFlightRange`, `RegisteredFetch`, `CompletableDeferred<ByteArray>`, `Semaphore`, `HIGH_PRIORITY_PLANNED_RANGE_MAX`, `MAX_PLANNED_RANGE_CONCURRENCY`, and `MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY`.
