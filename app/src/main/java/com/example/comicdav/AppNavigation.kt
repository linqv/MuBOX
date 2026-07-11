package com.example.comicdav

import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.muBoxColorsFor
import com.example.comicdav.ui.rememberMuBoxColors
import com.example.comicdav.video.MediaKind
import com.example.comicdav.video.mediaKindFor

internal fun mainAppRequestedOrientation(screenRotationLockEnabled: Boolean): Int =
    if (screenRotationLockEnabled) {
        ActivityInfo.SCREEN_ORIENTATION_LOCKED
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

internal fun comicDavRequestedOrientation(
    screenRotationLockEnabled: Boolean,
    isReaderOpen: Boolean,
    readerLandscapeModeEnabled: Boolean,
    readerLandscapeOrientationLocked: Boolean = false,
    forceMainPortrait: Boolean = false,
): Int =
    if (isReaderOpen && readerLandscapeModeEnabled) {
        readerLandscapeRequestedOrientation(readerLandscapeOrientationLocked)
    } else if (forceMainPortrait) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        mainAppRequestedOrientation(screenRotationLockEnabled)
    }

internal fun readerLandscapeRequestedOrientation(readerLandscapeOrientationLocked: Boolean): Int =
    if (readerLandscapeOrientationLocked) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

internal fun shouldForcePortraitAfterReaderLandscapeModeChange(
    currentReaderLandscapeModeEnabled: Boolean,
    nextReaderLandscapeModeEnabled: Boolean,
): Boolean =
    currentReaderLandscapeModeEnabled && !nextReaderLandscapeModeEnabled

internal fun readerLandscapeModeAfterReaderClosed(): Boolean = false

internal fun appTabLabels(): List<String> =
    AppTab.values().map { it.label }

internal fun selectionActionLabelsForLocalVideo(): List<String> =
    listOf("加入影视库", "取消")

internal fun selectionActionLabelsForWebDavVideo(): List<String> =
    listOf("加入影视库", "下载", "取消")

internal fun selectionActionLabelsForVideoLibraryItem(): List<String> =
    listOf("重新提取缩略图", "移除", "删除缩略图", "取消")

internal fun appShellBackgroundColor(colorScheme: ColorScheme) =
    muBoxColorsFor(colorScheme).background

internal fun appShellNavigationBarContainerColor(colorScheme: ColorScheme) =
    muBoxColorsFor(colorScheme).panel

internal fun appShellNavigationBarIndicatorColor(colorScheme: ColorScheme) =
    muBoxColorsFor(colorScheme).panelHigh

internal fun selectionNavigationBarContainerColor(colorScheme: ColorScheme) =
    muBoxColorsFor(colorScheme).panelHigh

internal enum class AppTab {
    SOURCES,
    LIBRARY,
    VIDEO_LIBRARY,
    DOWNLOADS,
    SETTINGS;

    val label: String
        get() = when (this) {
            SOURCES -> ComicDavCopy.sourcesTab
            LIBRARY -> ComicDavCopy.libraryTab
            VIDEO_LIBRARY -> ComicDavCopy.videoLibraryTab
            DOWNLOADS -> ComicDavCopy.downloadsTab
            SETTINGS -> ComicDavCopy.settingsTab
        }

    val iconVector: ImageVector
        get() = when (this) {
            SOURCES -> Icons.Filled.Folder
            LIBRARY -> Icons.AutoMirrored.Filled.LibraryBooks
            VIDEO_LIBRARY -> Icons.Filled.PlayArrow
            DOWNLOADS -> Icons.Filled.Download
            SETTINGS -> Icons.Filled.Settings
        }
}

@Composable
internal fun ComicDavAppShell(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(appShellBackgroundColor(MaterialTheme.colorScheme)),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
        }
        if (bottomBar != null) {
            bottomBar()
        } else {
            val muBoxColors = rememberMuBoxColors()
            androidx.compose.material3.HorizontalDivider(
                thickness = 0.5.dp,
                color = muBoxColors.separator,
            )
            NavigationBar(
                containerColor = appShellNavigationBarContainerColor(MaterialTheme.colorScheme),
                tonalElevation = 0.dp,
            ) {
                AppTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onTabSelected(tab) },
                        icon = {
                            Icon(
                                imageVector = tab.iconVector,
                                contentDescription = tab.label,
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = muBoxColors.mediaAccent,
                            selectedTextColor = muBoxColors.mediaAccent,
                            indicatorColor = appShellNavigationBarIndicatorColor(MaterialTheme.colorScheme),
                            unselectedIconColor = muBoxColors.muted,
                            unselectedTextColor = muBoxColors.muted,
                        ),
                    )
                }
            }
        }
    }
}

