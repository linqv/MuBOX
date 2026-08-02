package org.mubox.reader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.data.database.AppDatabase
import org.mubox.reader.data.playback.PlaybackPositionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackPositionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: PlaybackPositionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = PlaybackPositionRepository(database.playbackPositionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesPositionsByHashedPlaybackKey() = runTest {
        repository.savePosition("webdav|account\t1|/video/line\n1.mkv", 42_000L)

        assertEquals(42_000L, repository.loadPosition("webdav|account\t1|/video/line\n1.mkv"))
        assertEquals(0L, repository.loadPosition("webdav|other|/video/line\n1.mkv"))
    }

    @Test
    fun deleteAndClearUseLocalRoomUpdates() = runTest {
        repository.savePosition("video-1", 10_000L)
        repository.savePosition("video-2", 20_000L)

        repository.deletePosition("video-1")
        assertEquals(0L, repository.loadPosition("video-1"))
        assertEquals(20_000L, repository.loadPosition("video-2"))

        repository.clear()
        assertEquals(0L, repository.loadPosition("video-2"))
    }
}
