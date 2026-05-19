package com.example.comicdav.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {
    @Insert
    abstract suspend fun insertLibraryItem(item: LibraryItemEntity): Long

    @Insert
    abstract suspend fun insertLocalComicSource(source: LocalComicSourceEntity)

    @Insert
    abstract suspend fun insertWebDavComicSource(source: WebDavComicSourceEntity)

    @Transaction
    open suspend fun insertLocalComic(
        item: LibraryItemEntity,
        source: LocalComicSourceEntity,
    ): Long {
        val libraryItemId = insertLibraryItem(item)
        insertLocalComicSource(source.copy(libraryItemId = libraryItemId))
        return libraryItemId
    }

    @Transaction
    open suspend fun insertWebDavComic(
        item: LibraryItemEntity,
        source: WebDavComicSourceEntity,
    ): Long {
        val libraryItemId = insertLibraryItem(item)
        insertWebDavComicSource(source.copy(libraryItemId = libraryItemId))
        return libraryItemId
    }

    @Transaction
    @Query("SELECT * FROM library_items ORDER BY addedAt DESC, id DESC")
    abstract fun observeLibrary(): Flow<List<LibraryItemWithSources>>

    @Transaction
    @Query("SELECT * FROM library_items ORDER BY addedAt DESC, id DESC")
    abstract suspend fun getLibrary(): List<LibraryItemWithSources>

    @Query("SELECT libraryItemId FROM local_comic_sources WHERE uri = :uri LIMIT 1")
    abstract suspend fun findLocalComicId(uri: String): Long?

    @Query("SELECT libraryItemId FROM webdav_comic_sources WHERE accountId = :accountId AND remotePath = :remotePath LIMIT 1")
    abstract suspend fun findWebDavComicId(accountId: String, remotePath: String): Long?

    @Query("UPDATE library_items SET lastOpenedAt = :openedAt WHERE id = :libraryItemId")
    abstract suspend fun updateLastOpened(libraryItemId: Long, openedAt: Long)

    @Query("UPDATE library_items SET coverPath = :coverPath WHERE id = :libraryItemId")
    abstract suspend fun updateCoverPath(libraryItemId: Long, coverPath: String?)

    @Query("DELETE FROM library_items WHERE id = :libraryItemId")
    abstract suspend fun deleteLibraryItem(libraryItemId: Long)
}
