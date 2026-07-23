package com.example.comicdav.data

import com.example.comicdav.core.io.FileLruPruner

import com.example.comicdav.core.remote.WebDavClient
import java.io.File
import java.security.MessageDigest
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
            return ComicCacheKey(source.sha256())
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

    private companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024L * 1024L
    }
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
