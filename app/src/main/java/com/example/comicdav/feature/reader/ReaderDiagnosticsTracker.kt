package com.example.comicdav.feature.reader

internal class ReaderDiagnosticsTracker(
    private val elapsedRealtimeMs: () -> Long,
) {
    private val lock = Any()
    private val pageLoadTimings = mutableMapOf<Int, PageLoadDiagnostic>()
    private val prefetchDiagnostics = mutableMapOf<Int, PrefetchDiagnostic>()
    private val pageWaits = mutableMapOf<Int, PageWaitDiagnostic>()
    private val imageLoadStarts = mutableMapOf<Int, Long>()
    private var readerOpenStartedAtMs: Long? = null
    private var remoteOpenDurationMs: Long? = null
    private var sessionInitialPageMs: Long? = null
    private var firstImageAnalysisLogged = false

    fun reset() {
        synchronized(lock) {
            pageLoadTimings.clear()
            prefetchDiagnostics.clear()
            pageWaits.clear()
            imageLoadStarts.clear()
            readerOpenStartedAtMs = elapsedRealtimeMs()
            remoteOpenDurationMs = null
            sessionInitialPageMs = null
            firstImageAnalysisLogged = false
        }
    }

    fun recordRemoteOpenDuration(durationMs: Long) {
        synchronized(lock) {
            remoteOpenDurationMs = durationMs
        }
    }

    fun recordPageDemand(pageIndex: Int, source: String) {
        val demandAtMs = elapsedRealtimeMs()
        synchronized(lock) {
            if (pageWaits.containsKey(pageIndex)) return
            val prefetch = prefetchDiagnostics[pageIndex]
            pageWaits[pageIndex] = PageWaitDiagnostic(
                startedAtMs = demandAtMs,
                source = source,
                wasPrefetchPlanned = prefetch?.plannedAtMs != null && prefetch.plannedAtMs <= demandAtMs,
                prefetchStartedBeforeDemand = prefetch?.startedAtMs != null && prefetch.startedAtMs <= demandAtMs,
            )
        }
    }

    fun recordImageLoadStarted(pageIndex: Int) {
        val startedAtMs = elapsedRealtimeMs()
        synchronized(lock) {
            imageLoadStarts[pageIndex] = startedAtMs
        }
    }

    fun imageRenderDuration(pageIndex: Int, completedAtMs: Long): Long? =
        synchronized(lock) {
            imageLoadStarts[pageIndex]?.let { (completedAtMs - it).coerceAtLeast(0L) }
        }

    fun recordPageLoadTiming(
        pageIndex: Int,
        reason: String,
        cacheHit: Boolean,
        loadStartedAtMs: Long,
        fileReadyAtMs: Long,
        extractMs: Long,
        fileSize: Long,
    ) {
        synchronized(lock) {
            pageLoadTimings[pageIndex] = PageLoadDiagnostic(
                reason = reason,
                cacheHit = cacheHit,
                loadStartedAtMs = loadStartedAtMs,
                fileReadyAtMs = fileReadyAtMs,
                extractMs = extractMs,
                fileSize = fileSize,
            )
            if (reason == LOAD_REASON_INITIAL && sessionInitialPageMs == null) {
                val openStartedAtMs = readerOpenStartedAtMs ?: loadStartedAtMs
                sessionInitialPageMs = (fileReadyAtMs - openStartedAtMs).coerceAtLeast(0L)
            }
        }
    }

    fun markPrefetchPlanned(pages: List<Int>) {
        val plannedAtMs = elapsedRealtimeMs()
        synchronized(lock) {
            pages.forEach { page ->
                prefetchDiagnostics[page] = PrefetchDiagnostic(plannedAtMs = plannedAtMs)
            }
        }
    }

    fun markPrefetchStarted(pageIndex: Int) {
        val startedAtMs = elapsedRealtimeMs()
        synchronized(lock) {
            val current = prefetchDiagnostics[pageIndex] ?: PrefetchDiagnostic(plannedAtMs = startedAtMs)
            prefetchDiagnostics[pageIndex] = current.copy(startedAtMs = startedAtMs)
        }
    }

    fun markPrefetchCompleted(pageIndex: Int) {
        val completedAtMs = elapsedRealtimeMs()
        synchronized(lock) {
            val current = prefetchDiagnostics[pageIndex] ?: PrefetchDiagnostic(plannedAtMs = completedAtMs)
            prefetchDiagnostics[pageIndex] = current.copy(completedAtMs = completedAtMs)
        }
    }

    fun markPrefetchCancelled(pages: List<Int>) {
        val cancelledAtMs = elapsedRealtimeMs()
        synchronized(lock) {
            pages.forEach { page ->
                val diagnostic = prefetchDiagnostics[page]
                if (diagnostic != null && diagnostic.completedAtMs == null && diagnostic.cancelledAtMs == null) {
                    prefetchDiagnostics[page] = diagnostic.copy(cancelledAtMs = cancelledAtMs)
                }
            }
        }
    }

    fun firstImageAnalysisIfNeeded(pageIndex: Int, completedAtMs: Long, imageRenderMs: Long?): String? =
        synchronized(lock) {
            if (firstImageAnalysisLogged) return null
            firstImageAnalysisLogged = true
            val openStartedAtMs = readerOpenStartedAtMs ?: completedAtMs
            val pageLoad = pageLoadTimings[pageIndex]
            formatFirstImageAnalysis(
                FirstImageTiming(
                    page = pageIndex,
                    totalMs = (completedAtMs - openStartedAtMs).coerceAtLeast(0L),
                    remoteOpenMs = remoteOpenDurationMs,
                    sessionInitialPageMs = sessionInitialPageMs,
                    pageExtractMs = pageLoad?.extractMs,
                    imageRenderMs = imageRenderMs,
                    cacheHit = pageLoad?.cacheHit ?: false,
                ),
            )
        }

    fun pageNotReadyAnalysisIfNeeded(pageIndex: Int, completedAtMs: Long, imageRenderMs: Long?): String? =
        synchronized(lock) {
            val wait = pageWaits.remove(pageIndex) ?: return null
            val prefetch = prefetchDiagnostics[pageIndex]
            val pageLoad = pageLoadTimings[pageIndex]
            formatPageNotReadyAnalysis(
                PageNotReadyTiming(
                    page = pageIndex,
                    waitMs = (completedAtMs - wait.startedAtMs).coerceAtLeast(0L),
                    wasPrefetchPlanned = wait.wasPrefetchPlanned,
                    wasPrefetchCancelled = prefetch?.cancelledAtMs != null && prefetch.completedAtMs == null,
                    prefetchStartedBeforeDemand = wait.prefetchStartedBeforeDemand,
                    queueOrWaitMs = pageLoad?.queueOrWaitMs,
                    extractMs = pageLoad?.extractMs,
                    imageRenderMs = imageRenderMs,
                ),
            )
        }

    fun localSessionSummary(): LocalSessionPerformanceSummary? =
        synchronized(lock) {
            val loadedPages = pageLoadTimings.entries.toList()
            if (loadedPages.isEmpty()) return@synchronized null

            val slowestPage = loadedPages.maxByOrNull { (_, timing) -> timing.durationMs }
                ?: return@synchronized null
            LocalSessionPerformanceSummary(
                pagesLoaded = loadedPages.size,
                cacheHits = loadedPages.count { (_, timing) -> timing.cacheHit },
                cacheMisses = loadedPages.count { (_, timing) -> !timing.cacheHit },
                totalOutputBytes = loadedPages.sumOf { (_, timing) -> timing.fileSize },
                largestOutputBytes = loadedPages.maxOf { (_, timing) -> timing.fileSize },
                slowestPage = slowestPage.key,
                slowestPageMs = slowestPage.value.durationMs,
                slowestPageReason = slowestPage.value.performanceReason,
                slowestPageExtractMs = slowestPage.value.extractMs,
                slowestPageQueueOrWaitMs = slowestPage.value.queueOrWaitMs,
            )
        }

    private data class PageLoadDiagnostic(
        val reason: String,
        val cacheHit: Boolean,
        val loadStartedAtMs: Long,
        val fileReadyAtMs: Long,
        val extractMs: Long,
        val fileSize: Long,
    ) {
        val durationMs: Long
            get() = (fileReadyAtMs - loadStartedAtMs).coerceAtLeast(0L)

        val queueOrWaitMs: Long
            get() = (durationMs - extractMs).coerceAtLeast(0L)

        val performanceReason: String
            get() = when {
                cacheHit -> PERFORMANCE_REASON_CACHE_READ
                queueOrWaitMs > extractMs -> PERFORMANCE_REASON_QUEUE_WAIT
                extractMs > 0L -> PERFORMANCE_REASON_EXTRACT
                else -> PERFORMANCE_REASON_DECODE_RENDER
            }
    }

    private data class PrefetchDiagnostic(
        val plannedAtMs: Long,
        val startedAtMs: Long? = null,
        val completedAtMs: Long? = null,
        val cancelledAtMs: Long? = null,
    )

    private data class PageWaitDiagnostic(
        val startedAtMs: Long,
        val source: String,
        val wasPrefetchPlanned: Boolean,
        val prefetchStartedBeforeDemand: Boolean,
    )

    private companion object {
        const val LOAD_REASON_INITIAL = "initial"
        const val PERFORMANCE_REASON_CACHE_READ = "cache-read"
        const val PERFORMANCE_REASON_QUEUE_WAIT = "queue-wait"
        const val PERFORMANCE_REASON_EXTRACT = "extract"
        const val PERFORMANCE_REASON_DECODE_RENDER = "decode-render"
    }
}

internal data class LocalSessionPerformanceSummary(
    val pagesLoaded: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val totalOutputBytes: Long,
    val largestOutputBytes: Long,
    val slowestPage: Int,
    val slowestPageMs: Long,
    val slowestPageReason: String,
    val slowestPageExtractMs: Long = 0L,
    val slowestPageQueueOrWaitMs: Long = 0L,
)
