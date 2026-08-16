package org.mubox.reader

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import org.mubox.reader.core.diagnostics.DiagnosticCategory
import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.data.AppDataFolderStore
import org.mubox.reader.data.AppSettingsStore
import org.mubox.reader.data.ComicDownloadCache
import org.mubox.reader.data.ReadingProgressStore
import org.mubox.reader.data.WebDavAccountStore
import org.mubox.reader.data.database.createAppPersistence
import org.mubox.reader.feature.filedirectory.AndroidLocalDirectoryReader
import org.mubox.reader.feature.reader.LocalComicOpener
import org.mubox.reader.infrastructure.library.WebDavLibraryCoverExtractor
import org.mubox.reader.nativebridge.ComicEngine
import org.mubox.reader.feature.videolibrary.VideoThumbnailExtractor
import org.mubox.reader.network.WebDavClientProvider
import org.mubox.reader.network.WebDavCredentialsSnapshot
import org.mubox.reader.network.createWebDavClient as newWebDavClient
import org.mubox.reader.security.AndroidKeystoreCredentialCipher
import org.mubox.reader.security.CredentialCipher
import org.mubox.reader.video.player.VideoPlaybackStateStore
import org.mubox.reader.video.proxy.VideoProxyManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val Context.readingProgressDataStore by preferencesDataStore(name = "reading_progress")
internal val Context.appDataFolderDataStore by preferencesDataStore(name = "app_data_folder")
internal val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")
internal val Context.webDavAccountDataStore by preferencesDataStore(name = "webdav_accounts")

internal class AppContainer(
    context: Context,
    val diagnostics: Diagnostics,
) {

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
        openSession = { fd, size ->
            ComicEngine().openLocalFd(
                fd = fd,
                size = size,
            )
        },
    )

    val remoteCache = ComicDownloadCache(File(context.cacheDir, "remote-comics"))
    val coverExtractor = WebDavLibraryCoverExtractor(
        appCacheDir = context.cacheDir,
        remoteCacheDir = remoteCache.cacheDir,
    )
    val videoThumbnailExtractor = VideoThumbnailExtractor(
        cacheDir = context.cacheDir,
        maxCacheBytes = 128L * 1024L * 1024L,
    )

    val progressStore = ReadingProgressStore(context.readingProgressDataStore)
    val dataFolderStore = AppDataFolderStore(context.appDataFolderDataStore)
    val appSettingsStore = AppSettingsStore(context.appSettingsDataStore)
    val videoPlaybackStateStore = VideoPlaybackStateStore(appPersistence.playbackPositionRepository)
    val videoProxyManager = VideoProxyManager(diagnostics)
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
    val webDavPlaybackClientFactories = AppWebDavPlaybackClientFactories(
        loadSavedFactory = webDavClientProvider::clientFactoryFor,
    )
    val videoPlayerDependencies = AppVideoPlayerDependencies(
        settingsStore = appSettingsStore,
        webDavClientFactories = webDavPlaybackClientFactories,
        historyRepository = watchHistoryRepository,
        playbackStateStore = videoPlaybackStateStore,
        proxyManager = videoProxyManager,
    )

    fun createWebDavClient(baseUrl: String, username: String?, password: String?) =
        newWebDavClient(
            baseUrl = baseUrl,
            username = username,
            password = password,
            diagnostics = diagnostics,
        )

    fun openLocalComicSession(path: String) = ComicEngine().openLocal(path)
    val downloadRecordStore = appPersistence.downloadRecordRepository
    val videoDownloadStore = appPersistence.videoDownloadRepository

    fun startBackgroundMigrations(scope: CoroutineScope): Job = scope.launch {
        try {
            webDavAccountStore.migratePlaintextPasswords()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.error(DiagnosticCategory.STORAGE, "plaintext_password_migration_failed", error)
            // The account migration is idempotent and will be attempted again next process start.
        }
        try {
            val result = appPersistence.migrateLegacyFileDirectoryCredentials(
                accountStore = webDavAccountStore,
                cipher = credentialCipher,
            )
            if (!result.isComplete) {
                diagnostics.error(
                    DiagnosticCategory.STORAGE,
                    "legacy_directory_credential_migration_incomplete failedSourceIds=${result.failedSourceIds}",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.error(DiagnosticCategory.STORAGE, "legacy_directory_credential_migration_failed", error)
            // Legacy fields remain intact, so the idempotent migration can retry next process start.
        }
    }
}
