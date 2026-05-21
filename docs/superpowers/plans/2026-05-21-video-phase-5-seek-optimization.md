# Video Phase 5 Seek Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-configurable WebDAV video seek optimization with 2 MiB segment caching, in-flight coalescing, forward prefetch, and diagnostics.

**Architecture:** Keep HTTP protocol handling in `MuBoxVideoProxy`. Add `VideoSeekOptimizer` and a byte-aware `VideoRangeMemoryCache` under `video/proxy`, then pass per-stream `VideoProxySettings` from app settings through `VideoProxyManager`.

**Tech Stack:** Kotlin, Android DataStore Preferences, Jetpack Compose Material3, coroutines, JUnit4, Gradle `:app:testDebugUnitTest`.

---

## File Structure

- `app/src/main/java/com/example/comicdav/video/proxy/VideoProxySettings.kt`: User-facing proxy setting enums and immutable runtime settings.
- `app/src/main/java/com/example/comicdav/video/proxy/VideoProxyDiagnostics.kt`: Small diagnostics helper with OFF/SUMMARY/DETAIL gating and redacted stream ids.
- `app/src/main/java/com/example/comicdav/video/proxy/VideoRangeMemoryCache.kt`: Byte-aware, stream-scoped, 2 MiB segment LRU cache.
- `app/src/main/java/com/example/comicdav/video/proxy/VideoSeekOptimizer.kt`: Segment alignment, cache lookup, in-flight coalescing, foreground fetch, and prefetch scheduling.
- `app/src/main/java/com/example/comicdav/video/proxy/VideoStreamRequest.kt`: Add per-stream `VideoProxySettings`.
- `app/src/main/java/com/example/comicdav/video/proxy/MuBoxVideoProxy.kt`: Wire optimizer into bounded Range requests and clear optimizer state on unregister/close.
- `app/src/main/java/com/example/comicdav/video/proxy/VideoProxyManager.kt`: Accept settings per open call and register them with the stream.
- `app/src/main/java/com/example/comicdav/video/proxy/WebDavVideoPlaybackStarter.kt`: Thread settings through testable playback starter.
- `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`: Persist seek optimization, prefetch mode, and diagnostics mode.
- `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`: Add visible video settings controls.
- `app/src/main/java/com/example/comicdav/MainActivity.kt`: Convert `AppSettings` to `VideoProxySettings` and pass it when opening WebDAV video.
- `app/src/test/java/com/example/comicdav/video/proxy/VideoRangeMemoryCacheTest.kt`: Cache behavior tests.
- `app/src/test/java/com/example/comicdav/video/proxy/VideoSeekOptimizerTest.kt`: Optimizer behavior tests.
- `app/src/test/java/com/example/comicdav/video/proxy/MuBoxVideoProxyTest.kt`: Integration tests for bypass, cache reuse, cleanup.
- `app/src/test/java/com/example/comicdav/video/proxy/VideoProxyManagerTest.kt`: Settings propagation test.
- `app/src/test/java/com/example/comicdav/video/proxy/WebDavVideoPlaybackStarterTest.kt`: Default settings and close-on-failure compatibility.

## Parallelization

- Parallel Worker A owns proxy core only: `VideoProxySettings.kt`, `VideoProxyDiagnostics.kt`, `VideoRangeMemoryCache.kt`, `VideoSeekOptimizer.kt`, `VideoRangeMemoryCacheTest.kt`, `VideoSeekOptimizerTest.kt`.
- Parallel Worker B owns settings UI only: `AppSettingsStore.kt`, `SettingsScreen.kt`.
- Integration is a follow-up task after both workers return: `VideoStreamRequest.kt`, `MuBoxVideoProxy.kt`, `VideoProxyManager.kt`, `WebDavVideoPlaybackStarter.kt`, `MainActivity.kt`, and existing proxy tests.

### Task 1: Proxy Settings, Diagnostics, Cache

**Files:**
- Create: `app/src/main/java/com/example/comicdav/video/proxy/VideoProxySettings.kt`
- Create: `app/src/main/java/com/example/comicdav/video/proxy/VideoProxyDiagnostics.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/proxy/VideoRangeMemoryCache.kt`
- Test: `app/src/test/java/com/example/comicdav/video/proxy/VideoRangeMemoryCacheTest.kt`

