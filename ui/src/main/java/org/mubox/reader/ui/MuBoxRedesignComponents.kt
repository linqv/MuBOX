package org.mubox.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import org.mubox.reader.ui.icons.MuBoxEditorialIcons
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

@Composable
fun MuBoxGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusMDp)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = MuBoxMetrics.MinTouchTargetDp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.mediaAccent,
            contentColor = colors.onMediaAccent,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp,
            vertical = 10.dp,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// 媒体分区与来源分组标题行，字号由主题统一管理。
@Composable
fun MuBoxSection(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = rememberMuBoxColors()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MuBoxMetrics.MinTouchTargetDp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionText != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accentText),
                ) {
                    Text(text = actionText, maxLines = 1)
                }
            }
        }
        content()
    }
}

// 开放式社论分区：无外层玻璃/深色容器卡片，通栏排布配合细微分割线与清晰层级。
@Composable
fun MuBoxPanelSection(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MuBoxMetrics.MinTouchTargetDp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionText != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = MuBoxMetrics.MinTouchTargetDp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp,
                        vertical = 0.dp,
                    ),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accentText),
                ) {
                    if (actionIcon != null) {
                        Icon(
                            imageVector = actionIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(18.dp),
                        )
                    }
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            thickness = 0.5.dp,
            color = colors.border.copy(alpha = 0.5f),
        )
        content()
    }
}

enum class MuBoxPosterLayout {
    Recent,
    Cover,
}

// 社论媒体海报卡片：纯净封面（6dp圆角微边框，无遮挡渐变）、底部陶土进度条、下方独立图说元信息。
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MuBoxMediaPosterCard(
    title: String,
    mediaKind: MuBoxPosterKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    coverModel: Any? = null,
    progress: Float? = null,
    badge: String? = null,
    selected: Boolean = false,
    layout: MuBoxPosterLayout = MuBoxPosterLayout.Cover,
    coverAspectRatio: Float? = null,
    showKindLabel: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusSDp)
    val kindLabel = muBoxPosterKindLabel(mediaKind)
    val progressFraction = progress?.coerceIn(0f, 1f)
    val progressPercent = progressFraction?.let { (it * 100).roundToInt() }
    val accessibilityLabel = buildString {
        append(title).append('，').append(kindLabel)
        if (progressPercent != null) {
            append('，')
            append(if (mediaKind == MuBoxPosterKind.Comic) "已阅读" else "已观看")
            append(' ').append(progressPercent).append('%')
        }
    }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                this.selected = selected
            }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    coverAspectRatio ?: if (layout == MuBoxPosterLayout.Recent) 0.75f else 0.68f,
                )
                .then(
                    if (selected) {
                        Modifier.border(2.dp, colors.selectedBorder, shape)
                    } else {
                        Modifier.border(0.5.dp, colors.border.copy(alpha = 0.7f), shape)
                    },
                )
                .clip(shape)
                .background(colors.panelHigh),
        ) {
            MuBoxPosterArtwork(
                mediaKind = mediaKind,
                coverModel = coverModel,
                kindLabel = kindLabel.takeIf { showKindLabel },
                badge = badge,
            )
            if (progressFraction != null && progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(colors.border.copy(alpha = 0.4f)),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(colors.mediaAccent),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MuBoxPosterArtwork(
    mediaKind: MuBoxPosterKind,
    coverModel: Any?,
    kindLabel: String?,
    badge: String?,
) {
    val colors = rememberMuBoxColors()
    if (coverModel != null) {
        AsyncImage(
            model = coverModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceSecondary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (mediaKind) {
                    MuBoxPosterKind.Comic -> MuBoxEditorialIcons.ComicBook
                    MuBoxPosterKind.Video -> MuBoxEditorialIcons.CinemaVideo
                },
                contentDescription = null,
                tint = colors.muted.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
    if (!kindLabel.isNullOrBlank() || !badge.isNullOrBlank()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!kindLabel.isNullOrBlank()) {
                MuBoxPosterChip(
                    text = kindLabel,
                    containerColor = colors.panelHigh,
                    contentColor = colors.text,
                )
            }
            if (!badge.isNullOrBlank()) {
                MuBoxPosterChip(
                    text = badge,
                    containerColor = colors.accentSoft,
                    contentColor = colors.onAccentSoft,
                )
            }
        }
    }
}

// 本地 / WebDAV 来源行：克制圆角与发丝边框，炭黑排印。
@Composable
fun MuBoxSourceRow(
    icon: ImageVector,
    name: String,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    moreContentDescription: String = "更多操作",
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusSDp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, colors.border.copy(alpha = 0.7f), shape),
        shape = shape,
        color = colors.panel,
        contentColor = colors.text,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = moreContentDescription,
                    tint = colors.muted,
                )
            }
        }
    }
}

// 底部导航目的地模型（§12.5），同一模型也可供平板 Navigation Rail 使用。
data class MuBoxNavDestination(
    val key: String,
    val label: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
)

// 四项等宽底部导航：平直克制面板，极细分割线，陶土强调色指示。
@Composable
fun MuBoxBottomNavigation(
    destinations: List<MuBoxNavDestination>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: (MuBoxNavDestination) -> Int = { 0 },
) {
    val colors = rememberMuBoxColors()
    val surfaceModifier = modifier
        .fillMaxWidth()
        .background(colors.panel)

    Column(modifier = surfaceModifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.border.copy(alpha = 0.5f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MuBoxMetrics.MinTouchTargetDp),
        ) {
            destinations.forEach { destination ->
                val isSelected = destination.key == selected
                val count = badgeCount(destination)
                val itemColor = if (isSelected) colors.accentText else colors.muted
                val iconVector = if (isSelected) destination.iconFilled else destination.iconOutlined
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = isSelected }
                        .clickable(role = Role.Tab) { onSelect(destination.key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        BadgedBox(
                            badge = {
                                if (count > 0) {
                                    Badge(
                                        containerColor = colors.accentSoft,
                                        contentColor = colors.onAccentSoft,
                                    ) {
                                        Text(text = if (count > 99) "99+" else count.toString())
                                    }
                                }
                            },
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MuBoxMetrics.RadiusMDp))
                                    .background(
                                        if (isSelected) colors.accentSoft
                                        else Color.Transparent,
                                    )
                                    .padding(horizontal = 14.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = iconVector,
                                    contentDescription = null,
                                    tint = itemColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = itemColor,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        )
    }
}

// 标题下方的内联反馈消息（§7.6）：错误用语义化错误色，可关闭但不抢夺输入焦点。
@Composable
fun MuBoxInlineMessage(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val containerColor = if (isError) colors.errorSurface else colors.panelHigh
    val contentColor = if (isError) colors.errorText else colors.text
    val shape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(
            0.5.dp,
            if (isError) colors.errorText.copy(alpha = 0.28f) else colors.border.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Filled.Error else Icons.Filled.Info,
                contentDescription = if (isError) "错误" else "提示",
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = contentColor,
                )
            }
        }
    }
}

@Composable
private fun MuBoxPosterChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(MuBoxMetrics.RadiusXsDp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private fun muBoxPosterKindLabel(kind: MuBoxPosterKind): String =
    when (kind) {
        MuBoxPosterKind.Comic -> "漫画"
        MuBoxPosterKind.Video -> "影视"
    }
