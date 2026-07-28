package com.example.comicdav.feature.directorylisting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.ui.rememberMuBoxColors
import java.io.File

fun directorySortFieldLabel(sortField: DirectorySortField): String = when (sortField) {
    DirectorySortField.NAME -> "名称"
    DirectorySortField.SIZE -> "大小"
    DirectorySortField.TYPE -> "文件类型"
}

fun directorySortDirectionActionLabel(direction: DirectorySortDirection): String = when (direction) {
    DirectorySortDirection.ASCENDING -> "切换为降序"
    DirectorySortDirection.DESCENDING -> "切换为升序"
}

fun directorySortButtonDescription(
    sortField: DirectorySortField,
    sortDirection: DirectorySortDirection,
): String = "排序：${directorySortFieldLabel(sortField)}，${if (sortDirection == DirectorySortDirection.ASCENDING) "升序" else "降序"}"

fun compactDirectoryBreadcrumbLabels(labels: List<String>): List<String> =
    if (labels.size <= 2) labels else listOf("…") + labels.takeLast(2)

enum class DirectoryListingViewMode {
    LIST,
    GRID,
}

data class DirectoryVideoThumbnail(
    val version: String,
    val path: String,
    val artworkRevision: Long = 0L,
)

internal const val MAX_DIRECTORY_VIDEO_THUMBNAILS = 256

internal fun putBoundedDirectoryVideoThumbnail(
    thumbnails: Map<String, DirectoryVideoThumbnail>,
    key: String,
    thumbnail: DirectoryVideoThumbnail,
    maxEntries: Int = MAX_DIRECTORY_VIDEO_THUMBNAILS,
): Map<String, DirectoryVideoThumbnail> {
    require(maxEntries > 0)
    val updated = LinkedHashMap<String, DirectoryVideoThumbnail>(
        minOf(thumbnails.size + 1, maxEntries),
    )
    thumbnails.forEach { (existingKey, existingThumbnail) ->
        if (existingKey != key) {
            updated[existingKey] = existingThumbnail
        }
    }
    updated[key] = thumbnail
    while (updated.size > maxEntries) {
        val eldest = updated.entries.iterator()
        eldest.next()
        eldest.remove()
    }
    return updated
}

internal fun directoryVideoArtworkMemoryCacheKey(
    file: File,
    artworkRevision: Long,
): String =
    "${file.absolutePath}#directory-extraction-$artworkRevision:" +
        "${file.length()}:${file.lastModified()}"

internal fun shouldRequestDirectoryVideoThumbnail(
    enabled: Boolean,
    mediaKind: MediaKind,
    hasArtwork: Boolean,
): Boolean = enabled &&
    mediaKind == MediaKind.Video &&
    !hasArtwork

@Composable
fun rememberDirectoryVideoArtworkModel(
    thumbnail: DirectoryVideoThumbnail?,
    expectedVersion: String,
    validationRevision: Long,
): Any? {
    val context = LocalContext.current
    return remember(context, thumbnail, expectedVersion, validationRevision) {
        val currentThumbnail = thumbnail
            ?.takeIf { it.version == expectedVersion }
            ?: return@remember null
        val file = File(currentThumbnail.path)
            .takeIf { it.isFile && it.length() > 0L }
            ?: return@remember null
        if (currentThumbnail.artworkRevision == 0L) {
            file
        } else {
            ImageRequest.Builder(context)
                .data(file)
                .memoryCacheKey(directoryVideoArtworkMemoryCacheKey(file, currentThumbnail.artworkRevision))
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }
    }
}

fun directoryViewModeActionLabel(viewMode: DirectoryListingViewMode): String =
    when (viewMode) {
        DirectoryListingViewMode.LIST -> "切换为网格视图"
        DirectoryListingViewMode.GRID -> "切换为列表视图"
    }

@Composable
fun DirectoryListingTopBar(
    breadcrumbLabels: List<String>,
    searchQuery: String,
    sortField: DirectorySortField,
    sortDirection: DirectorySortDirection,
    onSearchQueryChange: (String) -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    viewMode: DirectoryListingViewMode,
    onToggleViewMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var isSortMenuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val colors = rememberMuBoxColors()

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    LaunchedEffect(breadcrumbLabels) {
        if (isSearchActive) {
            isSearchActive = false
            keyboardController?.hide()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.headerBar,
        contentColor = colors.text,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectoryPathOrSearchField(
                breadcrumbLabels = breadcrumbLabels,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onToggleViewMode,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = when (viewMode) {
                        DirectoryListingViewMode.LIST -> Icons.Filled.GridView
                        DirectoryListingViewMode.GRID -> Icons.AutoMirrored.Filled.ViewList
                    },
                    contentDescription = directoryViewModeActionLabel(viewMode),
                    tint = colors.text,
                )
            }

            Box {
                IconButton(
                    onClick = { isSortMenuExpanded = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = directorySortButtonDescription(sortField, sortDirection),
                        tint = colors.text,
                    )
                }
                DirectorySortMenu(
                    expanded = isSortMenuExpanded,
                    sortField = sortField,
                    sortDirection = sortDirection,
                    onDismiss = { isSortMenuExpanded = false },
                    onSortFieldChange = onSortFieldChange,
                    onToggleSortDirection = onToggleSortDirection,
                )
            }

            IconButton(
                onClick = {
                    if (isSearchActive) {
                        isSearchActive = false
                        onSearchQueryChange("")
                        keyboardController?.hide()
                    } else {
                        isSearchActive = true
                    }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (isSearchActive) "关闭搜索" else "搜索当前目录",
                    tint = colors.text,
                )
            }
        }
    }
}

@Composable
private fun DirectoryPathOrSearchField(
    breadcrumbLabels: List<String>,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.panelHigh,
        contentColor = colors.text,
        border = BorderStroke(1.dp, colors.mediaAccent.copy(alpha = 0.32f)),
    ) {
        if (isSearchActive) {
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(horizontal = 12.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                cursorBrush = SolidColor(colors.mediaAccent),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.muted,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "搜索当前目录",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    }
                },
            )
        } else {
            val breadcrumb = compactDirectoryBreadcrumbLabels(breadcrumbLabels)
                .ifEmpty { listOf("当前目录") }
                .joinToString("  ›  ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.mediaAccent,
                )
                Text(
                    text = breadcrumb,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DirectorySortMenu(
    expanded: Boolean,
    sortField: DirectorySortField,
    sortDirection: DirectorySortDirection,
    onDismiss: () -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DirectorySortField.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(directorySortFieldLabel(option)) },
                onClick = {
                    onDismiss()
                    onSortFieldChange(option)
                },
                modifier = Modifier.heightIn(min = 48.dp),
                trailingIcon = if (option == sortField) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
        HorizontalDivider()
        DirectorySortDirection.entries.forEach { option ->
            val isSelected = option == sortDirection
            DropdownMenuItem(
                text = { Text(if (option == DirectorySortDirection.ASCENDING) "升序" else "降序") },
                onClick = {
                    onDismiss()
                    if (!isSelected) onToggleSortDirection()
                },
                modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = {
                    Icon(
                        imageVector = if (option == DirectorySortDirection.ASCENDING) {
                            Icons.Filled.ArrowUpward
                        } else {
                            Icons.Filled.ArrowDownward
                        },
                        contentDescription = null,
                    )
                },
                trailingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}
