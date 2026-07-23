package com.example.comicdav.feature.reader

import com.example.comicdav.core.ports.ComicReaderSession
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ReaderSessionDescriptor(
    val generation: Int,
    val cacheDir: File,
    val comicKey: String?,
    val pageCacheKey: String,
    val readerKey: String,
    val transientPageKey: String,
)

internal data class ActiveReaderSession(
    val session: ComicReaderSession,
    val descriptor: ReaderSessionDescriptor,
)

internal interface ReaderSessionGenerationGate {
    fun isCurrent(generation: Int): Boolean

    suspend fun <T> withSessionLock(action: () -> T): T
}

/**
 * Owns the native reader session lifetime and the generation used to reject stale work.
 *
 * Page loading and prefetching remain separate concerns. They receive immutable descriptors
 * and use [ReaderSessionGenerationGate] when they need to serialize native session access.
 */
internal class ReaderSessionCoordinator(
    ioDispatcher: CoroutineDispatcher,
    private val clearTransientPages: (cacheDir: File, readerKey: String) -> Unit =
        ReaderPageCache::clearTransientPages,
) : ReaderSessionGenerationGate {
    private val sessionMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var activeGeneration = 0
    private var descriptor: ReaderSessionDescriptor? = null
    private var session: ComicReaderSession? = null
    private var remoteOpenJob: Job? = null
    private var viewportJob: Job? = null

    val generation: Int
        get() = activeGeneration

    val activeSession: ActiveReaderSession?
        get() {
            val activeSession = session ?: return null
            val activeDescriptor = descriptor ?: return null
            return ActiveReaderSession(activeSession, activeDescriptor)
        }

    val currentComicKey: String?
        get() = descriptor?.comicKey

    fun beginOpening(
        cacheDir: File,
        comicKey: String?,
        pageCacheKey: String,
        readerKeyBase: String,
        expectedGeneration: Int = generation,
    ): ReaderSessionDescriptor? {
        if (!isCurrent(expectedGeneration)) return null
        return ReaderSessionDescriptor(
            generation = expectedGeneration,
            cacheDir = cacheDir,
            comicKey = comicKey,
            pageCacheKey = pageCacheKey,
            readerKey = readerInstanceKey(readerKeyBase, expectedGeneration),
            transientPageKey = readerInstanceKey(pageCacheKey, expectedGeneration),
        ).also { descriptor = it }
    }

    /** Activates [openedSession], or asynchronously closes it when its open is already stale. */
    fun activate(
        opening: ReaderSessionDescriptor,
        openedSession: ComicReaderSession,
    ): Boolean {
        if (!isCurrent(opening.generation) || descriptor !== opening) {
            closeSessionAsync(openedSession)
            return false
        }
        session = openedSession
        return true
    }

    fun trackRemoteOpen(job: Job) {
        remoteOpenJob = job
    }

    fun clearRemoteOpen(job: Job?) {
        if (job != null && remoteOpenJob === job) {
            remoteOpenJob = null
        }
    }

    fun replaceViewportJob(jobFactory: () -> Job) {
        viewportJob?.cancel()
        viewportJob = jobFactory()
    }

    /**
     * Closes the current generation. The callback only coordinates work owned outside this
     * class (page/range prefetch jobs and the diagnostic summary).
     */
    fun closeCurrentSession(
        beforeRemoteCancellation: (ComicReaderSession?) -> Unit,
        cancelDependentWork: () -> Unit,
    ) {
        val closingSession = session
        val closingDescriptor = descriptor
        ReaderDiagnosticLog.event(
            "close_current_session generation=$activeGeneration hasSession=${closingSession != null}",
        )
        beforeRemoteCancellation(closingSession)
        remoteOpenJob?.cancel()
        remoteOpenJob = null
        cancelDependentWork()
        closingSession?.cancelPrefetches()
        viewportJob?.cancel()
        viewportJob = null
        activeGeneration++
        closingSession?.let(::closeSessionAsync)
        if (closingDescriptor != null) {
            cleanupScope.launch {
                clearTransientPages(closingDescriptor.cacheDir, closingDescriptor.transientPageKey)
            }
        }
        session = null
        descriptor = null
    }

    fun closeSessionAsync(session: ComicReaderSession) {
        cleanupScope.launch {
            sessionMutex.withLock {
                session.close()
            }
        }
    }

    override fun isCurrent(generation: Int): Boolean = generation == activeGeneration

    override suspend fun <T> withSessionLock(action: () -> T): T =
        sessionMutex.withLock { action() }
}

private fun readerInstanceKey(baseKey: String, openGeneration: Int): String =
    "$baseKey#$openGeneration"
