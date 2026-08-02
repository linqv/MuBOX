package org.mubox.reader.data

import androidx.room.withTransaction
import org.mubox.reader.core.model.transfer.VideoDownloadRecord
import org.mubox.reader.core.ports.VideoDownloadGateway
import org.mubox.reader.data.database.AppDatabase
import org.mubox.reader.data.download.VideoDownloadRecordDao
import org.mubox.reader.data.download.VideoDownloadRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class VideoDownloadStore(
    private val database: AppDatabase,
    private val dao: VideoDownloadRecordDao,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) : VideoDownloadGateway {
    override val records: Flow<List<VideoDownloadRecord>> =
        dao.observeAll().map { records -> records.map(VideoDownloadRecordEntity::toModel) }

    override suspend fun addRecord(record: VideoDownloadRecord) {
        database.withTransaction {
            dao.upsert(record.toEntity())
            dao.findOverflow(maxRecords.coerceAtLeast(1)).forEach { key ->
                dao.delete(key.accountId, key.remotePath)
            }
        }
    }

    override suspend fun removeRecord(record: VideoDownloadRecord) {
        dao.delete(record.accountId, record.remotePath)
    }

    private companion object {
        const val DEFAULT_MAX_RECORDS = 20
    }
}

internal fun VideoDownloadRecord.toEntity(): VideoDownloadRecordEntity =
    VideoDownloadRecordEntity(
        fileName = fileName,
        accountId = accountId,
        remotePath = remotePath,
        localUri = localUri,
        sizeBytes = sizeBytes,
        downloadedAtMillis = downloadedAtMillis,
    )

private fun VideoDownloadRecordEntity.toModel(): VideoDownloadRecord =
    VideoDownloadRecord(
        fileName = fileName,
        accountId = accountId,
        remotePath = remotePath,
        localUri = localUri,
        sizeBytes = sizeBytes,
        downloadedAtMillis = downloadedAtMillis,
    )
