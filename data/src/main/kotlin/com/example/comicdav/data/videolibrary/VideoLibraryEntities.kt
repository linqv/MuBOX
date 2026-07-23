package com.example.comicdav.data.videolibrary

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

@Entity(tableName = "video_library_items")
internal data class VideoLibraryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val displayName: String,
    val sourceType: VideoSourceType,
    val thumbnailPath: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)

@Entity(
    tableName = "local_video_sources",
    foreignKeys = [
        ForeignKey(
            entity = VideoLibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoLibraryItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("videoLibraryItemId"),
        Index(value = ["uri"], unique = true),
    ],
)
internal data class LocalVideoSourceEntity(
    @PrimaryKey
    val videoLibraryItemId: Long,
    val uri: String,
    val fileName: String,
    val size: Long? = null,
    val lastModified: Long? = null,
)

@Entity(
    tableName = "webdav_video_sources",
    foreignKeys = [
        ForeignKey(
            entity = VideoLibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoLibraryItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("videoLibraryItemId"),
        Index(value = ["accountId", "remotePath"], unique = true),
    ],
)
internal data class WebDavVideoSourceEntity(
    @PrimaryKey
    val videoLibraryItemId: Long,
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long? = null,
    val etag: String? = null,
    val lastModified: Long? = null,
)

internal data class VideoLibraryItemRelation(
    @Embedded
    val item: VideoLibraryItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "videoLibraryItemId",
    )
    val localSource: LocalVideoSourceEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "videoLibraryItemId",
    )
    val webDavSource: WebDavVideoSourceEntity?,
)

internal class VideoLibraryTypeConverters {
    @TypeConverter
    fun videoSourceTypeToString(sourceType: VideoSourceType): String {
        return sourceType.name
    }

    @TypeConverter
    fun stringToVideoSourceType(value: String): VideoSourceType {
        return VideoSourceType.valueOf(value)
    }
}
