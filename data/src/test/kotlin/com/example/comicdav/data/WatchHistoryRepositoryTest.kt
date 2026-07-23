package com.example.comicdav.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.data.database.AppDatabase
import com.example.comicdav.data.history.WatchHistoryEntity
import com.example.comicdav.data.history.WatchHistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchHistoryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: WatchHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WatchHistoryRepository(database.watchHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertKeepsOneEntryAndMovesLatestProgressToTheTop() = runTest {
        repository.upsert(entry("comic", watchedAt = 100L, progress = 2L))
        repository.upsert(entry("video", watchedAt = 200L, progress = 3L))
        repository.upsert(entry("comic", watchedAt = 300L, progress = 8L))

        val history = repository.history.first()

        assertEquals(listOf("comic", "video"), history.map { it.mediaKey })
        assertEquals(8L, history.first().progress)
    }

    @Test
    fun pruneAppliesAgeAndMaximumRecordLimitsTogether() = runTest {
        repository.upsert(entry("old", watchedAt = 1L))
        repository.upsert(entry("newest", watchedAt = NOW))
        repository.upsert(entry("middle", watchedAt = NOW - 1_000L))

        val removed = repository.prune(
            retentionDays = 30,
            maxRecords = 1,
            nowMillis = NOW,
        )

        assertEquals(setOf("old", "middle"), removed.map { it.mediaKey }.toSet())
        assertEquals(listOf("newest"), repository.history.first().map { it.mediaKey })
    }

    @Test
    fun legacyPercentEncodedTitleIsDecodedWhenHistoryIsRead() = runTest {
        database.watchHistoryDao().upsert(
            WatchHistoryEntity(
                mediaKey = "encoded-comic",
                mediaType = WatchMediaType.COMIC.name,
                title = "%E4%B8%AD%E6%96%87%E6%BC%AB%E7%94%BB.cbz",
                sourceType = WatchSourceType.LOCAL.name,
                sourceLocator = "content://encoded-comic",
                accountId = null,
                size = null,
                etag = null,
                lastModified = null,
                progress = 1L,
                total = 10L,
                lastWatchedAt = NOW,
            ),
        )

        assertEquals("中文漫画.cbz", repository.history.first().single().title)
    }

    private fun entry(
        key: String,
        watchedAt: Long,
        progress: Long = 1L,
    ) = WatchHistoryEntry(
        mediaKey = key,
        mediaType = WatchMediaType.COMIC,
        title = key,
        sourceType = WatchSourceType.LOCAL,
        sourceLocator = "content://$key",
        progress = progress,
        total = 10L,
        lastWatchedAt = watchedAt,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