- [ ] **Step 1: Write failing cache tests**

Create `VideoRangeMemoryCacheTest.kt`:

```kotlin
package com.example.comicdav.video.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoRangeMemoryCacheTest {
    @Test
    fun getSegmentReturnsStoredBytesAndUpdatesStats() {
        val cache = VideoRangeMemoryCache(maxBytes = 8)
        cache.putSegment("stream-1", segmentIndex = 0L, start = 0L, bytes = "abcd".toByteArray())

        val segment = cache.getSegment("stream-1", segmentIndex = 0L)

        assertEquals(0L, segment?.start)
        assertEquals(3L, segment?.endInclusive)
        assertArrayEquals("abcd".toByteArray(), segment?.bytes)
        assertEquals(4L, cache.totalBytes())
    }

    @Test
    fun putSegmentEvictsLeastRecentlyUsedSegmentsByByteCapacity() {
        val cache = VideoRangeMemoryCache(maxBytes = 8)
        cache.putSegment("stream-1", 0L, 0L, "aaaa".toByteArray())
        cache.putSegment("stream-1", 1L, 4L, "bbbb".toByteArray())
        cache.getSegment("stream-1", 0L)

        cache.putSegment("stream-1", 2L, 8L, "cccc".toByteArray())

        assertArrayEquals("aaaa".toByteArray(), cache.getSegment("stream-1", 0L)?.bytes)
        assertNull(cache.getSegment("stream-1", 1L))
        assertArrayEquals("cccc".toByteArray(), cache.getSegment("stream-1", 2L)?.bytes)
        assertEquals(8L, cache.totalBytes())
    }

    @Test
    fun removeStreamClearsOnlyThatStreamsSegments() {
        val cache = VideoRangeMemoryCache(maxBytes = 16)
        cache.putSegment("stream-1", 0L, 0L, "aaaa".toByteArray())
        cache.putSegment("stream-2", 0L, 0L, "bbbb".toByteArray())

        cache.removeStream("stream-1")

        assertNull(cache.getSegment("stream-1", 0L))
        assertArrayEquals("bbbb".toByteArray(), cache.getSegment("stream-2", 0L)?.bytes)
    }

    @Test
    fun oversizedSegmentIsRejected() {
        val cache = VideoRangeMemoryCache(maxBytes = 3)

        val stored = cache.putSegment("stream-1", 0L, 0L, "abcd".toByteArray())

        assertEquals(false, stored)
        assertNull(cache.getSegment("stream-1", 0L))
        assertEquals(0L, cache.totalBytes())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.proxy.VideoRangeMemoryCacheTest
```

Expected: FAIL because `VideoRangeMemoryCache` has no constructor with `maxBytes`, no `putSegment`, no `getSegment`, no `removeStream`, and no `totalBytes`.

- [ ] **Step 3: Implement settings, diagnostics, and cache**

Create `VideoProxySettings.kt`:

```kotlin
package com.example.comicdav.video.proxy

enum class VideoForwardPrefetchMode(val segmentCount: Int) {
    OFF(0),
    STANDARD(1),
    AGGRESSIVE(2),
}

enum class VideoProxyDiagnosticsMode {
    OFF,
    SUMMARY,
    DETAIL,
}

data class VideoProxySettings(
    val seekOptimizationEnabled: Boolean = true,
    val forwardPrefetchMode: VideoForwardPrefetchMode = VideoForwardPrefetchMode.STANDARD,
    val diagnosticsMode: VideoProxyDiagnosticsMode = VideoProxyDiagnosticsMode.OFF,
) {
    companion object {
        val DEFAULT = VideoProxySettings()
    }
}
```

Create `VideoProxyDiagnostics.kt`:

