package org.mubox.reader.network

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.mubox.reader.core.ports.RangeProvider
import org.mubox.reader.core.remote.WebDavClient
import kotlinx.coroutines.runBlocking

/**
 * Thin WebDAV transport for the native comic range engine.
 *
 * Rust owns caching, read-ahead, in-flight request coalescing and protected eviction. This class
 * only streams an exact network miss into native-owned memory and adapts cancellation to OkHttp.
 */
class WebDavRangeProvider(
    private val client: WebDavClient,
    private val path: String,
    private val size: Long,
) : RangeProvider {
    private val lock = Any()
    private val activeRequests = mutableMapOf<Long, ActiveRangeFetch>()
    private val cancelledBeforeRegistration = linkedSetOf<Long>()
    private val recentlyCompletedRequests = linkedSetOf<Long>()
    private var closed = false

    override fun fetchRangeInto(
        fileId: Long,
        requestId: Long,
        start: Long,
        endInclusive: Long,
        target: ByteBuffer,
    ): Int {
        require(requestId > 0L) { "Range request id must be positive" }
        require(start >= 0L && endInclusive >= start) { "Invalid range $start-$endInclusive" }
        require(endInclusive < size) { "Range end must be smaller than the remote file" }
        require(target.isDirect && !target.isReadOnly) { "Range target must be writable native memory" }
        val expectedBytes = rangeLength(start, endInclusive)
        require(expectedBytes <= Int.MAX_VALUE.toLong()) { "Range is too large for direct transport" }
        require(
            target.position() == 0 &&
                target.limit() == expectedBytes.toInt() &&
                target.capacity() == expectedBytes.toInt()
        ) { "Range target has the wrong bounds" }

        val fetch = synchronized(lock) {
            checkOpenLocked()
            check(activeRequests[requestId] == null) { "Duplicate range request id: $requestId" }
            if (cancelledBeforeRegistration.remove(requestId)) {
                throw CancellationException("range request cancelled")
            }
            recentlyCompletedRequests.remove(requestId)
            ActiveRangeFetch().also { activeRequests[requestId] = it }
        }
        return try {
            val written = readNetworkRangeInto(
                start = start,
                endInclusive = endInclusive,
                target = target,
                fetch = fetch,
            )
            fetch.throwIfCancelled()
            written
        } catch (error: Throwable) {
            fetch.throwIfCancelled(error)
            throw error
        } finally {
            synchronized(lock) {
                activeRequests.remove(requestId, fetch)
                cancelledBeforeRegistration.remove(requestId)
                rememberBoundedRequestIdLocked(recentlyCompletedRequests, requestId)
            }
            fetch.finish()
        }
    }

    override fun cancelRangeRequest(requestId: Long) {
        val fetch = synchronized(lock) {
            activeRequests[requestId] ?: run {
                // A native cancellation can race with the narrow window after this callback
                // returned but before Rust published the response. Do not turn that late
                // cancellation into a tombstone for a request that already completed.
                if (requestId !in recentlyCompletedRequests) {
                    rememberBoundedRequestIdLocked(cancelledBeforeRegistration, requestId)
                }
                null
            }
        }
        fetch?.cancel()
    }

    override fun close() {
        cancelAll(closeProvider = true)
    }

    private fun readNetworkRangeInto(
        start: Long,
        endInclusive: Long,
        target: ByteBuffer,
        fetch: ActiveRangeFetch,
    ): Int = runBlocking {
        val response = client.openRangeStream(
            path = path,
            start = start,
            endInclusive = endInclusive,
            registerCancellation = fetch::registerCancellation,
        )
        try {
            val initialPosition = target.position()
            while (target.hasRemaining()) {
                fetch.throwIfCancelled()
                var count: Int
                do {
                    count = response.readInto(target)
                } while (count == 0)
                if (count < 0) {
                    throw IOException(
                        "Invalid range response length: " +
                            "start=$start end=$endInclusive expected=${target.limit() - initialPosition} " +
                            "actual=${target.position() - initialPosition}",
                    )
                }
            }
            fetch.throwIfCancelled()
            val overflow = ByteBuffer.allocateDirect(1)
            var overflowCount: Int
            do {
                overflowCount = response.readInto(overflow)
            } while (overflowCount == 0)
            if (overflowCount > 0) {
                throw IOException(
                    "Invalid range response length: start=$start end=$endInclusive " +
                        "expected=${target.limit() - initialPosition} actual>expected",
                )
            }
            target.position() - initialPosition
        } finally {
            response.close()
        }
    }

    private fun cancelAll(closeProvider: Boolean) {
        val active = synchronized(lock) {
            if (closeProvider) {
                closed = true
                cancelledBeforeRegistration.clear()
                recentlyCompletedRequests.clear()
            }
            activeRequests.values.toList()
        }
        active.forEach(ActiveRangeFetch::cancel)
    }

    private fun checkOpenLocked() {
        if (closed) throw CancellationException("range provider closed")
    }

    private fun rangeLength(start: Long, endInclusive: Long): Long {
        require(start >= 0L && endInclusive >= start) { "Invalid range $start-$endInclusive" }
        return (endInclusive - start + 1L).also { length ->
            require(length > 0L) { "Range length overflow" }
        }
    }

    private fun rememberBoundedRequestIdLocked(target: LinkedHashSet<Long>, requestId: Long) {
        target.remove(requestId)
        target.add(requestId)
        while (target.size > MAX_REQUEST_TOMBSTONES) {
            val iterator = target.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val MAX_REQUEST_TOMBSTONES = 4_096
    }
}

private class ActiveRangeFetch {
    private val lock = Any()
    private var cancellation: Closeable? = null
    private var cancelled = false
    private var finished = false

    fun registerCancellation(closeable: Closeable) {
        val closeImmediately = synchronized(lock) {
            if (cancelled || finished) {
                true
            } else {
                cancellation = closeable
                false
            }
        }
        if (closeImmediately) runCatching { closeable.close() }
    }

    fun cancel() {
        val closeable = synchronized(lock) {
            if (cancelled || finished) return
            cancelled = true
            cancellation.also { cancellation = null }
        }
        closeable?.let { runCatching { it.close() } }
    }

    fun finish() {
        synchronized(lock) {
            finished = true
            cancellation = null
        }
    }

    fun throwIfCancelled(cause: Throwable? = null) {
        if (synchronized(lock) { cancelled }) {
            throw CancellationException("range request cancelled").also { cancellation ->
                if (cause != null && cause !== cancellation) {
                    cancellation.initCause(cause)
                }
            }
        }
    }
}
