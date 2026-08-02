package org.mubox.reader.core.ports

import org.mubox.reader.core.model.transfer.DownloadRecord
import org.mubox.reader.core.model.transfer.VideoDownloadRecord
import kotlinx.coroutines.flow.Flow

interface DownloadRecordGateway {
    val records: Flow<List<DownloadRecord>>

    suspend fun addRecord(record: DownloadRecord)

    suspend fun removeRecord(record: DownloadRecord)
}

interface VideoDownloadGateway {
    val records: Flow<List<VideoDownloadRecord>>

    suspend fun addRecord(record: VideoDownloadRecord)

    suspend fun removeRecord(record: VideoDownloadRecord)
}