```kotlin
package com.example.comicdav.video.proxy

import java.security.MessageDigest

internal class VideoProxyDiagnostics(
    private val mode: VideoProxyDiagnosticsMode,
    private val sink: (String) -> Unit = { message -> System.err.println(message) },
) {
    fun summary(event: () -> String) {
        if (mode != VideoProxyDiagnosticsMode.OFF) sink("video_proxy ${event()}")
    }

    fun detail(event: () -> String) {
        if (mode == VideoProxyDiagnosticsMode.DETAIL) sink("video_proxy ${event()}")
    }

    fun streamId(raw: String): String = "stream:${shortHash(raw)}"

    private fun shortHash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
    }
}
```

Replace `VideoRangeMemoryCache.kt` with a byte-aware LRU implementation:

```kotlin
package com.example.comicdav.video.proxy

class VideoRangeMemoryCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<SegmentKey, Segment>(16, 0.75f, true)
    private var byteCount = 0L

    fun getSegment(streamId: String, segmentIndex: Long): Segment? = synchronized(lock) {
        entries[SegmentKey(streamId, segmentIndex)]?.copy()
    }

    fun putSegment(streamId: String, segmentIndex: Long, start: Long, bytes: ByteArray): Boolean = synchronized(lock) {
        if (bytes.size.toLong() > maxBytes) return false
        val key = SegmentKey(streamId, segmentIndex)
        entries.remove(key)?.let { byteCount -= it.bytes.size.toLong() }
        entries[key] = Segment(
            streamId = streamId,
            segmentIndex = segmentIndex,
            start = start,
            bytes = bytes.copyOf(),
        )
        byteCount += bytes.size.toLong()
        trimToSize()
        true
    }

    fun containsSegment(streamId: String, segmentIndex: Long): Boolean = synchronized(lock) {
        entries.containsKey(SegmentKey(streamId, segmentIndex))
    }

    fun removeStream(streamId: String) = synchronized(lock) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.streamId == streamId) {
                byteCount -= entry.value.bytes.size.toLong()
                iterator.remove()
            }
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        byteCount = 0L
    }

    fun totalBytes(): Long = synchronized(lock) { byteCount }

    fun segmentCount(): Int = synchronized(lock) { entries.size }

    private fun trimToSize() {
        val iterator = entries.iterator()
        while (byteCount > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            byteCount -= entry.value.bytes.size.toLong()
            iterator.remove()
        }
    }

    data class Segment(
        val streamId: String,
        val segmentIndex: Long,
        val start: Long,
        val bytes: ByteArray,
    ) {
        val endInclusive: Long get() = start + bytes.size - 1L

        fun slice(start: Long, endInclusive: Long): ByteArray {
            val from = (start - this.start).toInt()
            val toExclusive = (endInclusive - this.start + 1L).toInt()
            return bytes.copyOfRange(from, toExclusive)
        }

        fun copy(): Segment = copy(bytes = bytes.copyOf())

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Segment) return false
            return streamId == other.streamId &&
                segmentIndex == other.segmentIndex &&
                start == other.start &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = streamId.hashCode()
            result = 31 * result + segmentIndex.hashCode()
            result = 31 * result + start.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    private data class SegmentKey(val streamId: String, val segmentIndex: Long)

    companion object {
        const val DEFAULT_SEGMENT_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
    }
}
```

- [ ] **Step 4: Run cache tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.proxy.VideoRangeMemoryCacheTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/video/proxy/VideoProxySettings.kt \
    app/src/main/java/com/example/comicdav/video/proxy/VideoProxyDiagnostics.kt \
    app/src/main/java/com/example/comicdav/video/proxy/VideoRangeMemoryCache.kt \
    app/src/test/java/com/example/comicdav/video/proxy/VideoRangeMemoryCacheTest.kt