internal data class SelectionAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal fun selectionBottomBar(
    selectedWebDavFile: WebDavItem?,
    selectedDirectoryComic: FileDirectoryBrowserItem?,
    selectedDirectoryVideo: FileDirectoryBrowserItem?,
    selectedLibraryItem: LibraryItemWithSources?,
    selectedVideoLibraryItem: VideoLibraryItemWithSources?,
    onDownloadWebDavFile: (WebDavItem) -> Unit,
    onDownloadWebDavVideo: (WebDavItem) -> Unit,
    onAddWebDavFileToLibrary: (WebDavItem) -> Unit,
    onAddWebDavVideoToVideoLibrary: (WebDavItem) -> Unit,
    onAddDirectoryComicToLibrary: (FileDirectoryBrowserItem) -> Unit,
    onAddDirectoryVideoToVideoLibrary: (FileDirectoryBrowserItem) -> Unit,
    onRemoveLibraryItem: (LibraryItemWithSources) -> Unit,
    onRefreshLibraryCover: (LibraryItemWithSources) -> Unit,
    onDownloadLibraryItem: (LibraryItemWithSources) -> Unit,
    onRemoveVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onRefreshVideoLibraryThumbnail: (VideoLibraryItemWithSources) -> Unit,
    onDeleteVideoLibraryThumbnail: (VideoLibraryItemWithSources) -> Unit,
    onCancel: () -> Unit,
): (@Composable () -> Unit)? {
    val actions = when {
        selectedWebDavFile != null -> when (mediaKindFor(name = selectedWebDavFile.name, isDirectory = selectedWebDavFile.isDirectory)) {
            MediaKind.Video -> listOf(
                SelectionAction("加入影视库", Icons.Filled.PlayArrow) { onAddWebDavVideoToVideoLibrary(selectedWebDavFile) },
                SelectionAction("下载", Icons.Filled.Download) { onDownloadWebDavVideo(selectedWebDavFile) },
                SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
            )
            else -> listOf(
                SelectionAction("下载", Icons.Filled.Download) { onDownloadWebDavFile(selectedWebDavFile) },
                SelectionAction("加入书架", Icons.Filled.Book) { onAddWebDavFileToLibrary(selectedWebDavFile) },
                SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
            )
        }
        selectedDirectoryComic != null -> listOf(
            SelectionAction("加入书架", Icons.Filled.Book) { onAddDirectoryComicToLibrary(selectedDirectoryComic) },
            SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
        )
        selectedDirectoryVideo != null -> listOf(
            SelectionAction("加入影视库", Icons.Filled.PlayArrow) { onAddDirectoryVideoToVideoLibrary(selectedDirectoryVideo) },
            SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
        )
        selectedLibraryItem != null -> {
            val isWebDav = selectedLibraryItem.webDavSource != null
            listOf(
                SelectionAction("移除", Icons.Filled.Delete) { onRemoveLibraryItem(selectedLibraryItem) },
                SelectionAction("重新获取封面", Icons.Filled.Refresh, enabled = isWebDav) {
                    onRefreshLibraryCover(selectedLibraryItem)
                },
                SelectionAction("下载", Icons.Filled.Download, enabled = isWebDav) {
                    onDownloadLibraryItem(selectedLibraryItem)
                },
                SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
            )
        }
        selectedVideoLibraryItem != null -> listOf(
            SelectionAction("重新提取缩略图", Icons.Filled.Refresh) {
                onRefreshVideoLibraryThumbnail(selectedVideoLibraryItem)
            },
            SelectionAction("移除", Icons.Filled.Delete) { onRemoveVideoLibraryItem(selectedVideoLibraryItem) },
            SelectionAction("删除缩略图", Icons.Filled.Delete) { onDeleteVideoLibraryThumbnail(selectedVideoLibraryItem) },
            SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
        )
        else -> return null
    }
    return {
        SelectionNavigationBar(actions = actions)
    }
}

