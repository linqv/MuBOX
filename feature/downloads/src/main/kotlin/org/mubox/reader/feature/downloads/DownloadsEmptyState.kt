package org.mubox.reader.feature.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.mubox.reader.ui.MuBoxEmptyState

@Composable
internal fun DownloadsEmptyState(
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MuBoxEmptyState(
            icon = Icons.Filled.CloudDownload,
            title = "还没有下载",
            body = "从来源下载的漫画和视频会显示在这里",
            actionLabel = "浏览来源",
            onAction = onOpenSources,
        )
    }
}
