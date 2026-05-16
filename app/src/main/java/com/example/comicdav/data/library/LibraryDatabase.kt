package com.example.comicdav.data.library

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.comicdav.data.filedirectory.FileDirectoryDao
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectoryTypeConverters

@Database(
    entities = [
        LibraryItemEntity::class,
        LocalComicSourceEntity::class,
        WebDavComicSourceEntity::class,
        FileDirectorySourceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(LibraryTypeConverters::class, FileDirectoryTypeConverters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun fileDirectoryDao(): FileDirectoryDao
}
