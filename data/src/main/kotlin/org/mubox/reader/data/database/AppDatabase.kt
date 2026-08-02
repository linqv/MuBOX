package org.mubox.reader.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.mubox.reader.data.filedirectory.FileDirectoryDao
import org.mubox.reader.data.filedirectory.FileDirectorySourceEntity
import org.mubox.reader.data.filedirectory.FileDirectoryTypeConverters
import org.mubox.reader.data.history.WatchHistoryDao
import org.mubox.reader.data.history.WatchHistoryEntity
import org.mubox.reader.data.library.LibraryDao
import org.mubox.reader.data.library.LibraryItemEntity
import org.mubox.reader.data.library.LibraryTypeConverters
import org.mubox.reader.data.library.LocalComicSourceEntity
import org.mubox.reader.data.library.WebDavComicSourceEntity
import org.mubox.reader.data.videolibrary.LocalVideoSourceEntity
import org.mubox.reader.data.videolibrary.VideoLibraryDao
import org.mubox.reader.data.videolibrary.VideoLibraryItemEntity
import org.mubox.reader.data.videolibrary.VideoLibraryTypeConverters
import org.mubox.reader.data.videolibrary.WebDavVideoSourceEntity

internal const val APP_DATABASE_NAME = "mubox-library.db"

@Database(
    entities = [
        LibraryItemEntity::class,
        LocalComicSourceEntity::class,
        WebDavComicSourceEntity::class,
        FileDirectorySourceEntity::class,
        VideoLibraryItemEntity::class,
        LocalVideoSourceEntity::class,
        WebDavVideoSourceEntity::class,
        WatchHistoryEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(LibraryTypeConverters::class, FileDirectoryTypeConverters::class, VideoLibraryTypeConverters::class)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun fileDirectoryDao(): FileDirectoryDao
    abstract fun videoLibraryDao(): VideoLibraryDao
    abstract fun watchHistoryDao(): WatchHistoryDao
}

internal fun createAppDatabase(
    context: Context,
    databaseName: String = APP_DATABASE_NAME,
): AppDatabase {
    return Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        databaseName,
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        .build()
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `file_directory_sources` ADD COLUMN `webDavBaseUrl` TEXT")
        db.execSQL("ALTER TABLE `file_directory_sources` ADD COLUMN `webDavUsername` TEXT")
        db.execSQL("ALTER TABLE `file_directory_sources` ADD COLUMN `webDavPassword` TEXT")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM `library_items`
            WHERE `id` IN (
                SELECT duplicate.`libraryItemId`
                FROM `local_comic_sources` AS duplicate
                WHERE duplicate.`libraryItemId` NOT IN (
                    SELECT MIN(kept.`libraryItemId`)
                    FROM `local_comic_sources` AS kept
                    GROUP BY kept.`uri`
                )
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM `library_items`
            WHERE `id` IN (
                SELECT duplicate.`libraryItemId`
                FROM `webdav_comic_sources` AS duplicate
                WHERE duplicate.`libraryItemId` NOT IN (
                    SELECT MIN(kept.`libraryItemId`)
                    FROM `webdav_comic_sources` AS kept
                    GROUP BY kept.`accountId`, kept.`remotePath`
                )
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_comic_sources_uri` ON `local_comic_sources` (`uri`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_webdav_comic_sources_accountId_remotePath` ON `webdav_comic_sources` (`accountId`, `remotePath`)")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `video_library_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `sourceType` TEXT NOT NULL,
                `thumbnailPath` TEXT,
                `addedAt` INTEGER NOT NULL,
                `lastOpenedAt` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_video_sources` (
                `videoLibraryItemId` INTEGER NOT NULL,
                `uri` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `size` INTEGER,
                `lastModified` INTEGER,
                PRIMARY KEY(`videoLibraryItemId`),
                FOREIGN KEY(`videoLibraryItemId`) REFERENCES `video_library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_video_sources_videoLibraryItemId` " +
                "ON `local_video_sources` (`videoLibraryItemId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_video_sources_uri` ON `local_video_sources` (`uri`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `webdav_video_sources` (
                `videoLibraryItemId` INTEGER NOT NULL,
                `accountId` TEXT NOT NULL,
                `remotePath` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `size` INTEGER,
                `etag` TEXT,
                `lastModified` INTEGER,
                PRIMARY KEY(`videoLibraryItemId`),
                FOREIGN KEY(`videoLibraryItemId`) REFERENCES `video_library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_webdav_video_sources_videoLibraryItemId` " +
                "ON `webdav_video_sources` (`videoLibraryItemId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_webdav_video_sources_accountId_remotePath` " +
                "ON `webdav_video_sources` (`accountId`, `remotePath`)",
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `watch_history` (
                `mediaKey` TEXT NOT NULL,
                `mediaType` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `sourceType` TEXT NOT NULL,
                `sourceLocator` TEXT NOT NULL,
                `accountId` TEXT,
                `size` INTEGER,
                `etag` TEXT,
                `lastModified` INTEGER,
                `progress` INTEGER NOT NULL,
                `total` INTEGER NOT NULL,
                `lastWatchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`mediaKey`)
            )
            """.trimIndent(),
        )
    }
}
