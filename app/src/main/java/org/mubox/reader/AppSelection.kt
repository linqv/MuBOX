package org.mubox.reader

import org.mubox.reader.feature.filedirectory.FileDirectoryBrowserItem
import org.mubox.reader.core.remote.WebDavItem

internal data class HomeSelection(
    val historyKeys: Set<String> = emptySet(),
    val libraryItemIds: Set<Long> = emptySet(),
    val videoLibraryItemIds: Set<Long> = emptySet(),
) {
    val isActive: Boolean
        get() = historyKeys.isNotEmpty() ||
            libraryItemIds.isNotEmpty() ||
            videoLibraryItemIds.isNotEmpty()

    val count: Int
        get() = historyKeys.size + libraryItemIds.size + videoLibraryItemIds.size

    fun toggleHistory(key: String): HomeSelection = copy(
        historyKeys = historyKeys.toggle(key),
    )

    fun selectAllHistory(keys: Set<String>): HomeSelection = copy(
        historyKeys = keys,
    )

    fun toggleLibrary(id: Long): HomeSelection = copy(
        libraryItemIds = libraryItemIds.toggle(id),
    )

    fun toggleVideoLibrary(id: Long): HomeSelection = copy(
        videoLibraryItemIds = videoLibraryItemIds.toggle(id),
    )

    private fun <T> Set<T>.toggle(value: T): Set<T> =
        if (value in this) this - value else this + value
}

internal sealed interface AppSelection {
    data object None : AppSelection

    data class WebDavFile(val item: WebDavItem) : AppSelection

    data class DirectoryComic(val item: FileDirectoryBrowserItem) : AppSelection

    data class DirectoryVideo(val item: FileDirectoryBrowserItem) : AppSelection
}

internal val AppSelection.webDavFileOrNull: WebDavItem?
    get() = (this as? AppSelection.WebDavFile)?.item

internal val AppSelection.directoryComicOrNull: FileDirectoryBrowserItem?
    get() = (this as? AppSelection.DirectoryComic)?.item

internal val AppSelection.directoryVideoOrNull: FileDirectoryBrowserItem?
    get() = (this as? AppSelection.DirectoryVideo)?.item

internal val AppSelection.isActive: Boolean
    get() = this !is AppSelection.None

internal fun AppSelection.clear(): AppSelection = AppSelection.None

internal inline fun AppSelection.clearIf(predicate: (AppSelection) -> Boolean): AppSelection =
    if (predicate(this)) AppSelection.None else this
