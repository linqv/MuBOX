package com.example.comicdav.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.comicdav.data.filedirectory.FileDirectoryDao
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectoryTypeConverters

const val LIBRARY_DATABASE_NAME = "comicdav-library.db"

@Database(
    entities = [
        LibraryItemEntity::class,
        LocalComicSourceEntity::class,
        WebDavComicSourceEntity::class,
        FileDirectorySourceEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(LibraryTypeConverters::class, FileDirectoryTypeConverters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun fileDirectoryDao(): FileDirectoryDao
}

fun createLibraryDatabase(
    context: Context,
    databaseName: String = LIBRARY_DATABASE_NAME,
): LibraryDatabase {
    return Room.databaseBuilder(
        context.applicationContext,
        LibraryDatabase::class.java,
        databaseName,
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
