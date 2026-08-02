package org.mubox.reader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.core.model.transfer.DownloadRecord
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
class DownloadRecordStoreTest {
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
    fun startsWithNoRecords() = runTest {
        assertEquals(emptyList<DownloadRecord>(), store().records.first())
    }

    @Test
    fun keepsNewestDownloadRecordsFirst() = runTest {
        val store = store()

        store.addRecord(record("chapter-1.cbz", 10L))
        store.addRecord(record("chapter-2.cbz", 20L))

        assertEquals(
            listOf("chapter-2.cbz", "chapter-1.cbz"),
            store.records.first().map { it.fileName },
        )
    }

    @Test
    fun preservesTabsAndNewlinesInStructuredFields() = runTest {
        val store = store()
        val record = DownloadRecord(
            fileName = "demo\tchapter\n01.cbz",
            remotePath = "/books/line\nwith\ttabs.cbz",
            sizeBytes = 4096L,
            downloadedAtMillis = 123L,
            accountId = "account\t1",
            localUri = "content://downloads/root/line\n01.cbz",
        )

        store.addRecord(record)

        assertEquals(listOf(record), store.records.first())
    }

    @Test
    fun addRecordReplacesMatchingRemotePathAndFileName() = runTest {
        val store = store()
        val original = record("same.cbz", 10L)
        val replacement = original.copy(
            sizeBytes = 8192L,
            downloadedAtMillis = 30L,
            localUri = "content://downloads/replacement.cbz",
        )

        store.addRecord(original)
        store.addRecord(replacement)

        assertEquals(listOf(replacement), store.records.first())
    }

    @Test
    fun removesSelectedDownloadRecord() = runTest {
        val store = store()
        val keep = record("keep.cbz", 10L)
        val remove = record("remove.cbz", 20L)
        store.addRecord(keep)
        store.addRecord(remove)

        store.removeRecord(remove)

        assertEquals(listOf(keep), store.records.first())
    }

    @Test
    fun trimsOnlyRecordsBeyondConfiguredLimit() = runTest {
        val store = store(maxRecords = 2)
        store.addRecord(record("old.cbz", 10L))
        store.addRecord(record("middle.cbz", 20L))
        store.addRecord(record("new.cbz", 30L))

        assertEquals(
            listOf("new.cbz", "middle.cbz"),
            store.records.first().map { it.fileName },
        )
    }

    private fun store(maxRecords: Int = 20): DownloadRecordStore =
        DownloadRecordStore(
            database = database,
            dao = database.downloadRecordDao(),
            maxRecords = maxRecords,
        )

    private fun record(fileName: String, downloadedAtMillis: Long): DownloadRecord =
        DownloadRecord(
            fileName = fileName,
            remotePath = "/books/$fileName",
            sizeBytes = 1024L,
            downloadedAtMillis = downloadedAtMillis,
            accountId = "account-1",
            localUri = "content://downloads/$fileName",
        )
}
