package org.mubox.reader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.core.model.transfer.VideoDownloadRecord
import org.mubox.reader.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoDownloadStoreTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addRecordPreservesAllVideoDownloadFields() = runTest {
        val store = store()
        val record = record()

        store.addRecord(record)

        assertEquals(listOf(record), store.records.first())
    }

    @Test
    fun preservesTabsAndNewlinesInVideoDownloadFields() = runTest {
        val store = store()
        val record = record().copy(
            fileName = "movie\tpart\n1.mp4",
            accountId = "account\t1",
            remotePath = "/videos/line\n1.mp4",
            localUri = "file:///storage/MuBOX/videos/line\n1.mp4",
        )

        store.addRecord(record)

        assertEquals(listOf(record), store.records.first())
    }

    @Test
    fun addRecordReplacesMatchingAccountAndRemotePath() = runTest {
        val store = store()
        val first = record()
        val replacement = first.copy(
            fileName = "new.mp4",
            localUri = "file:///storage/MuBOX/videos/new.mp4",
            sizeBytes = 30L,
            downloadedAtMillis = 40L,
        )

        store.addRecord(first)
        store.addRecord(replacement)

        assertEquals(listOf(replacement), store.records.first())
    }

    @Test
    fun removeRecordRemovesMatchingAccountAndRemotePathOnly() = runTest {
        val store = store()
        val target = record()
        val other = target.copy(
            accountId = "account-2",
            localUri = "file:///storage/MuBOX/videos/other.mp4",
        )

        store.addRecord(target)
        store.addRecord(other)
        store.removeRecord(target)

        assertEquals(listOf(other), store.records.first())
    }

    @Test
    fun trimsOnlyRecordsBeyondConfiguredLimit() = runTest {
        val store = store(maxRecords = 2)
        store.addRecord(record(path = "/videos/old.mp4", downloadedAtMillis = 10L))
        store.addRecord(record(path = "/videos/middle.mp4", downloadedAtMillis = 20L))
        store.addRecord(record(path = "/videos/new.mp4", downloadedAtMillis = 30L))

        assertEquals(
            listOf("/videos/new.mp4", "/videos/middle.mp4"),
            store.records.first().map { it.remotePath },
        )
    }

    private fun store(maxRecords: Int = 20): VideoDownloadStore =
        VideoDownloadStore(
            database = database,
            dao = database.videoDownloadRecordDao(),
            maxRecords = maxRecords,
        )

    private fun record(
        path: String = "/videos/movie.mp4",
        downloadedAtMillis: Long = 5678L,
    ): VideoDownloadRecord = VideoDownloadRecord(
        fileName = path.substringAfterLast('/'),
        accountId = "account-1",
        remotePath = path,
        localUri = "file:///storage/MuBOX/videos/${path.substringAfterLast('/')}",
        sizeBytes = 1234L,
        downloadedAtMillis = downloadedAtMillis,
    )
}
