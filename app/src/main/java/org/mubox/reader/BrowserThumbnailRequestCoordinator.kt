package org.mubox.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Coalesces browser thumbnail work without tying extraction lifecycle to one
 * short-lived Compose waiter.
 */
internal class BrowserThumbnailRequestCoordinator(
    private val scope: CoroutineScope,
    maxParallelism: Int = 2,
    private val orphanGracePeriodMillis: Long = 500L,
) {
    private class Request(
        val deferred: Deferred<String?>,
        var waiterCount: Int,
        var orphanCancellation: Job? = null,
    )

    private val requestLock = Any()
    private val requestsInFlight = mutableMapOf<String, Request>()
    private val extractionSemaphore = Semaphore(maxParallelism)

    init {
        require(maxParallelism > 0)
        require(orphanGracePeriodMillis >= 0L)
    }

    suspend fun request(
        stableKey: String,
        extract: suspend () -> String?,
    ): String? {
        var shouldStartRequest = false
        val request = synchronized(requestLock) {
            requestsInFlight[stableKey]?.also { existingRequest ->
                existingRequest.waiterCount += 1
                existingRequest.orphanCancellation?.cancel()
                existingRequest.orphanCancellation = null
            } ?: run {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        extractionSemaphore.withPermit { extract() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                }
                Request(
                    deferred = deferred,
                    waiterCount = 1,
                ).also { createdRequest ->
                    requestsInFlight[stableKey] = createdRequest
                    shouldStartRequest = true
                }
            }
        }
        if (shouldStartRequest) {
            request.deferred.start()
        }
        return try {
            request.deferred.await()
        } finally {
            releaseWaiter(stableKey, request)
        }
    }

    private fun releaseWaiter(stableKey: String, request: Request) {
        synchronized(requestLock) {
            if (requestsInFlight[stableKey] !== request) return
            request.waiterCount = (request.waiterCount - 1).coerceAtLeast(0)
            if (request.waiterCount > 0) return
            if (request.deferred.isCompleted) {
                requestsInFlight.remove(stableKey)
                return
            }
            request.orphanCancellation = scope.launch {
                delay(orphanGracePeriodMillis)
                val orphanedRequest = synchronized(requestLock) {
                    requestsInFlight[stableKey]
                        ?.takeIf { it === request && it.waiterCount == 0 }
                        ?.also { requestsInFlight.remove(stableKey) }
                }
                orphanedRequest?.deferred?.cancel()
            }
        }
    }
}
