package com.example.comicdav

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.example.comicdav.data.AppDataFolderStore
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.DownloadRecordStore
import com.example.comicdav.data.ReadingProgressStore
import com.example.comicdav.data.VideoDownloadStore
import com.example.comicdav.data.WebDavAccountStore
import com.example.comicdav.data.filedirectory.FileDirectoryCredentialMigrator
import com.example.comicdav.data.filedirectory.FileDirectoryRepository
import com.example.comicdav.data.library.LibraryRepository
import com.example.comicdav.data.library.createLibraryDatabase
import com.example.comicdav.data.videolibrary.VideoLibraryRepository
import com.example.comicdav.feature.filedirectory.AndroidLocalDirectoryReader
import com.example.comicdav.feature.library.WebDavLibraryCoverExtractor
import com.example.comicdav.feature.reader.LocalComicOpener
import com.example.comicdav.feature.videolibrary.VideoThumbnailExtractor
import com.example.comicdav.network.WebDavClientProvider
import com.example.comicdav.security.AndroidKeystoreCredentialCipher
import com.example.comicdav.security.CredentialCipher
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val Context.readingProgressDataStore by preferencesDataStore(name = "reading_progress")
internal val Context.appDataFolderDataStore by preferencesDataStore(name = "app_data_folder")
internal val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")
internal val Context.webDavAccountDataStore by preferencesDataStore(name = "webdav_accounts")
internal val Context.downloadRecordsDataStore by preferencesDataStore(name = "download_records")
internal val Context.videoDownloadRecordsDataStore by preferencesDataStore(name = "video_download_records")

internal class AppContainer(context: Context) {
    val credentialCipher: CredentialCipher = AndroidKeystoreCredentialCipher()

    private val libraryDatabase = createLibraryDatabase(context)

    val libraryRepository = LibraryRepository(libraryDatabase.libraryDao())
    val videoLibraryRepository = VideoLibraryRepository(libraryDatabase.videoLibraryDao())

    val webDavAccountStore = WebDavAccountStore(context.webDavAccountDataStore, credentialCipher)
    val fileDirectoryRepository = FileDirectoryRepository(libraryDatabase.fileDirectoryDao())
    private val fileDirectoryCredentialMigrator = FileDirectoryCredentialMigrator(
        dao = libraryDatabase.fileDirectoryDao(),
        accountStore = webDavAccountStore,
        cipher = credentialCipher,
    )

    val localDirectoryReader = AndroidLocalDirectoryReader(context.applicationContext)
    val localComicOpener = LocalComicOpener(context.applicationContext)

    val remoteCache = ComicDownloadCache(File(context.cacheDir, "remote-comics"))
    val coverExtractor = WebDavLibraryCoverExtractor(
        appCacheDir = context.cacheDir,
        remoteCacheDir = remoteCache.cacheDir,
    )
    val videoThumbnailExtractor = VideoThumbnailExtractor(cacheDir = context.cacheDir)

    val progressStore = ReadingProgressStore(context.readingProgressDataStore)
    val dataFolderStore = AppDataFolderStore(context.appDataFolderDataStore)
    val appSettingsStore = AppSettingsStore(context.appSettingsDataStore)
    val webDavClientProvider = WebDavClientProvider(webDavAccountStore)
    val downloadRecordStore = DownloadRecordStore(context.downloadRecordsDataStore)
    val videoDownloadStore = VideoDownloadStore(context.videoDownloadRecordsDataStore)

    fun startBackgroundMigrations(scope: CoroutineScope): Job = scope.launch {
        try {
            webDavAccountStore.migratePlaintextPasswords()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The account migration is idempotent and will be attempted again next process start.
        }
        try {
            fileDirectoryCredentialMigrator.migrateLegacyCredentials()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Legacy fields remain intact, so the idempotent migration can retry next process start.
        }
    }
}
