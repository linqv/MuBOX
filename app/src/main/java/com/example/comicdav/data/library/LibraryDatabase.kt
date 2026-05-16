package com.example.comicdav.data.library

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        LibraryItemEntity::class,
        LocalComicSourceEntity::class,
        WebDavComicSourceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(LibraryTypeConverters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
