package com.example.comicdav.data.library

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter
import com.example.comicdav.core.model.library.OfflineState
import com.example.comicdav.core.model.library.SourceType

@Entity(tableName = "library_items")
internal data class LibraryItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val displayName: String,
    val seriesTitle: String? = null,
    val volumeTitle: String? = null,
    val sourceType: SourceType,
    val coverPath: String? = null,
    val pageCount: Int? = null,
    val lastPageIndex: Int = 0,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val offlineState: OfflineState = OfflineState.NOT_DOWNLOADED,
)

@Entity(
    tableName = "local_comic_sources",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["libraryItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("libraryItemId"),
        Index(value = ["uri"], unique = true),
    ],
)
internal data class LocalComicSourceEntity(
    @PrimaryKey
    val libraryItemId: Long,
    val uri: String,
    val fileName: String,
    val size: Long? = null,
    val lastModified: Long? = null,
)

@Entity(
    tableName = "webdav_comic_sources",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["libraryItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("libraryItemId"),
        Index(value = ["accountId", "remotePath"], unique = true),
    ],
)
internal data class WebDavComicSourceEntity(
    @PrimaryKey
    val libraryItemId: Long,
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long? = null,
    val etag: String? = null,
    val lastModified: Long? = null,
    val cacheKey: String? = null,
)

internal data class LibraryItemRelation(
    @Embedded
    val item: LibraryItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "libraryItemId",
    )
    val localSource: LocalComicSourceEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "libraryItemId",
    )
    val webDavSource: WebDavComicSourceEntity?,
)

internal class LibraryTypeConverters {
    @TypeConverter
    fun sourceTypeToString(sourceType: SourceType): String {
        return sourceType.name
    }

    @TypeConverter
    fun stringToSourceType(value: String): SourceType {
        return SourceType.valueOf(value)
    }

    @TypeConverter
    fun offlineStateToString(offlineState: OfflineState): String {
        return offlineState.name
    }

    @TypeConverter
    fun stringToOfflineState(value: String): OfflineState {
        return OfflineState.valueOf(value)
    }
}