git commit -m "feat: add video range segment cache"
```

### Task 2: Video Seek Optimizer Core

**Files:**
- Create: `app/src/main/java/com/example/comicdav/video/proxy/VideoSeekOptimizer.kt`
- Test: `app/src/test/java/com/example/comicdav/video/proxy/VideoSeekOptimizerTest.kt`

- [ ] **Step 1: Write failing optimizer tests**

Create `VideoSeekOptimizerTest.kt` with tests for slicing, cache reuse, in-flight coalescing, and prefetch:

```kotlin
package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.network.WebDavStreamResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSeekOptimizerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun openRangeReturnsExactSliceFromFetchedSegments() = runTest {
        val bytes = ByteArray((VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES * 2L).toInt()) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope)

        val response = optimizer.openRangeStream(
            client = client,
            request = request(size = bytes.size.toLong()),
            totalSize = bytes.size.toLong(),
            start = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES - 2L,
            endInclusive = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES + 2L,
            settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF),
        )

        assertArrayEquals(
            bytes.copyOfRange(
                (VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES - 2L).toInt(),
                (VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES + 3L).toInt(),
            ),
            response.stream.readBytes(),
        )
        assertEquals(
            listOf(
                0L to VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES - 1L,
                VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES to VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES * 2L - 1L,
            ),
            client.openRangeCalls,
        )
    }

    @Test
    fun cachedSegmentAvoidsSecondRemoteFetch() = runTest {
        val bytes = ByteArray((VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES).toInt()) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope)
        val req = request(size = bytes.size.toLong())
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)

        optimizer.openRangeStream(client, req, bytes.size.toLong(), 0L, 15L, settings).close()
        val second = optimizer.openRangeStream(client, req, bytes.size.toLong(), 4L, 8L, settings)

        assertArrayEquals(bytes.copyOfRange(4, 9), second.stream.readBytes())
        assertEquals(1, client.openRangeCalls.size)
    }

    @Test
    fun concurrentSameSegmentRequestsShareOneRemoteFetch() = runTest {
        val bytes = ByteArray((VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES).toInt()) { (it % 251).toByte() }
        val gate = CompletableDeferred<Unit>()
        val client = BlockingRecordingClient(bytes, gate)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope)
        val req = request(size = bytes.size.toLong())

        val first = async(Dispatchers.IO) {
            optimizer.openRangeStream(client, req, bytes.size.toLong(), 0L, 7L, VideoProxySettings.DEFAULT).stream.readBytes()
        }
        val second = async(Dispatchers.IO) {
            optimizer.openRangeStream(client, req, bytes.size.toLong(), 8L, 15L, VideoProxySettings.DEFAULT).stream.readBytes()
        }
        while (client.started.get() == 0) Thread.sleep(10)
        gate.complete(Unit)

        listOf(first, second).awaitAll()
        assertEquals(1, client.openRangeCalls.size)
    }

    @Test
    fun standardPrefetchFetchesNextSegmentInBackground() = runTest {
        val bytes = ByteArray((VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES * 3L).toInt()) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope)

        optimizer.openRangeStream(
            client = client,
            request = request(size = bytes.size.toLong()),
            totalSize = bytes.size.toLong(),
            start = 0L,
            endInclusive = 15L,
            settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.STANDARD),
        ).close()

        eventually {
            assertEquals(
                listOf(
                    0L to VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES - 1L,
                    VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES to VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES * 2L - 1L,
                ),
                client.openRangeCalls,
            )
        }
    }

    private fun request(size: Long): VideoStreamRequest =
        VideoStreamRequest(
            streamId = "stream-1",
            accountId = "account-1",
            remotePath = "/movie.mp4",
            displayName = "movie.mp4",
            size = size,
            etag = null,
            lastModified = null,
            mimeType = "video/mp4",
            proxySettings = VideoProxySettings.DEFAULT,
        )

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 2_000
        var lastError: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                lastError = error
                Thread.sleep(20)
            }
        }
        throw lastError ?: AssertionError("condition was not met")
    }

    private open class RecordingClient(private val bytes: ByteArray) : WebDavClient {
        val openRangeCalls = mutableListOf<Pair<Long, Long>>()
        override suspend fun list(path: String): List<WebDavItem> = emptyList()
        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), null, null, true)
        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            bytes.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse {
            val end = requireNotNull(endInclusive)
            openRangeCalls += start to end
            val chunk = readRange(path, start, end)
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(chunk),
                statusCode = 206,
                contentLength = chunk.size.toLong(),
                contentRange = ContentRange(start, end, bytes.size.toLong()),
                contentType = "video/mp4",
                totalSize = bytes.size.toLong(),
                close = {},
            )
        }
        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("not used")
    }

    private class BlockingRecordingClient(
        bytes: ByteArray,
        private val gate: CompletableDeferred<Unit>,
    ) : RecordingClient(bytes) {
        val started = AtomicInteger(0)
        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse {
            started.incrementAndGet()
            gate.await()
            return super.openRangeStream(path, start, endInclusive)
        }
    }
}
```

- [ ] **Step 2: Run optimizer tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.proxy.VideoSeekOptimizerTest
```

