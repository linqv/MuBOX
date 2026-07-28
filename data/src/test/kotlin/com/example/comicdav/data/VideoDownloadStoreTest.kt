package com.example.comicdav.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.comicdav.core.model.transfer.VideoDownloadRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoDownloadStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun encodeDecodeRoundTripPreservesAllVideoDownloadFields() {
        val record = VideoDownloadRecord(
            fileName = "movie.mp4",
            accountId = "account-1",
            remotePath = "/videos/movie.mp4",
            localUri = "file:///storage/MuBOX/videos/movie.mp4",
            sizeBytes = 1234L,
            downloadedAtMillis = 5678L,
        )

        assertEquals(record, decodeVideoDownloadRecord(encodeVideoDownloadRecord(record)))
    }

    @Test
    fun addRecordPreservesAllVideoDownloadFields() = runTest {
        val store = createStore("video-downloads.preferences_pb")
        val record = VideoDownloadRecord(
            fileName = "movie.mp4",
            accountId = "account-1",
            remotePath = "/videos/movie.mp4",
            localUri = "file:///storage/MuBOX/videos/movie.mp4",
            sizeBytes = 1234L,
            downloadedAtMillis = 5678L,
        )

        store.addRecord(record)

        assertEquals(listOf(record), store.records.first())
    }

    @Test
    fun addRecordReplacesMatchingAccountAndRemotePath() = runTest {
        val store = createStore("video-download-replace.preferences_pb")
        val first = VideoDownloadRecord(
            fileName = "old.mp4",
            accountId = "account-1",
            remotePath = "/videos/movie.mp4",
            localUri = "file:///storage/MuBOX/videos/old.mp4",
            sizeBytes = 10L,
            downloadedAtMillis = 20L,
        )
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
        val store = createStore("video-download-remove.preferences_pb")
        val target = VideoDownloadRecord(
            fileName = "movie.mp4",
            accountId = "account-1",
            remotePath = "/videos/movie.mp4",
            localUri = "file:///storage/MuBOX/videos/movie.mp4",
            sizeBytes = 1234L,
            downloadedAtMillis = 5678L,
        )
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
    fun malformedStoredLinesAreIgnored() = runTest {
        assertTrue(decodeVideoDownloadRecord("movie.mp4\taccount\t/path\tfile:///tmp/movie.mp4\tbad-size\t100") == null)
    }

    private fun TestScope.createStore(fileName: String): VideoDownloadStore {
        val preferencesFile = temporaryFolder.newFile(fileName)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        return VideoDownloadStore(dataStore)
    }
}
