package com.example.comicdav.data.filedirectory

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.comicdav.core.model.source.FileDirectorySourceType

@Entity(tableName = "file_directory_sources")
internal data class FileDirectorySourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val displayName: String,
    val sourceType: FileDirectorySourceType,
    val localTreeUri: String? = null,
    val webDavAccountId: String? = null,
    val webDavPath: String? = null,
    val webDavBaseUrl: String? = null,
    val webDavUsername: String? = null,
    val webDavPassword: String? = null,
    val addedAt: Long,
)

internal class FileDirectoryTypeConverters {
    @TypeConverter
    fun sourceTypeToString(sourceType: FileDirectorySourceType): String {
        return sourceType.name
    }

    @TypeConverter
    fun stringToSourceType(value: String): FileDirectorySourceType {
        return FileDirectorySourceType.valueOf(value)
    }
}
