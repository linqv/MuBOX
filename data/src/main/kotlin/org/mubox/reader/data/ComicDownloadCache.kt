package org.mubox.reader.data

import org.mubox.reader.core.crypto.sha256Hex
import org.mubox.reader.core.io.FileLruPruner

import org.mubox.reader.core.remote.WebDavClient
import java.io.File
import kotlinx.coroutines.CancellationException

@JvmInline
value class ComicCacheKey(val value: String) {
    companion object {
        fun fromRemote(
            accountId: String,
            remotePath: String,
            size: Long,
            etag: String?,
            lastModified: Long?,
        ): ComicCacheKey {
            val version = etag ?: lastModified?.toString().orEmpty()
            val source = listOf(accountId, remotePath, size.toString(), version).joinToString(separator = "\u001F")
            return ComicCacheKey(source.sha256Hex())
        }
    }
}

class ComicDownloadCache(
    val cacheDir: File,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    suspend fun download(
        client: WebDavClient,
        remotePath: String,
        key: ComicCacheKey,
        expectedSize: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        cacheDir.mkdirs()
        val finalFile = cacheDir.resolve("${key.value}.cbz")
        if (finalFile.isFile && finalFile.length() == expectedSize) {
            finalFile.setLastModified(System.currentTimeMillis())
            prune(protectedFile = finalFile)
            onProgress(expectedSize, expectedSize)
            return finalFile
        }

        val tmpFile = cacheDir.resolve("${key.value}.tmp")
        tmpFile.delete()
        try {
            val written = client.download(remotePath, tmpFile) { downloaded ->
                onProgress(downloaded, expectedSize)
            }
            if (written != expectedSize) {
                throw IllegalStateException("Downloaded $written bytes, expected $expectedSize")
            }
            if (finalFile.exists()) {
                finalFile.delete()
            }
            check(tmpFile.renameTo(finalFile)) { "Could not rename download into cache" }
            finalFile.setLastModified(System.currentTimeMillis())
            prune(protectedFile = finalFile)
            return finalFile
        } catch (error: CancellationException) {
            tmpFile.delete()
            throw error
        } catch (error: Throwable) {
            tmpFile.delete()
            throw error
        }
    }

    fun prune(protectedFile: File? = null): Int {
        val protectedFiles = protectedFile?.let { setOf(it) }.orEmpty()
        return FileLruPruner.prune(cacheDir, maxCacheBytes, protectedFiles)
    }

    fun clear(key: ComicCacheKey): Long {
        val targets = listOf(
            cacheDir.resolve("${key.value}.cbz"),
            cacheDir.resolve("${key.value}.tmp"),
            indexCacheFile(key),
        )
        return targets
            .distinctBy { target -> target.absolutePath }
            .sumOf { target -> target.deleteAndReturnBytes() }
    }

    internal fun indexCacheFile(key: ComicCacheKey): File =
        cacheDir.resolve("index").resolve("${key.value.sha256Hex()}.json")

    private companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024L * 1024L
    }
}

private fun File.deleteAndReturnBytes(): Long {
    if (!exists()) return 0L
    val bytes = if (isFile) length() else walkTopDown().filter(File::isFile).sumOf(File::length)
    val deleted = if (isDirectory) deleteRecursively() else delete()
    return if (deleted) bytes else 0L
}