Expected: FAIL because `VideoSeekOptimizer` and `VideoStreamRequest.proxySettings` do not exist.

- [ ] **Step 3: Implement `VideoSeekOptimizer`**

Implement public API:

```kotlin
internal class VideoSeekOptimizer(
    private val coroutineScope: CoroutineScope,
    private val cache: VideoRangeMemoryCache = VideoRangeMemoryCache(),
    private val segmentBytes: Long = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES,
) : Closeable {
    suspend fun openRangeStream(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
        settings: VideoProxySettings,
    ): WebDavStreamResponse

    fun removeStream(streamId: String)

    override fun close()
}
```

Required behavior:

- `segmentIndex = start / segmentBytes`.
- Segment start is `segmentIndex * segmentBytes`.
- Segment end is `(segmentStart + segmentBytes - 1).coerceAtMost(totalSize - 1)`.
- Use `cache.getSegment()` first.
- Use a `ConcurrentHashMap<SegmentKey, Deferred<Segment>>` or equivalent for in-flight fetches.
- Fetch a segment with `client.openRangeStream(request.remotePath, segmentStart, segmentEnd)`, read bytes, close response, validate byte count, then store.
- Assemble requested response bytes by slicing fetched segments into one `ByteArray`.
- Return `WebDavStreamResponse` with `statusCode = 206`, `contentLength = requestedBytes.size.toLong()`, `contentRange = ContentRange(start, endInclusive, totalSize)`, `contentType = request.mimeType`, `totalSize = totalSize`, and no-op close.
- Schedule prefetch using `coroutineScope.launch(Dispatchers.IO)` after foreground response bytes are assembled.
- `removeStream()` cancels in-flight and prefetch jobs for that stream and removes cache entries.

- [ ] **Step 4: Run cache and optimizer tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.proxy.VideoRangeMemoryCacheTest --tests com.example.comicdav.video.proxy.VideoSeekOptimizerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/video/proxy/VideoSeekOptimizer.kt \
    app/src/test/java/com/example/comicdav/video/proxy/VideoSeekOptimizerTest.kt
git commit -m "feat: add video seek optimizer core"
```

### Task 3: User-Visible Settings

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`

- [ ] **Step 1: Add settings fields and UI controls**

In `AppSettingsStore.kt`, import the proxy enums and extend `AppSettings`:

```kotlin
import com.example.comicdav.video.proxy.VideoForwardPrefetchMode
import com.example.comicdav.video.proxy.VideoProxyDiagnosticsMode

data class AppSettings(
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val readerLoggingMode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val autoPageEnabled: Boolean = false,
    val autoPageSpeedMillis: Int = 5_000,
    val screenRotationLockEnabled: Boolean = false,
    val volumeKeysTurnPagesEnabled: Boolean = false,
    val diskCacheLimitMb: Int = 1024,
    val webDavPrefetchPageCount: Int = 4,
    val libraryCoversEnabled: Boolean = true,
    val videoResumeEnabled: Boolean = true,
    val videoSeekOptimizationEnabled: Boolean = true,
    val videoForwardPrefetchMode: VideoForwardPrefetchMode = VideoForwardPrefetchMode.STANDARD,
    val videoProxyDiagnosticsMode: VideoProxyDiagnosticsMode = VideoProxyDiagnosticsMode.OFF,
) {
    val loggingEnabled: Boolean
        get() = readerLoggingMode != ReaderLoggingMode.OFF
}
```

Add DataStore keys:

```kotlin
val VIDEO_SEEK_OPTIMIZATION_ENABLED = booleanPreferencesKey("video_seek_optimization_enabled")
val VIDEO_FORWARD_PREFETCH_MODE = stringPreferencesKey("video_forward_prefetch_mode")
val VIDEO_PROXY_DIAGNOSTICS_MODE = stringPreferencesKey("video_proxy_diagnostics_mode")
```

