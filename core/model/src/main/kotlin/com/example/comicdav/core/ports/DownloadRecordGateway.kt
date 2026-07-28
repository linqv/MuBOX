package com.example.comicdav.core.ports

import com.example.comicdav.core.model.transfer.DownloadRecord
import com.example.comicdav.core.model.transfer.VideoDownloadRecord
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
