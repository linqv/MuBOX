package com.example.comicdav

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.example.comicdav.core.diagnostics.ConfigurableDiagnostics
import com.example.comicdav.data.AppDataFolderStore
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.DownloadRecordStore
import com.example.comicdav.data.ReadingProgressStore
import com.example.comicdav.data.VideoDownloadStore
import com.example.comicdav.data.WebDavAccountStore
import com.example.comicdav.data.database.createAppPersistence
import com.example.comicdav.feature.filedirectory.AndroidLocalDirectoryReader
import com.example.comicdav.feature.library.WebDavLibraryCoverExtractor
import com.example.comicdav.feature.reader.LocalComicOpener
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.infrastructure.diagnostics.AndroidLogcatDiagnosticSink
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.network.OkHttpWebDavClient
import com.example.comicdav.feature.videolibrary.VideoThumbnailExtractor
import com.example.comicdav.network.WebDavClientProvider
import com.example.comicdav.network.WebDavCredentialsSnapshot
import com.example.comicdav.security.AndroidKeystoreCredentialCipher
import com.example.comicdav.security.CredentialCipher
import com.example.comicdav.video.player.VideoPlaybackStateStore
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
internal val Context.videoPlaybackStateDataStore by preferencesDataStore(name = "video_playback_state")

internal class AppContainer(context: Context) {
    val diagnostics = ConfigurableDiagnostics(defaultSink = AndroidLogcatDiagnosticSink())

    init {
        ReaderDiagnosticLog.attach(diagnostics)
    }

    val credentialCipher: CredentialCipher = AndroidKeystoreCredentialCipher()

    private val appPersistence = createAppPersistence(context)

    val libraryRepository = appPersistence.libraryRepository
    val videoLibraryRepository = appPersistence.videoLibraryRepository
    val watchHistoryRepository = appPersistence.watchHistoryRepository

    val webDavAccountStore = WebDavAccountStore(context.webDavAccountDataStore, credentialCipher)
    val fileDirectoryRepository = appPersistence.fileDirectoryRepository

    val localDirectoryReader = AndroidLocalDirectoryReader(context.applicationContext)
    val localComicOpener = LocalComicOpener(
        context = context.applicationContext,
        openSession = { fd, size, format, avifImagesEnabled ->
            ComicEngine(diagnostics = diagnostics).openLocalFd(
                fd = fd,
                size = size,
                format = format.nativeName,
                avifImagesEnabled = avifImagesEnabled,
            )
        },
        diagnostics = diagnostics,
    )

    val remoteCache = ComicDownloadCache(File(context.cacheDir, "remote-comics"))
    val coverExtractor = WebDavLibraryCoverExtractor(
        appCacheDir = context.cacheDir,
        remoteCacheDir = remoteCache.cacheDir,
        diagnostics = diagnostics,
    )
    val videoThumbnailExtractor = VideoThumbnailExtractor(cacheDir = context.cacheDir)
    val historyThumbnailExtractor = VideoThumbnailExtractor(
        cacheDir = context.cacheDir,
        cacheSubdirectory = "history-thumbnails",
    )

    val progressStore = ReadingProgressStore(context.readingProgressDataStore)
    val dataFolderStore = AppDataFolderStore(context.appDataFolderDataStore)
    val appSettingsStore = AppSettingsStore(context.appSettingsDataStore)
    val videoPlaybackStateStore = VideoPlaybackStateStore(context.videoPlaybackStateDataStore)
    val webDavClientProvider = WebDavClientProvider(
        loadCredentials = { accountId ->
            webDavAccountStore.loadAccount(accountId)?.let { account ->
                WebDavCredentialsSnapshot(
                    baseUrl = account.baseUrl,
                    username = account.username,
                    password = account.password,
                )
            }
        },
        diagnostics = diagnostics,
    )
    val videoPlayerDependencies = AppVideoPlayerDependencies(
        settingsStore = appSettingsStore,
        clientProvider = webDavClientProvider,
        historyRepository = watchHistoryRepository,
        legacyPlaybackStateStore = videoPlaybackStateStore,
    )

    fun createWebDavClient(baseUrl: String, username: String?, password: String?) =
        OkHttpWebDavClient(
            baseUrl = baseUrl,
            username = username?.takeIf(String::isNotBlank),
            password = password?.takeIf(String::isNotBlank),
            diagnostics = com.example.comicdav.network.WebDavNetworkDiagnostics(diagnostics),
        )

    fun openLocalComicSession(path: String) = ComicEngine(diagnostics = diagnostics).openLocal(path)
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
            appPersistence.migrateLegacyFileDirectoryCredentials(
                accountStore = webDavAccountStore,
                cipher = credentialCipher,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Legacy fields remain intact, so the idempotent migration can retry next process start.
        }
    }
}