@Composable
internal fun SelectionNavigationBar(actions: List<SelectionAction>) {
    val muBoxColors = rememberMuBoxColors()
    androidx.compose.material3.HorizontalDivider(
        thickness = 0.5.dp,
        color = muBoxColors.separator,
    )
    NavigationBar(
        containerColor = selectionNavigationBarContainerColor(MaterialTheme.colorScheme),
        tonalElevation = 0.dp,
    ) {
        actions.forEach { action ->
            NavigationBarItem(
                selected = false,
                enabled = action.enabled,
                onClick = action.onClick,
                icon = {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                    )
                },
                label = {
                    Text(
                        text = action.label,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = muBoxColors.mediaAccent,
                    selectedTextColor = muBoxColors.mediaAccent,
                    indicatorColor = muBoxColors.accentSoft,
                    unselectedIconColor = muBoxColors.accentText,
                    unselectedTextColor = muBoxColors.muted,
                    disabledIconColor = muBoxColors.muted.copy(alpha = 0.38f),
                    disabledTextColor = muBoxColors.muted.copy(alpha = 0.38f),
                ),
            )
        }
    }
}

@Composable
internal fun DataFolderGateScreen(
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = com.example.comicdav.ui.rememberMuBoxColors()
    Column(
        modifier = modifier
            .background(colors.background)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .size(72.dp)
                .background(colors.accentSoft, androidx.compose.foundation.shape.RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = colors.onAccentSoft,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = ComicDavCopy.chooseDataFolderTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = ComicDavCopy.chooseDataFolderBody,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(
            onClick = onChooseFolder,
            modifier = Modifier.defaultMinSize(minWidth = 160.dp, minHeight = 52.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                text = ComicDavCopy.chooseFolder,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

internal fun parentWebDavDirectoryPath(remotePath: String): String {
    val normalized = remotePath.takeIf { it.isNotBlank() } ?: return "/"
    val withoutTrailingSlash = normalized.trimEnd('/')
    val slashIndex = withoutTrailingSlash.lastIndexOf('/')
    return if (slashIndex <= 0) {
        "/"
    } else {
        withoutTrailingSlash.substring(0, slashIndex + 1)
    }
}

internal fun hasActiveAppSelection(
    webDavFileSelected: Boolean,
    directoryComicSelected: Boolean,
    directoryVideoSelected: Boolean,
    libraryItemSelected: Boolean,
    videoLibraryItemSelected: Boolean,
): Boolean =
    webDavFileSelected ||
        directoryComicSelected ||
        directoryVideoSelected ||
        libraryItemSelected ||
        videoLibraryItemSelected

internal fun parentDocumentUriForLocalVideo(videoUri: android.net.Uri): android.net.Uri? {
    return runCatching {
        val documentId = android.provider.DocumentsContract.getDocumentId(videoUri)
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentDocumentId.isBlank()) {
            null
        } else {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(videoUri, parentDocumentId)
        }
    }.getOrNull()
}

internal fun shouldShowWebDavAccountForm(
    isAddingWebDavPath: Boolean,
    editingWebDavSourceId: Long?,
    webDavStatus: String,
): Boolean =
    webDavStatus != com.example.comicdav.feature.webdav.WEB_DAV_STATUS_CONNECTED && (isAddingWebDavPath || editingWebDavSourceId != null)

internal fun com.example.comicdav.data.ComicCacheCategory.cacheLabel(): String =
    when (this) {
        com.example.comicdav.data.ComicCacheCategory.REMOTE_DOWNLOADS -> "远程整本缓存"
        com.example.comicdav.data.ComicCacheCategory.REMOTE_INDEX -> "WebDAV 索引缓存"
        com.example.comicdav.data.ComicCacheCategory.READER_PAGES -> "页面图片缓存"
        com.example.comicdav.data.ComicCacheCategory.TRANSIENT_READER_PAGES -> "临时页面缓存"
        com.example.comicdav.data.ComicCacheCategory.LIBRARY_COVERS -> "书架封面缓存"
        com.example.comicdav.data.ComicCacheCategory.VIDEO_THUMBNAILS -> "影视库缩略图缓存"
        com.example.comicdav.data.ComicCacheCategory.VIDEO_SUBTITLES -> "视频字幕缓存"
        com.example.comicdav.data.ComicCacheCategory.CODE_CACHE -> "运行时代码缓存"
        com.example.comicdav.data.ComicCacheCategory.EXTERNAL_CACHE -> "外部缓存"
        com.example.comicdav.data.ComicCacheCategory.OTHER -> "其他缓存"
    }

internal fun effectiveAvifImagesEnabled(
    settingEnabled: Boolean,
    sdkInt: Int = android.os.Build.VERSION.SDK_INT,
): Boolean = settingEnabled && sdkInt >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
