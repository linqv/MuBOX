package org.mubox.reader.infrastructure.reader

import org.mubox.reader.core.crypto.sha256Hex
import org.mubox.reader.core.diagnostics.DiagnosticCategory
import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.ports.ReadingProgressGateway
import org.mubox.reader.core.ports.RemoteRangeComicSessionFactory
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.data.ComicCacheKey
import org.mubox.reader.data.ComicDownloadCache
import org.mubox.reader.feature.reader.OpenComicResult
import org.mubox.reader.nativebridge.ComicEngine
import org.mubox.reader.nativebridge.RangeProviderRegistry
import org.mubox.reader.network.WebDavRangeProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenComicUseCase(
    private val accountId: String,
    private val cache: ComicDownloadCache,
    private val progressStore: ReadingProgressGateway,
    private val diagnostics: Diagnostics = NoopDiagnostics,
    private val openRemoteSession: RemoteRangeComicSessionFactory = {
            fileId,
            size,
            cacheDir,
            comicKey,
            validator,
            webDavPrefetchPageCount,
        ->
        ComicEngine().openRemote(
            fileId = fileId,
            size = size,
            cacheDir = cacheDir,
            comicKey = comicKey,
            validator = validator,
            webDavPrefetchPageCount = webDavPrefetchPageCount,
        )
    },
    private val webDavPrefetchPageCount: Int = 4,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun open(
        client: WebDavClient,
        remotePath: String,
        knownInfo: RemoteFileInfo? = null,
    ): OpenComicResult = withContext(ioDispatcher) {
        val info = knownInfo ?: client.head(remotePath)
        val key = ComicCacheKey.fromRemote(
            accountId = accountId,
            remotePath = remotePath,
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        try {
            openRemote(client, remotePath, info, key)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            diagnostics.error(
                DiagnosticCategory.SESSION,
                "open_remote_range_failed path=$remotePath size=${info.size}",
                error,
            )
            throw error
        }
    }

    private suspend fun openRemote(
        client: WebDavClient,
        remotePath: String,
        info: RemoteFileInfo,
        key: ComicCacheKey,
    ): OpenComicResult {
        cache.cacheDir.mkdirs()
        val fileId = RangeProviderRegistry.register(
            WebDavRangeProvider(client, remotePath, info.size),
        )
        return try {
            // A pre-v4 index excludes AVIF pages, so re-indexing can shift page
            // positions. Capture the saved page's name first, then remap it after
            // openRemoteSession regenerates the index. Migration must never break
            // opening, so every read is guarded.
            val savedPageMigration = runCatching {
                probeProgressMigration(cache.cacheDir, key.value, progressStore)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                diagnostics.error(
                    DiagnosticCategory.SESSION,
                    "comic_progress_migration_probe_failed",
                    error,
                )
            }.getOrNull()

            // open() dispatches the full preparation path to IO before reaching this worker-thread call.
            val session = openRemoteSession(
                fileId,
                info.size,
                cache.cacheDir,
                key.value,
                info.validator(),
                webDavPrefetchPageCount,
            )

            var initialPage: Int? = null
            if (savedPageMigration != null) {
                val migratedPage = runCatching {
                    remapProgressPage(cache.cacheDir, key.value, savedPageMigration)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    diagnostics.error(
                        DiagnosticCategory.SESSION,
                        "comic_progress_migration_remap_failed",
                        error,
                    )
                }.getOrNull()
                if (migratedPage != null) {
                    progressStore.savePage(key.value, migratedPage)
                    initialPage = migratedPage
                }
            }
            if (initialPage == null) {
                initialPage = progressStore.loadPage(key.value).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
            }

            OpenComicResult(
                comicKey = key.value,
                pageCacheKey = key.value,
                localFile = cache.cacheDir.resolve("${key.value}.remote"),
                session = session,
                initialPage = initialPage,
            )
        } catch (error: Throwable) {
            RangeProviderRegistry.unregister(fileId)
            throw error
        }
    }

    private fun RemoteFileInfo.validator(): String =
        etag ?: lastModified?.toString() ?: size.toString()
}

private data class ProgressPageMigration(
    val savedPageIndex: Int,
    val targetPageName: String,
)

private data class ParsedIndex(
    val version: Int,
    val pages: List<String>,
)

/** Index versions below this one exclude AVIF pages and can shift page positions on re-index. */
private const val MIGRATION_SOURCE_INDEX_VERSION = 4

private suspend fun probeProgressMigration(
    cacheDir: File,
    comicKey: String,
    progressStore: ReadingProgressGateway,
): ProgressPageMigration? {
    val parsed = readIndexCacheFile(cacheDir, comicKey) ?: return null
    if (parsed.version >= MIGRATION_SOURCE_INDEX_VERSION) return null
    val saved = progressStore.loadPage(comicKey)
    if (saved !in parsed.pages.indices) return null
    return ProgressPageMigration(savedPageIndex = saved, targetPageName = parsed.pages[saved])
}

private fun remapProgressPage(
    cacheDir: File,
    comicKey: String,
    migration: ProgressPageMigration,
): Int? {
    val pages = readIndexCacheFile(cacheDir, comicKey)?.pages ?: return null
    val newIndex = pages.indexOf(migration.targetPageName)
    if (newIndex < 0 || newIndex == migration.savedPageIndex) return null
    return newIndex
}

private fun readIndexCacheFile(cacheDir: File, comicKey: String): ParsedIndex? =
    cacheDir
        .resolve("index/${comicKey.sha256Hex()}.json")
        .takeIf(File::isFile)
        ?.let { parseIndexJson(it.readText()) }

private fun parseIndexJson(content: String): ParsedIndex? {
    return runCatching {
        val json = org.json.JSONObject(content)
        val version = json.optInt("version", -1)
        val indexObj = json.optJSONObject("index")
        val pagesArray = indexObj?.optJSONArray("pages")
        val pages = mutableListOf<String>()
        if (pagesArray != null) {
            for (i in 0 until pagesArray.length()) {
                val pageObj = pagesArray.optJSONObject(i)
                val name = pageObj?.optString("name")
                if (!name.isNullOrBlank()) {
                    pages += name
                }
            }
        }
        ParsedIndex(version = version, pages = pages)
    }.getOrElse {
        // Fallback for malformed JSON (and for JVM unit tests where org.json is stubbed).
        val versionMatch = Regex("\"version\"\\s*:\\s*(\\d+)").find(content) ?: return null
        val version = versionMatch.groupValues[1].toIntOrNull() ?: return null
        val pageMatches = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(content)
        val pages = pageMatches.map { it.groupValues[1] }.toList()
        ParsedIndex(version = version, pages = pages)
    }
}
