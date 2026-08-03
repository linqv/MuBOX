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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

@Composable
fun MuBoxGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    if (!colors.isMuBoxDark) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.mediaAccent,
                contentColor = colors.onMediaAccent,
            ),
        ) {
            Text(text)
        }
        return
    }
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusMDp)
    Box(
        modifier = modifier
            .then(
                if (colors.isMuBoxDark) {
                    Modifier.shadow(
                        elevation = 10.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = colors.neonAmbient,
                        spotColor = colors.neonGlow,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(muBoxAccentGradient(colors))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = MuBoxMetrics.MinTouchTargetDp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onMediaAccent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// 首页媒体分区与来源分组标题行（§11.4 Section Title 18sp/600），内容插槽由调用方决定横向或纵向排布。
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
                fontSize = 18.sp,
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

// 首页媒体分区面板（§7.1）：整个分区（标题行 + 内容）收进 16dp 圆角、1dp 边框的统一面板，
// 与页面底色形成方框分层；内容插槽与面板标题保持 12dp 内边距对齐。
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
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusLDp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp)
            .muBoxGlassSurface(colors = colors, shape = shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
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
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 16.sp,
                    color = colors.text,
                    fontWeight = FontWeight.Medium,
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
            content()
        }
    }
}

enum class MuBoxPosterLayout {
    Recent,
    Cover,
}

// 首页媒体卡片：最近记录为“封面 + 内置元信息”，书架与影视库为窄长封面叠字。
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
    val cardModifier = modifier
        .then(
            if (selected && colors.isMuBoxDark) {
                Modifier.shadow(
                    elevation = 8.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = colors.neonAmbient,
                    spotColor = colors.neonGlow,
                )
            } else {
                Modifier
            },
        )
        .muBoxGradientBorder(
            colors = colors,
            shape = shape,
            highlighted = selected,
            width = if (selected) 1.5.dp else 1.dp,
        )
        .clip(shape)
        .background(colors.panelHigh)
        .semantics(mergeDescendants = true) {
            contentDescription = accessibilityLabel
            this.selected = selected
        }
        .combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
        )

    Box(
        modifier = cardModifier
            .aspectRatio(
                coverAspectRatio ?: if (layout == MuBoxPosterLayout.Recent) 0.75f else 0.68f,
            ),
    ) {
        MuBoxPosterArtwork(
            mediaKind = mediaKind,
            coverModel = coverModel,
            kindLabel = kindLabel.takeIf { showKindLabel },
            badge = badge,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.60f to Color.Transparent,
                            1f to colors.overlay.copy(alpha = 0.90f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 7.dp,
                    end = 7.dp,
                    bottom = if (progressFraction == null) 7.dp else 8.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = if (layout == MuBoxPosterLayout.Recent) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = colors.overlayText,
                fontWeight = FontWeight.Medium,
                maxLines = if (layout == MuBoxPosterLayout.Recent) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.overlayText.copy(alpha = 0.74f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (progressFraction != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (layout == MuBoxPosterLayout.Recent) 1.5.dp else 2.dp)
                    .background(colors.muted.copy(alpha = 0.28f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .fillMaxHeight()
                        .background(muBoxAccentGradient(colors)),
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
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.surfaceHover,
                            colors.accentSoft.copy(alpha = 0.52f),
                            colors.backgroundElevated,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (mediaKind) {
                    MuBoxPosterKind.Comic -> Icons.Filled.PhotoLibrary
                    MuBoxPosterKind.Video -> Icons.Filled.Movie
                },
                contentDescription = null,
                tint = colors.muted.copy(alpha = 0.84f),
                modifier = Modifier.size(30.dp),
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
                    containerColor = colors.posterChip,
                    contentColor = colors.onPosterChip,
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

// 本地 / WebDAV 来源行（§8.2/§8.3）：仅展示可用的名称与路径信息，更多操作不依赖长按。
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
    val shape = RoundedCornerShape(MuBoxMetrics.RadiusMDp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .muBoxGlassSurface(colors = colors, shape = shape),
        shape = shape,
        color = Color.Transparent,
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
                tint = colors.onAccentSoft,
                modifier = Modifier.size(32.dp),
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

// 四项等宽底部导航：所有状态保持同一套描边图标，选中仅改变色彩与字重。
@Composable
fun MuBoxBottomNavigation(
    destinations: List<MuBoxNavDestination>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: (MuBoxNavDestination) -> Int = { 0 },
) {
    val colors = rememberMuBoxColors()
    val shape = RoundedCornerShape(
        topStart = MuBoxMetrics.RadiusXlDp,
        topEnd = MuBoxMetrics.RadiusXlDp,
    )
    val surfaceModifier = if (colors.isMuBoxDark) {
        modifier
            .fillMaxWidth()
            .muBoxGlassSurface(colors = colors, shape = shape)
    } else {
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel)
    }
    Column(modifier = surfaceModifier) {
        if (!colors.isMuBoxDark) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MuBoxMetrics.SeparatorThicknessDp)
                    .background(colors.separator),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MuBoxMetrics.MinTouchTargetDp),
        ) {
            destinations.forEach { destination ->
                val isSelected = destination.key == selected
                val count = badgeCount(destination)
                val itemColor = if (isSelected) colors.accentCyan else colors.muted
                val itemShape = RoundedCornerShape(MuBoxMetrics.RadiusLDp)
                val selectedModifier = if (isSelected) {
                    Modifier
                        .muBoxGradientBorder(
                            colors = colors,
                            shape = itemShape,
                            highlighted = true,
                            width = 1.dp,
                        )
                        .clip(itemShape)
                        .background(colors.surfaceActive.copy(alpha = 0.58f))
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp, vertical = 3.dp)
                        .then(selectedModifier)
                        .semantics { this.selected = isSelected }
                        .clickable(role = Role.Tab) { onSelect(destination.key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        BadgedBox(
                            badge = {
                                if (count > 0) {
                                    Badge {
                                        Text(text = if (count > 99) "99+" else count.toString())
                                    }
                                }
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = destination.iconOutlined,
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
            .then(
                if (isError) {
                    Modifier
                } else {
                    Modifier.muBoxGradientBorder(colors = colors, shape = shape)
                },
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = if (isError) BorderStroke(1.dp, colors.errorText.copy(alpha = 0.28f)) else null,
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
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
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
