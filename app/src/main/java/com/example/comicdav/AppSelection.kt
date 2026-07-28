package com.example.comicdav

import com.example.comicdav.core.model.library.LibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.core.remote.WebDavItem

internal sealed interface AppSelection {
    data object None : AppSelection

    data class WebDavFile(val item: WebDavItem) : AppSelection

    data class DirectoryComic(val item: FileDirectoryBrowserItem) : AppSelection

    data class DirectoryVideo(val item: FileDirectoryBrowserItem) : AppSelection

    data class LibraryItem(val item: LibraryItemWithSources) : AppSelection

    data class VideoLibraryItem(val item: VideoLibraryItemWithSources) : AppSelection
}

internal val AppSelection.webDavFileOrNull: WebDavItem?
    get() = (this as? AppSelection.WebDavFile)?.item

internal val AppSelection.directoryComicOrNull: FileDirectoryBrowserItem?
    get() = (this as? AppSelection.DirectoryComic)?.item

internal val AppSelection.directoryVideoOrNull: FileDirectoryBrowserItem?
    get() = (this as? AppSelection.DirectoryVideo)?.item

internal val AppSelection.libraryItemOrNull: LibraryItemWithSources?
    get() = (this as? AppSelection.LibraryItem)?.item

internal val AppSelection.videoLibraryItemOrNull: VideoLibraryItemWithSources?
    get() = (this as? AppSelection.VideoLibraryItem)?.item

internal val AppSelection.isActive: Boolean
    get() = this !is AppSelection.None

internal fun AppSelection.clear(): AppSelection = AppSelection.None

internal inline fun AppSelection.clearIf(predicate: (AppSelection) -> Boolean): AppSelection =
    if (predicate(this)) AppSelection.None else this