Add update functions:

```kotlin
suspend fun updateVideoSeekOptimizationEnabled(enabled: Boolean) {
    dataStore.edit { preferences ->
        preferences[VIDEO_SEEK_OPTIMIZATION_ENABLED] = enabled
    }
}

suspend fun updateVideoForwardPrefetchMode(mode: VideoForwardPrefetchMode) {
    dataStore.edit { preferences ->
        preferences[VIDEO_FORWARD_PREFETCH_MODE] = mode.name
    }
}

suspend fun updateVideoProxyDiagnosticsMode(mode: VideoProxyDiagnosticsMode) {
    dataStore.edit { preferences ->
        preferences[VIDEO_PROXY_DIAGNOSTICS_MODE] = mode.name
    }
}
```

In `SettingsScreen.kt`, add callback parameters:

```kotlin
onVideoSeekOptimizationEnabledChange: (Boolean) -> Unit,
onVideoForwardPrefetchModeChange: (VideoForwardPrefetchMode) -> Unit,
onVideoProxyDiagnosticsModeChange: (VideoProxyDiagnosticsMode) -> Unit,
```

Add rows in the `视频` group after resume:

```kotlin
SwitchRow(
    title = "WebDAV 视频 seek 优化",
    subtitle = "缓存小段视频并合并重复 seek 请求",
    checked = settings.videoSeekOptimizationEnabled,
    onCheckedChange = onVideoSeekOptimizationEnabledChange,
)
DropdownRow(
    title = "向前预读",
    selected = settings.videoForwardPrefetchMode,
    options = VideoForwardPrefetchMode.entries,
    label = VideoForwardPrefetchMode::label,
    onSelected = onVideoForwardPrefetchModeChange,
)
DropdownRow(
    title = "视频代理诊断日志",
    selected = settings.videoProxyDiagnosticsMode,
    options = VideoProxyDiagnosticsMode.entries,
    label = VideoProxyDiagnosticsMode::label,
    onSelected = onVideoProxyDiagnosticsModeChange,
)
```

Add label helpers:

```kotlin
private fun VideoForwardPrefetchMode.label(): String =
    when (this) {
        VideoForwardPrefetchMode.OFF -> "关闭"
        VideoForwardPrefetchMode.STANDARD -> "标准"
        VideoForwardPrefetchMode.AGGRESSIVE -> "积极"
    }

private fun VideoProxyDiagnosticsMode.label(): String =
    when (this) {
        VideoProxyDiagnosticsMode.OFF -> "关闭"
        VideoProxyDiagnosticsMode.SUMMARY -> "摘要"
        VideoProxyDiagnosticsMode.DETAIL -> "详细"
    }
```

- [ ] **Step 2: Run unit-test compilation**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.proxy.MuBoxVideoProxyTest
```

Expected before MainActivity wiring: Kotlin compilation may fail because `SettingsScreen` call sites need new callback parameters. If it fails for that reason, leave integration for Task 4.

- [ ] **Step 3: Commit only if compilation passes**

If compilation passes:

```bash
git add app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt \
    app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt
git commit -m "feat: add video proxy settings UI"
```

If compilation fails only because `MainActivity` call sites need wiring, do not commit yet; Task 4 will commit settings with integration.

### Task 4: Proxy Integration

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/proxy/VideoStreamRequest.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/proxy/MuBoxVideoProxy.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/proxy/VideoProxyManager.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/proxy/WebDavVideoPlaybackStarter.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/proxy/MuBoxVideoProxyTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/proxy/VideoProxyManagerTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/proxy/WebDavVideoPlaybackStarterTest.kt`

- [ ] **Step 1: Write failing proxy integration tests**

Add to `MuBoxVideoProxyTest`:

