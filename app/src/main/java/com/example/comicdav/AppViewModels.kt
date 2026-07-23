package com.example.comicdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.comicdav.feature.downloads.AndroidDownloadBackend
import com.example.comicdav.feature.downloads.DownloadCoordinator
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.feature.library.LibraryViewModel
import com.example.comicdav.feature.reader.ReaderViewModel
import com.example.comicdav.feature.videolibrary.VideoLibraryViewModel
import com.example.comicdav.feature.webdav.WebDavViewModel

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
            factory = viewModelFactory { ReaderViewModel(openSession = container::openLocalComicSession) },
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
