package org.mubox.reader.data.database

import android.content.Context
import org.mubox.reader.data.DownloadRecordStore
import org.mubox.reader.data.VideoDownloadStore
import org.mubox.reader.data.WebDavAccountStore
import org.mubox.reader.core.ports.DownloadRecordGateway
import org.mubox.reader.core.ports.FileDirectoryCatalog
import org.mubox.reader.data.filedirectory.FileDirectoryCredentialMigrator
import org.mubox.reader.data.filedirectory.FileDirectoryCredentialMigrationResult
import org.mubox.reader.data.filedirectory.FileDirectoryRepository
import org.mubox.reader.data.history.WatchHistoryRepository
import org.mubox.reader.core.ports.WatchHistoryGateway
import org.mubox.reader.core.ports.LibraryCatalog
import org.mubox.reader.core.ports.PlaybackPositionGateway
import org.mubox.reader.core.ports.VideoDownloadGateway
import org.mubox.reader.data.library.LibraryRepository
import org.mubox.reader.core.ports.VideoLibraryCatalog
import org.mubox.reader.data.videolibrary.VideoLibraryRepository
import org.mubox.reader.data.playback.PlaybackPositionRepository
import org.mubox.reader.security.CredentialCipher

/** Public composition boundary for the app's private Room database. */
class AppPersistence internal constructor(
    private val database: AppDatabase,
) {
    val libraryRepository: LibraryCatalog = LibraryRepository(database.libraryDao())
    val videoLibraryRepository: VideoLibraryCatalog = VideoLibraryRepository(database.videoLibraryDao())
    val fileDirectoryRepository: FileDirectoryCatalog = FileDirectoryRepository(database.fileDirectoryDao())
    val watchHistoryRepository: WatchHistoryGateway = WatchHistoryRepository(database.watchHistoryDao())
    val downloadRecordRepository: DownloadRecordGateway = DownloadRecordStore(
        database = database,
        dao = database.downloadRecordDao(),
    )
    val videoDownloadRepository: VideoDownloadGateway = VideoDownloadStore(
        database = database,
        dao = database.videoDownloadRecordDao(),
    )
    val playbackPositionRepository: PlaybackPositionGateway = PlaybackPositionRepository(
        dao = database.playbackPositionDao(),
    )

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