```kotlin
@Test
fun repeatedRangeRequestUsesCacheWhenSeekOptimizationIsEnabled() = runTest {
    val client = RecordingClient("0123456789".toByteArray())
    val url = startProxy(
        client = client,
        size = 10L,
        proxySettings = VideoProxySettings.DEFAULT.copy(
            seekOptimizationEnabled = true,
            forwardPrefetchMode = VideoForwardPrefetchMode.OFF,
        ),
    )

    assertArrayEquals("012".toByteArray(), httpRequest(url, method = "GET", range = "bytes=0-2").body)
    assertArrayEquals("123".toByteArray(), httpRequest(url, method = "GET", range = "bytes=1-3").body)

    assertEquals(listOf(0L to 9L), client.openRangeCalls)
}

@Test
fun rangeRequestBypassesOptimizerWhenSeekOptimizationIsDisabled() = runTest {
    val client = RecordingClient("0123456789".toByteArray())
    val url = startProxy(
        client = client,
        size = 10L,
        proxySettings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false),
    )

    httpRequest(url, method = "GET", range = "bytes=0-2")
    httpRequest(url, method = "GET", range = "bytes=1-3")

    assertEquals(listOf(0L to 2L, 1L to 3L), client.openRangeCalls)
}
```

Update `startProxy()` helpers to accept `proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT` and pass it to `WebDavVideoOpenRequest` registration through `MuBoxVideoProxy.register()`.

Add to `VideoProxyManagerTest` a settings propagation test that opens with `seekOptimizationEnabled = false`, makes two overlapping requests, and asserts the remote server receives two separate Range requests.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.proxy.MuBoxVideoProxyTest --tests com.example.comicdav.video.proxy.VideoProxyManagerTest --tests com.example.comicdav.video.proxy.WebDavVideoPlaybackStarterTest
```

Expected: FAIL because settings are not threaded and `MuBoxVideoProxy` does not use `VideoSeekOptimizer`.

- [ ] **Step 3: Wire settings through stream registration**

Update `VideoStreamRequest`:

```kotlin
data class VideoStreamRequest(
    val streamId: String,
    val accountId: String,
    val remotePath: String,
    val displayName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val mimeType: String?,
    val proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
)
```

Do not add proxy settings to `WebDavVideoOpenRequest`. Update `MuBoxVideoProxy.register()` so settings are passed as proxy-only parameters:

```kotlin
fun register(
    request: WebDavVideoOpenRequest,
    proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
): String =
    register(request, proxySettings) {
        clientProvider(request.accountId)
    }

