package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class VideoDownloadRecord(
    val fileName: String,
    val accountId: String,
    val remotePath: String,
    val localUri: String,
    val sizeBytes: Long,
    val downloadedAtMillis: Long,
)

class VideoDownloadStore(
    private val dataStore: DataStore<Preferences>,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) {
    val records: Flow<List<VideoDownloadRecord>> = dataStore.data.map { preferences ->
        preferences[VIDEO_DOWNLOAD_RECORDS]
            .orEmpty()
            .lineSequence()
            .mapNotNull(::decodeVideoDownloadRecord)
            .toList()
    }

    suspend fun addRecord(record: VideoDownloadRecord) {
        dataStore.edit { preferences ->
            val existing = preferences[VIDEO_DOWNLOAD_RECORDS]
                .orEmpty()
                .lineSequence()
                .mapNotNull(::decodeVideoDownloadRecord)
                .filterNot { it.accountId == record.accountId && it.remotePath == record.remotePath }
                .toList()
            preferences[VIDEO_DOWNLOAD_RECORDS] = (listOf(record) + existing)
                .take(maxRecords.coerceAtLeast(1))
                .joinToString(separator = "\n", transform = ::encodeVideoDownloadRecord)
        }
    }

    suspend fun removeRecord(record: VideoDownloadRecord) {
        dataStore.edit { preferences ->
            preferences[VIDEO_DOWNLOAD_RECORDS] = preferences[VIDEO_DOWNLOAD_RECORDS]
                .orEmpty()
                .lineSequence()
                .mapNotNull(::decodeVideoDownloadRecord)
                .filterNot { it.accountId == record.accountId && it.remotePath == record.remotePath }
                .joinToString(separator = "\n", transform = ::encodeVideoDownloadRecord)
        }
    }

    private companion object {
        const val DEFAULT_MAX_RECORDS = 20
    }
}

internal val VIDEO_DOWNLOAD_RECORDS = stringPreferencesKey("video_download_records")

internal fun encodeVideoDownloadRecord(record: VideoDownloadRecord): String =
    listOf(
        record.fileName.sanitizeVideoDownloadRecordField(),
        record.accountId.sanitizeVideoDownloadRecordField(),
        record.remotePath.sanitizeVideoDownloadRecordField(),
        record.localUri.sanitizeVideoDownloadRecordField(),
        record.sizeBytes.toString(),
        record.downloadedAtMillis.toString(),
    ).joinToString(separator = "\t")

internal fun decodeVideoDownloadRecord(raw: String): VideoDownloadRecord? {
    val parts = raw.split('\t')
    if (parts.size != 6) return null
    return VideoDownloadRecord(
        fileName = parts[0],
        accountId = parts[1],
        remotePath = parts[2],
        localUri = parts[3],
        sizeBytes = parts[4].toLongOrNull() ?: return null,
        downloadedAtMillis = parts[5].toLongOrNull() ?: return null,
    )
}

private fun String.sanitizeVideoDownloadRecordField(): String =
    replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
