package com.example.comicdav.data.database

import android.content.Context
import com.example.comicdav.data.WebDavAccountStore
import com.example.comicdav.core.ports.FileDirectoryCatalog
import com.example.comicdav.data.filedirectory.FileDirectoryCredentialMigrator
import com.example.comicdav.data.filedirectory.FileDirectoryCredentialMigrationResult
import com.example.comicdav.data.filedirectory.FileDirectoryRepository
import com.example.comicdav.data.history.WatchHistoryRepository
import com.example.comicdav.core.ports.WatchHistoryGateway
import com.example.comicdav.core.ports.LibraryCatalog
import com.example.comicdav.data.library.LibraryRepository
import com.example.comicdav.core.ports.VideoLibraryCatalog
import com.example.comicdav.data.videolibrary.VideoLibraryRepository
import com.example.comicdav.security.CredentialCipher

/** Public composition boundary for the app's private Room database. */
class AppPersistence internal constructor(
    private val database: AppDatabase,
) {
    val libraryRepository: LibraryCatalog = LibraryRepository(database.libraryDao())
    val videoLibraryRepository: VideoLibraryCatalog = VideoLibraryRepository(database.videoLibraryDao())
    val fileDirectoryRepository: FileDirectoryCatalog = FileDirectoryRepository(database.fileDirectoryDao())
    val watchHistoryRepository: WatchHistoryGateway = WatchHistoryRepository(database.watchHistoryDao())

    suspend fun migrateLegacyFileDirectoryCredentials(
        accountStore: WebDavAccountStore,
        cipher: CredentialCipher,
    ): FileDirectoryCredentialMigrationResult =
        FileDirectoryCredentialMigrator(
            dao = database.fileDirectoryDao(),
            accountStore = accountStore,
            cipher = cipher,
        ).migrateLegacyCredentials()
}

fun createAppPersistence(context: Context): AppPersistence {
    return AppPersistence(createAppDatabase(context))
}
