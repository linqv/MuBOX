package org.mubox.reader.data.download

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

internal data class DownloadRecordKey(
    val remotePath: String,
    val fileName: String,
)

internal data class VideoDownloadRecordKey(
    val accountId: String,
    val remotePath: String,
)

@Dao
internal interface DownloadRecordDao {
    @Query(
        """
        SELECT * FROM download_records
        ORDER BY downloadedAtMillis DESC, remotePath ASC, fileName ASC
        """,
    )
    fun observeAll(): Flow<List<DownloadRecordEntity>>

    @Upsert
    suspend fun upsert(record: DownloadRecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<DownloadRecordEntity>)

    @Query("DELETE FROM download_records WHERE remotePath = :remotePath AND fileName = :fileName")
    suspend fun delete(remotePath: String, fileName: String)

    @Query(
        """
        SELECT remotePath, fileName FROM download_records
        ORDER BY downloadedAtMillis DESC, remotePath ASC, fileName ASC
        LIMIT -1 OFFSET :maxRecords
        """,
    )
    suspend fun findOverflow(maxRecords: Int): List<DownloadRecordKey>
}

@Dao
internal interface VideoDownloadRecordDao {
    @Query(
        """
        SELECT * FROM video_download_records
        ORDER BY downloadedAtMillis DESC, accountId ASC, remotePath ASC
        """,
    )
    fun observeAll(): Flow<List<VideoDownloadRecordEntity>>

    @Upsert
    suspend fun upsert(record: VideoDownloadRecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<VideoDownloadRecordEntity>)

    @Query("DELETE FROM video_download_records WHERE accountId = :accountId AND remotePath = :remotePath")
    suspend fun delete(accountId: String, remotePath: String)

    @Query(
        """
        SELECT accountId, remotePath FROM video_download_records
        ORDER BY downloadedAtMillis DESC, accountId ASC, remotePath ASC
        LIMIT -1 OFFSET :maxRecords
        """,
    )
    suspend fun findOverflow(maxRecords: Int): List<VideoDownloadRecordKey>
}
