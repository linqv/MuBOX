package org.mubox.reader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.data.filedirectory.FileDirectorySourceEntity
import org.mubox.reader.core.model.source.FileDirectorySourceType
import org.mubox.reader.data.database.createAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun opensVersionOneAppDatabaseAfterFileDirectorySourceTableIsAdded() = runTest {
        createVersionOneAppDatabase()

        val database = createAppDatabase(context, TEST_DATABASE_NAME)
        try {
            val library = database.libraryDao().getLibrary()
            val directories = database.fileDirectoryDao().observeSources().first()
            val history = database.watchHistoryDao().observeAll().first()

            assertEquals(1, library.size)
            assertEquals("Chapter 01", library.single().item.displayName)
            assertTrue(directories.isEmpty())
            assertTrue(history.isEmpty())

            val directoryId = database.fileDirectoryDao().insertSource(
                FileDirectorySourceEntity(
                    displayName = "Comics",
                    sourceType = FileDirectorySourceType.LOCAL,
                    localTreeUri = "content://tree/comics",
                    addedAt = 1_700_000_000_000L,
                ),
            )
            assertTrue(directoryId > 0L)
        } finally {
            database.close()
        }
    }

    @Test
    fun opensVersionTwoAppDatabaseAfterWebDavConnectionColumnsAreAdded() = runTest {
        createVersionTwoAppDatabase()

        val database = createAppDatabase(context, TEST_DATABASE_NAME)
        try {
            val migratedSource = database.fileDirectoryDao().observeSources().first().single()

            assertEquals("Saved WebDAV", migratedSource.displayName)
            assertEquals(FileDirectorySourceType.WEBDAV, migratedSource.sourceType)
            assertEquals("account-1", migratedSource.webDavAccountId)
            assertEquals("/manga", migratedSource.webDavPath)
            assertEquals(null, migratedSource.webDavBaseUrl)
            assertEquals(null, migratedSource.webDavUsername)
            assertEquals(null, migratedSource.webDavPassword)

            val directoryId = database.fileDirectoryDao().insertSource(
                FileDirectorySourceEntity(
                    displayName = "/new",
                    sourceType = FileDirectorySourceType.WEBDAV,
                    webDavAccountId = "https://example.test/dav|lin",
                    webDavPath = "/new",
                    webDavBaseUrl = "https://example.test/dav",
                    webDavUsername = "lin",
                    webDavPassword = "secret",
                    addedAt = 1_700_000_000_001L,
                ),
            )
            assertTrue(directoryId > 0L)
        } finally {
            database.close()
        }
    }

    private fun createVersionOneAppDatabase() {
        context.deleteDatabase(TEST_DATABASE_NAME)
        context.openOrCreateDatabase(TEST_DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `library_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `seriesTitle` TEXT,
                    `volumeTitle` TEXT,
                    `sourceType` TEXT NOT NULL,
                    `coverPath` TEXT,
                    `pageCount` INTEGER,
                    `lastPageIndex` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    `lastOpenedAt` INTEGER,
                    `offlineState` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `local_comic_sources` (
                    `libraryItemId` INTEGER NOT NULL,
                    `uri` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `size` INTEGER,
                    `lastModified` INTEGER,
                    PRIMARY KEY(`libraryItemId`),
                    FOREIGN KEY(`libraryItemId`) REFERENCES `library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_comic_sources_libraryItemId` ON `local_comic_sources` (`libraryItemId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `webdav_comic_sources` (
                    `libraryItemId` INTEGER NOT NULL,
                    `accountId` TEXT NOT NULL,
                    `remotePath` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `size` INTEGER,
                    `etag` TEXT,
                    `lastModified` INTEGER,
                    `cacheKey` TEXT,
                    PRIMARY KEY(`libraryItemId`),
                    FOREIGN KEY(`libraryItemId`) REFERENCES `library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_webdav_comic_sources_libraryItemId` ON `webdav_comic_sources` (`libraryItemId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf(VERSION_ONE_IDENTITY_HASH),
            )
            db.execSQL(
                """
                INSERT INTO `library_items` (
                    `title`, `displayName`, `sourceType`, `lastPageIndex`, `addedAt`, `offlineState`
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("Chapter 01", "Chapter 01", "LOCAL", 0, 1_700_000_000_000L, "NOT_DOWNLOADED"),
            )
            db.execSQL(
                """
                INSERT INTO `local_comic_sources` (
                    `libraryItemId`, `uri`, `fileName`, `size`, `lastModified`
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1L,
                    "content://documents/tree/comics/document/chapter.cbz",
                    "chapter.cbz",
                    42_000L,
                    1_600_000_000_000L,
                ),
            )
            db.version = 1
        }
    }

    private fun createVersionTwoAppDatabase() {
        createVersionOneAppDatabase()
        context.openOrCreateDatabase(TEST_DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `file_directory_sources` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `localTreeUri` TEXT,
                    `webDavAccountId` TEXT,
                    `webDavPath` TEXT,
                    `addedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `file_directory_sources` (
                    `displayName`, `sourceType`, `webDavAccountId`, `webDavPath`, `addedAt`
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("Saved WebDAV", "WEBDAV", "account-1", "/manga", 1_700_000_000_000L),
            )
            db.version = 2
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "library-migration-test.db"
        const val VERSION_ONE_IDENTITY_HASH = "027f5268eb97eae53d5b2e5a1fab8815"
    }
}
