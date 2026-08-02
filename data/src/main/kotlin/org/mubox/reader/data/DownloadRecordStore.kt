package org.mubox.reader.data

import androidx.room.withTransaction
import org.mubox.reader.core.model.transfer.DownloadRecord
import org.mubox.reader.core.ports.DownloadRecordGateway
import org.mubox.reader.data.database.AppDatabase
import org.mubox.reader.data.download.DownloadRecordDao
import org.mubox.reader.data.download.DownloadRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DownloadRecordStore(
    private val database: AppDatabase,
    private val dao: DownloadRecordDao,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) : DownloadRecordGateway {
    override val records: Flow<List<DownloadRecord>> =
        dao.observeAll().map { records -> records.map(DownloadRecordEntity::toModel) }

    override suspend fun addRecord(record: DownloadRecord) {
        database.withTransaction {
            dao.upsert(record.toEntity())
            dao.findOverflow(maxRecords.coerceAtLeast(1)).forEach { key ->
                dao.delete(key.remotePath, key.fileName)
            }
        }
    }

    override suspend fun removeRecord(record: DownloadRecord) {
        dao.delete(record.remotePath, record.fileName)
    }

    private companion object {
        const val DEFAULT_MAX_RECORDS = 20
    }
}

internal fun DownloadRecord.toEntity(): DownloadRecordEntity =
    DownloadRecordEntity(
        fileName = fileName,
        remotePath = remotePath,
        sizeBytes = sizeBytes,
        downloadedAtMillis = downloadedAtMillis,
        accountId = accountId,
        localUri = localUri,
    )

private fun DownloadRecordEntity.toModel(): DownloadRecord =
    DownloadRecord(
        fileName = fileName,
        remotePath = remotePath,
        sizeBytes = sizeBytes,
        downloadedAtMillis = downloadedAtMillis,
        accountId = accountId,
        localUri = localUri,
    )
