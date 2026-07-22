package com.example.comicdav

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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
)

@Composable
internal fun rememberAppViewModels(container: AppContainer): AppViewModels =
    AppViewModels(
        webDav = viewModel(),
        reader = viewModel(),
        library = viewModel(factory = viewModelFactory { LibraryViewModel(container.libraryRepository) }),
        videoLibrary = viewModel(
            factory = viewModelFactory { VideoLibraryViewModel(container.videoLibraryRepository) },
        ),
        fileDirectory = viewModel(
            factory = viewModelFactory {
                FileDirectoryViewModel(container.fileDirectoryRepository, container.localDirectoryReader)
            },
        ),
    )

private inline fun <reified T : ViewModel> viewModelFactory(
    crossinline create: () -> T,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
