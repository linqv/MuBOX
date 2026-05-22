package com.example.comicdav.data.videolibrary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VideoLibraryDao {
    @Insert
    abstract suspend fun insertVideoLibraryItem(item: VideoLibraryItemEntity): Long

    @Insert
    abstract suspend fun insertLocalVideoSource(source: LocalVideoSourceEntity)

    @Insert
    abstract suspend fun insertWebDavVideoSource(source: WebDavVideoSourceEntity)

    @Transaction
    open suspend fun insertLocalVideo(
        item: VideoLibraryItemEntity,
        source: LocalVideoSourceEntity,
    ): Long {
        val videoLibraryItemId = insertVideoLibraryItem(item)
        insertLocalVideoSource(source.copy(videoLibraryItemId = videoLibraryItemId))
        return videoLibraryItemId
    }

    @Transaction
    open suspend fun insertWebDavVideo(
        item: VideoLibraryItemEntity,
        source: WebDavVideoSourceEntity,
    ): Long {
        val videoLibraryItemId = insertVideoLibraryItem(item)
        insertWebDavVideoSource(source.copy(videoLibraryItemId = videoLibraryItemId))
        return videoLibraryItemId
    }

    @Transaction
    @Query("SELECT * FROM video_library_items ORDER BY addedAt DESC, id DESC")
    abstract fun observeVideoLibrary(): Flow<List<VideoLibraryItemWithSources>>

    @Transaction
    @Query("SELECT * FROM video_library_items ORDER BY addedAt DESC, id DESC")
    abstract suspend fun getVideoLibrary(): List<VideoLibraryItemWithSources>

    @Query("SELECT videoLibraryItemId FROM local_video_sources WHERE uri = :uri LIMIT 1")
    abstract suspend fun findLocalVideoId(uri: String): Long?

    @Query(
        "SELECT videoLibraryItemId FROM webdav_video_sources " +
            "WHERE accountId = :accountId AND remotePath = :remotePath LIMIT 1",
    )
    abstract suspend fun findWebDavVideoId(accountId: String, remotePath: String): Long?

    @Query("UPDATE video_library_items SET lastOpenedAt = :openedAt WHERE id = :videoLibraryItemId")
    abstract suspend fun updateLastOpened(videoLibraryItemId: Long, openedAt: Long)

    @Query("UPDATE video_library_items SET thumbnailPath = :thumbnailPath WHERE id = :videoLibraryItemId")
    abstract suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?)

    @Query("DELETE FROM video_library_items WHERE id = :videoLibraryItemId")
    abstract suspend fun deleteVideoLibraryItem(videoLibraryItemId: Long)
}
