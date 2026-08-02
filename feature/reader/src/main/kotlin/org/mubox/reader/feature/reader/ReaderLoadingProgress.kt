package org.mubox.reader.feature.reader

data class ReaderLoadingProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    val label: String
        get() = "正在下载 ${downloadedBytes / 1024} KiB / ${totalBytes / 1024} KiB"
}
