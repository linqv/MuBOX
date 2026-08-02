package org.mubox.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.mubox.reader.core.model.transfer.DownloadRecord
import org.mubox.reader.core.ports.DownloadRecordGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadRecordStore(
    private val dataStore: DataStore<Preferences>,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) : DownloadRecordGateway {
    override val records: Flow<List<DownloadRecord>> = dataStore.data.map { preferences ->
        preferences[DOWNLOAD_RECORDS]
            .orEmpty()
            .lineSequence()
            .mapNotNull(::decodeRecord)
            .toList()
    }

    override suspend fun addRecord(record: DownloadRecord) {
        dataStore.edit { preferences ->
            val existing = preferences[DOWNLOAD_RECORDS]
                .orEmpty()
                .lineSequence()
                .mapNotNull(::decodeRecord)
                .filterNot { it.remotePath == record.remotePath && it.fileName == record.fileName }
                .toList()
            val updated = (listOf(record) + existing)
                .take(maxRecords.coerceAtLeast(1))
                .joinToString(separator = "\n", transform = ::encodeRecord)
            preferences[DOWNLOAD_RECORDS] = updated
        }
    }

    override suspend fun removeRecord(record: DownloadRecord) {
        dataStore.edit { preferences ->
            val updated = preferences[DOWNLOAD_RECORDS]
                .orEmpty()
                .lineSequence()
                .mapNotNull(::decodeRecord)
                .filterNot { it.remotePath == record.remotePath && it.fileName == record.fileName }
                .joinToString(separator = "\n", transform = ::encodeRecord)
            preferences[DOWNLOAD_RECORDS] = updated
        }
    }

    private companion object {
        const val DEFAULT_MAX_RECORDS = 20
        val DOWNLOAD_RECORDS = stringPreferencesKey("download_records")
    }
}

private fun encodeRecord(record: DownloadRecord): String =
    listOf(
        record.fileName.sanitizeRecordField(),
        record.remotePath.sanitizeRecordField(),
        record.sizeBytes.toString(),
        record.downloadedAtMillis.toString(),
        record.accountId.orEmpty().sanitizeRecordField(),
        record.localUri.orEmpty().sanitizeRecordField(),
    ).joinToString(separator = "\t")

private fun decodeRecord(raw: String): DownloadRecord? {
    val parts = raw.split('\t')
    if (parts.size !in 4..6) return null
    return DownloadRecord(
        fileName = parts[0],
        remotePath = parts[1],
        sizeBytes = parts[2].toLongOrNull() ?: return null,
        downloadedAtMillis = parts[3].toLongOrNull() ?: return null,
        accountId = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        localUri = parts.getOrNull(5)?.takeIf { it.isNotBlank() },
    )
}

private fun String.sanitizeRecordField(): String =
    replace('\t', ' ').replace('\n', ' ')