fun register(
    request: WebDavVideoOpenRequest,
    proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    openClient: suspend () -> WebDavClient?,
): String {
    val streamId = nextId.getAndIncrement().toString()
    registry.put(
        streamId,
        RegisteredVideoStream(
            request = VideoStreamRequest(
                streamId = streamId,
                accountId = request.accountId,
                remotePath = request.remotePath,
                displayName = request.displayName,
                size = request.size,
                etag = request.etag,
                lastModified = request.lastModified,
                mimeType = request.mimeType,
                proxySettings = proxySettings,
            ),
            openClient = openClient,
        ),
    )
    return "$baseUrl/stream/$streamId/${request.displayName.toUrlPathSegment()}"
}
```

- [ ] **Step 4: Wire optimizer into `MuBoxVideoProxy`**

Add field:

```kotlin
private val seekOptimizer = VideoSeekOptimizer(coroutineScope = coroutineScope)
```

In `handleGet()`, replace the direct range open with:

```kotlin
val response = try {
    if (range == null) {
        client.openFullStream(request.remotePath)
    } else if (request.proxySettings.seekOptimizationEnabled) {
        runCatchingCancellable {
            seekOptimizer.openRangeStream(
                client = client,
                request = request,
                totalSize = info.size,
                start = range.start,
                endInclusive = range.endInclusive,
                settings = request.proxySettings,
            )
        }.getOrElse { error ->
            logProxyFailure("GET optimized stream", request, error)
            client.openRangeStream(request.remotePath, range.start, range.endInclusive)
        }
    } else {
        client.openRangeStream(request.remotePath, range.start, range.endInclusive)
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    logProxyFailure("GET stream", request, error)
    writeResponse(output, 502, emptyMap(), null)
    return
}
```

Update `unregister()`:

```kotlin
fun unregister(streamId: String): Boolean {
    seekOptimizer.removeStream(streamId)
    return registry.remove(streamId) != null
}
```

Update `close()`:

```kotlin
seekOptimizer.close()
```

- [ ] **Step 5: Wire app settings**

Update `VideoProxyManager.open()`:

```kotlin
suspend fun open(
    request: WebDavVideoOpenRequest,
    account: SavedWebDavAccount,
    proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
): ProxySession
```

Thread `proxySettings` into each `sessionProxy.register()` call:

```kotlin
val url = sessionProxy.register(request, proxySettings) {
    accountSnapshot.client()
}
registeredUrls += url
val subtitleUrls = request.subtitles.map { subtitle ->
    sessionProxy.register(
        request = subtitle.asStreamRequest(accountId = request.accountId),
        proxySettings = proxySettings,
    ) {
        accountSnapshot.client()
    }.also { registeredUrls += it }
}
```

Update `WebDavVideoPlaybackStarter`:

```kotlin
internal suspend fun startWebDavVideoPlayback(
    request: WebDavVideoOpenRequest,
    account: SavedWebDavAccount,
    proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    openProxy: suspend (WebDavVideoOpenRequest, SavedWebDavAccount, VideoProxySettings) -> ProxySession = VideoProxyManager::open,
    closeProxy: (String) -> Unit = VideoProxyManager::close,
    startPlayback: (ProxySession) -> Unit,
)
```

Update `MainActivity` to pass:

```kotlin
proxySettings = VideoProxySettings(
    seekOptimizationEnabled = appSettings.videoSeekOptimizationEnabled,
    forwardPrefetchMode = appSettings.videoForwardPrefetchMode,
    diagnosticsMode = appSettings.videoProxyDiagnosticsMode,
)
```

Update the `SettingsScreen` call site with the new callbacks:

```kotlin
onVideoSeekOptimizationEnabledChange = { value ->
    scope.launch { appSettingsStore.updateVideoSeekOptimizationEnabled(value) }
},
onVideoForwardPrefetchModeChange = { value ->
    scope.launch { appSettingsStore.updateVideoForwardPrefetchMode(value) }
},
onVideoProxyDiagnosticsModeChange = { value ->
    scope.launch { appSettingsStore.updateVideoProxyDiagnosticsMode(value) }
},
```

- [ ] **Step 6: Run integration tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.proxy.MuBoxVideoProxyTest --tests com.example.comicdav.video.proxy.VideoProxyManagerTest --tests com.example.comicdav.video.proxy.WebDavVideoPlaybackStarterTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/comicdav/video/proxy/VideoStreamRequest.kt \
    app/src/main/java/com/example/comicdav/video/proxy/MuBoxVideoProxy.kt \
    app/src/main/java/com/example/comicdav/video/proxy/VideoProxyManager.kt \
    app/src/main/java/com/example/comicdav/video/proxy/WebDavVideoPlaybackStarter.kt \
    app/src/main/java/com/example/comicdav/MainActivity.kt \
    app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt \
    app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt \
    app/src/test/java/com/example/comicdav/video/proxy/MuBoxVideoProxyTest.kt \
    app/src/test/java/com/example/comicdav/video/proxy/VideoProxyManagerTest.kt \
    app/src/test/java/com/example/comicdav/video/proxy/WebDavVideoPlaybackStarterTest.kt
git commit -m "feat: wire video seek optimization settings"
```

### Task 5: Final Verification

**Files:**
- No new production files unless tests expose a required fix.

- [ ] **Step 1: Run all unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Review diff against spec**

Run:

```bash
git diff master...HEAD --stat
git diff master...HEAD -- app/src/main/java/com/example/comicdav/video/proxy app/src/main/java/com/example/comicdav/data app/src/main/java/com/example/comicdav/feature/settings app/src/main/java/com/example/comicdav/MainActivity.kt
```

Confirm every requirement in `docs/superpowers/specs/2026-05-21-video-phase-5-seek-optimization-design.md` is represented by code or tests.

- [ ] **Step 3: Leave the branch clean**

Run:

```bash
git status --short
```

Expected after all implementation commits: no unstaged or staged changes.
