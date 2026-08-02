package com.example.comicdav

import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.feature.filedirectory.LocalDirectoryReader
import com.example.comicdav.feature.reader.LocalComicOpener
import com.example.comicdav.feature.videolibrary.VideoLibraryViewModel
import com.example.comicdav.feature.videolibrary.VideoThumbnailExtractor
import com.example.comicdav.feature.webdav.WebDavViewModel
import com.example.comicdav.infrastructure.library.WebDavLibraryCoverExtractor
import com.example.comicdav.video.proxy.VideoProxyManager

/**
 * Media services used by the app-level video workflow.
 *
 * Keeping this explicit prevents video actions from reaching unrelated application services.
 */
internal data class AppVideoMediaServices(
    val diagnostics: Diagnostics,
    val localDirectoryReader: LocalDirectoryReader,
    val library: AppVideoLibraryCoordinator,
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
