package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRecordStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun startsWithNoRecords() = runTest {
        val store = DownloadRecordStore(dataStore("download-records-empty.preferences_pb"))

        assertEquals(emptyList<DownloadRecord>(), store.records.first())
    }

    @Test
    fun keepsNewestDownloadRecordsFirst() = runTest {
        val store = DownloadRecordStore(dataStore("download-records.preferences_pb"))

        store.addRecord(
            DownloadRecord(
                fileName = "chapter-1.cbz",
                remotePath = "/books/chapter-1.cbz",
                sizeBytes = 1024,
                downloadedAtMillis = 10,
            ),
        )
        store.addRecord(
            DownloadRecord(
                fileName = "chapter-2.cbz",
                remotePath = "/books/chapter-2.cbz",
                sizeBytes = 2048,
                downloadedAtMillis = 20,
            ),
        )

        assertEquals(
            listOf("chapter-2.cbz", "chapter-1.cbz"),
            store.records.first().map { it.fileName },
        )
    }

    @Test
    fun storesRecordsWithStablePreferenceKey() = runTest {
        val dataStore = dataStore("download-records-key.preferences_pb")
        val store = DownloadRecordStore(dataStore)

        store.addRecord(
            DownloadRecord(
                fileName = "demo.cbz",
                remotePath = "/books/demo.cbz",
                sizeBytes = 4096,
                downloadedAtMillis = 123,
                accountId = "https://example.test/dav|lin",
                localUri = "content://downloads/root/demo.cbz",
            ),
        )

        val rawRecords = dataStore.data.first()[stringPreferencesKey("download_records")].orEmpty()

        assertEquals(
            "demo.cbz\t/books/demo.cbz\t4096\t123\thttps://example.test/dav|lin\tcontent://downloads/root/demo.cbz",
            rawRecords,
        )
    }

    @Test
    fun addRecordPreservesLocalUri() = runTest {
        val store = DownloadRecordStore(dataStore("download-records-local-uri.preferences_pb"))
        val record = DownloadRecord(
            fileName = "demo.cbz",
            remotePath = "/books/demo.cbz",
            sizeBytes = 4096,
            downloadedAtMillis = 123,
            accountId = "account-1",
            localUri = "content://downloads/root/demo.cbz",
        )

        store.addRecord(record)

        assertEquals(listOf(record), store.records.first())
    }

    @Test
    fun removesSelectedDownloadRecord() = runTest {
        val store = DownloadRecordStore(dataStore("download-records-remove.preferences_pb"))
        store.addRecord(
            DownloadRecord(
                fileName = "keep.cbz",
                remotePath = "/books/keep.cbz",
                sizeBytes = 1024,
                downloadedAtMillis = 10,
            ),
        )
        store.addRecord(
            DownloadRecord(
                fileName = "remove.cbz",
                remotePath = "/books/remove.cbz",
                sizeBytes = 2048,
                downloadedAtMillis = 20,
            ),
        )

        store.removeRecord(
            DownloadRecord(
                fileName = "remove.cbz",
                remotePath = "/books/remove.cbz",
                sizeBytes = 2048,
                downloadedAtMillis = 20,
            ),
        )

        assertEquals(listOf("keep.cbz"), store.records.first().map { it.fileName })
    }

    private fun TestScope.dataStore(fileName: String): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temp.newFile(fileName) },
        )
    }
}
