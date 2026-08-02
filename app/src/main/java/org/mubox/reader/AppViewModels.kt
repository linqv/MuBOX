package org.mubox.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.mubox.reader.feature.downloads.AndroidDownloadBackend
import org.mubox.reader.feature.downloads.DownloadCoordinator
import org.mubox.reader.feature.filedirectory.FileDirectoryViewModel
import org.mubox.reader.feature.library.LibraryViewModel
import org.mubox.reader.feature.reader.ReaderViewModel
import org.mubox.reader.feature.videolibrary.VideoLibraryViewModel
import org.mubox.reader.feature.webdav.WebDavViewModel

internal data class AppViewModels(
    val webDav: WebDavViewModel,
    val reader: ReaderViewModel,
    val library: LibraryViewModel,
    val videoLibrary: VideoLibraryViewModel,
    val fileDirectory: FileDirectoryViewModel,
    val downloads: DownloadCoordinator,
)

@Composable
internal fun rememberAppViewModels(container: AppContainer): AppViewModels {
    val context = LocalContext.current.applicationContext
    val downloadCoordinatorFactory = remember(
        context,
        container.downloadRecordStore,
        container.videoDownloadStore,
    ) {
        DownloadCoordinator.Factory(
            AndroidDownloadBackend(
                context = context,
                downloadRecordStore = container.downloadRecordStore,
                videoDownloadStore = container.videoDownloadStore,
            ),
            reportFailure = { event, error -> container.diagnostics.error(event, error) },
        )
    }
    return AppViewModels(
        webDav = viewModel(
            factory = viewModelFactory { WebDavViewModel(clientFactory = container::createWebDavClient) },
        ),
        reader = viewModel(
            factory = viewModelFactory {
                ReaderViewModel(
                    openSession = container::openLocalComicSession,
                    savePage = container.progressStore::savePage,
                    recordHistory = { entry ->
                        container.watchHistoryRepository.upsert(entry)
                    },
                    diagnosticLog = container.diagnostics,
                )
            },
        ),
        library = viewModel(factory = viewModelFactory { LibraryViewModel(container.libraryRepository) }),
        videoLibrary = viewModel(
            factory = viewModelFactory { VideoLibraryViewModel(container.videoLibraryRepository) },
        ),
        fileDirectory = viewModel(
            factory = viewModelFactory {
                FileDirectoryViewModel(container.fileDirectoryRepository, container.localDirectoryReader)
            },
        ),
        downloads = viewModel(factory = downloadCoordinatorFactory),
    )
}

private inline fun <reified T : ViewModel> viewModelFactory(
    crossinline create: () -> T,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
