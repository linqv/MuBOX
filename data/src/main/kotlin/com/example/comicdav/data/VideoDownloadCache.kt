package com.example.comicdav.data

import com.example.comicdav.core.remote.WebDavClient
import java.io.File
import kotlinx.coroutines.CancellationException

data class VideoDownloadResult(
    val file: File,
    val localUri: String,
    val sizeBytes: Long,
)

class VideoDownloadCache(
    private val targetDirectory: File,
) {
    suspend fun downloadWebDavVideo(
        client: WebDavClient,
        remotePath: String,
        fileName: String,
        expectedSize: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): VideoDownloadResult {
        targetDirectory.mkdirs()
        val finalFile = targetDirectory.resolve(fileName.sanitizeVideoFileName())
        val tmpFile = targetDirectory.resolve("${finalFile.name}.tmp")
        tmpFile.delete()

        try {
            client.download(remotePath, tmpFile) { downloaded ->
                onProgress(downloaded, expectedSize)
            }
            val actualSize = tmpFile.length()
            if (expectedSize > 0L && actualSize != expectedSize) {
                throw IllegalStateException("Downloaded $actualSize bytes, expected $expectedSize")
            }
            finalizeDownload(tmpFile, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            return VideoDownloadResult(
                file = finalFile,
                localUri = finalFile.toURI().toString(),
                sizeBytes = finalFile.length(),
            )
        } catch (error: CancellationException) {
            tmpFile.delete()
            throw error
        } catch (error: Throwable) {
            tmpFile.delete()
            throw error
        }
    }

    private fun finalizeDownload(tmpFile: File, finalFile: File) {
        if (finalFile.exists() && !finalFile.delete()) {
            throw IllegalStateException("Could not replace existing video download")
        }
        if (tmpFile.renameTo(finalFile)) {
            return
        }
        try {
            tmpFile.copyTo(finalFile, overwrite = true)
            tmpFile.delete()
        } catch (error: Throwable) {
            finalFile.delete()
            throw error
        }
    }
}

internal fun String.sanitizeVideoFileName(): String {
    val sanitized = replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .trim()
        .trim('.')
        .take(180)
    return sanitized.ifBlank { "video-download" }
}
