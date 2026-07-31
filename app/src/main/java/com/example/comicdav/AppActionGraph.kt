package com.example.comicdav

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.ReaderLoggingMode
import kotlinx.coroutines.CoroutineScope

internal data class AppActionGraph(
    val cache: AppCacheActions,
    val downloads: AppDownloadActions,
    val sources: AppSourceActions,
    val launchers: AppActivityLaunchers,
    val reader: AppReaderActions,
    val webDavResolver: AppWebDavResolver,
    val video: AppVideoActions,
    val comic: AppComicActions,
)

@Composable
internal fun rememberAppActionGraph(
    context: Context,
    scope: CoroutineScope,
    settings: AppSettings,
    container: AppContainer,
    viewModels: AppViewModels,
    ui: AppUiStateHolder,
): AppActionGraph {
    val sourceActions = remember(context, scope, container, viewModels, ui) {
        AppSourceActions(
            context = context,
            scope = scope,
            container = container,
            viewModels = viewModels,
            callbacks = AppSourceActionCallbacks(
                setError = { message -> ui.localOpenError = message },
                setActionMessage = { message -> ui.webDavActionMessage = message },
                setWebDavOpen = { open -> ui.isWebDavOpen = open },
                setAddingWebDavPath = { adding -> ui.isAddingWebDavPath = adding },
                setEditingWebDavSourceId = { sourceId -> ui.editingWebDavSourceId = sourceId },
                // Source actions historically only switched the destination. The app shell
                // owns clearing transient messages and contextual selection on explicit tab taps.
                selectTab = { tab -> ui.selectedTabName = tab.name },
            ),
        )
    }
    val launchers = rememberAppActivityLaunchers(
        context = context,
        scope = scope,
        dataFolderStore = container.dataFolderStore,
        diagnostics = container.diagnostics,
        loggingEnabled = settings.readerLoggingMode != ReaderLoggingMode.OFF,
        onDataFolderSelected = ui::onDataFolderSelected,
        onLogFolderSelected = { uriText -> ui.logFolderUriText = uriText },
        onLocalDirectorySelected = sourceActions::addLocalDirectory,
        onVideoPlayerClosed = { ui.forceMainPortraitState.value = true },
    )
    val cacheActions = remember(context, scope, container, viewModels, ui) {
        AppCacheActions(
            context = context,
            scope = scope,
            container = container,
            viewModels = viewModels,
            callbacks = AppCacheActionCallbacks(
                setAnalysis = { analysis -> ui.cacheAnalysis = analysis },
                setActionMessage = { message -> ui.cacheActionMessage = message },
            ),
        )
    }

    return remember(
        context,
        scope,
        settings,
        container,
        viewModels,
        ui,
        ui.dataFolderUriText,
        ui.logFolderUriText,
        launchers,
        sourceActions,
        cacheActions,
    ) {
        val downloadActions = AppDownloadActions(
            context = context,
            scope = scope,
            dataFolderUri = ui.dataFolderUriText,
            container = container,
            viewModels = viewModels,
            callbacks = AppDownloadActionCallbacks(
                setError = { message -> ui.localOpenError = message },
                setActionMessage = { message -> ui.webDavActionMessage = message },
                clearSelectionIf = ui::clearSelectionIf,
            ),
        )
        val readerActions = AppReaderActions(
            scope = scope,
            settings = settings,
            appSettingsStore = container.appSettingsStore,
            diagnostics = container.diagnostics,
            activityLaunchers = launchers,
            viewModels = viewModels,
            callbacks = AppReaderActionCallbacks(
                isLandscapeModeEnabled = { ui.readerLandscapeModeState.value },
                setLandscapeModeEnabled = { enabled -> ui.readerLandscapeModeState.value = enabled },
                setLandscapeOrientationLocked = { locked ->
                    ui.readerLandscapeOrientationLockedState.value = locked
                },
                setReaderOpen = { open -> ui.readerOpenState.value = open },
                setForceMainPortrait = { force -> ui.forceMainPortraitState.value = force },
                setActionMessage = { message -> ui.webDavActionMessage = message },
            ),
        )
        val webDavResolver = AppWebDavResolver(
            loadSavedAccount = container.webDavAccountStore::loadAccount,
            loadSavedClient = container.webDavClientProvider::clientFor,
            loadSavedClientFactory = container.webDavClientProvider::clientFactoryFor,
            activeConnection = {
                val webDav = viewModels.webDav
                val latestUiState = webDav.uiState
                ActiveWebDavConnection(
                    activeAccountId = webDav.activeAccountId(),
                    configuredAccountId = webDav.accountId(),
                    baseUrl = latestUiState.baseUrl,
                    username = latestUiState.username,
                    password = latestUiState.password,
                    client = webDav.activeClient(),
                )
            },
        )
        val videoActions = AppVideoActions(
            context = context,
            scope = scope,
            settings = settings,
            services = AppVideoMediaServices(
                diagnostics = container.diagnostics,
                localDirectoryReader = container.localDirectoryReader,
                library = AppVideoLibraryCoordinator(container.videoLibraryRepository),
                videoThumbnailExtractor = container.videoThumbnailExtractor,
                browserVideoThumbnailExtractor = container.browserVideoThumbnailExtractor,
                historyThumbnailExtractor = container.historyThumbnailExtractor,
                localComicOpener = container.localComicOpener,
                coverExtractor = container.coverExtractor,
                videoProxyManager = container.videoProxyManager,
                webDavPlaybackClientFactories = container.webDavPlaybackClientFactories,
            ),
            presenters = AppVideoPresenters(
                fileDirectory = viewModels.fileDirectory,
                webDav = viewModels.webDav,
                videoLibrary = viewModels.videoLibrary,
            ),
            webDavResolver = webDavResolver,
            callbacks = AppVideoActionCallbacks(
                launchPlayer = launchers.openVideoPlayer,
                setError = { message -> ui.localOpenError = message },
                setActionMessage = { message -> ui.webDavActionMessage = message },
                clearSelectionIf = ui::clearSelectionIf,
            ),
        )
        val comicActions = AppComicActions(
            context = context,
            scope = scope,
            settings = settings,
            logFolderUri = ui.logFolderUriText,
            container = container,
            viewModels = viewModels,
            webDavResolver = webDavResolver,
            callbacks = AppComicActionCallbacks(
                setError = { message -> ui.localOpenError = message },
                setActionMessage = { message -> ui.webDavActionMessage = message },
                setWebDavOpen = { open -> ui.isWebDavOpen = open },
                setReaderOpen = { open -> ui.readerOpenState.value = open },
                clearSelectionIf = ui::clearSelectionIf,
                refreshCacheAnalysis = cacheActions::refresh,
            ),
        )
        AppActionGraph(
            cache = cacheActions,
            downloads = downloadActions,
            sources = sourceActions,
            launchers = launchers,
            reader = readerActions,
            webDavResolver = webDavResolver,
            video = videoActions,
            comic = comicActions,
        )
    }
}
