package org.mubox.reader

import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.ports.LibraryCatalog
import org.mubox.reader.feature.filedirectory.FileDirectoryViewModel
import org.mubox.reader.feature.filedirectory.LocalDirectoryReader
import org.mubox.reader.feature.reader.LocalComicOpener
import org.mubox.reader.feature.videolibrary.VideoLibraryViewModel
import org.mubox.reader.feature.videolibrary.VideoThumbnailExtractor
import org.mubox.reader.feature.webdav.WebDavViewModel
import org.mubox.reader.infrastructure.library.WebDavLibraryCoverExtractor
import org.mubox.reader.video.proxy.VideoProxyManager

/**
 * Media services used by the app-level video workflow.
 *
 * Keeping this explicit prevents video actions from reaching unrelated application services.
 */
internal data class AppVideoMediaServices(
    val diagnostics: Diagnostics,
    val localDirectoryReader: LocalDirectoryReader,
    val library: AppVideoLibraryCoordinator,
    val comicLibrary: LibraryCatalog,
    val videoThumbnailExtractor: VideoThumbnailExtractor,
    val localComicOpener: LocalComicOpener,
    val coverExtractor: WebDavLibraryCoverExtractor,
    val videoProxyManager: VideoProxyManager,
    val webDavPlaybackClientFactories: AppWebDavPlaybackClientFactories,
)

/**
 * Presentation endpoints affected by the video workflow.
 *
 * The action facade deliberately sees only these three feature presenters instead of the
 * application-wide view-model aggregate.
 */
internal data class AppVideoPresenters(
    val fileDirectory: FileDirectoryViewModel,
    val webDav: WebDavViewModel,
    val videoLibrary: VideoLibraryViewModel,
)
