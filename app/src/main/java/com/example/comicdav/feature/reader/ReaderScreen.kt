package com.example.comicdav.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.comicdav.data.ReadingDirection
import com.example.comicdav.ui.ComicDavCopy
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    onPageChanged: (Int) -> Unit,
    onPageDemanded: (Int, String) -> Unit,
    onImageLoadStarted: (Int) -> Unit,
    onImageLoadSucceeded: (Int) -> Unit,
    onImageLoadFailed: (Int) -> Unit,
    onChooseLogFile: () -> Unit,
    loadingProgress: ReaderLoadingProgress? = null,
    onCancelLoading: (() -> Unit)? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    autoPageEnabled: Boolean = false,
    onAutoPageEnabledChange: (Boolean) -> Unit = {},
    autoPageIntervalMillis: Long = 0L,
    volumeKeysTurnPages: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            uiState.error != null -> {
                ReaderErrorState(
                    message = uiState.error,
                    onChooseLogFile = onChooseLogFile,
                    onClose = onClose,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.pageCount == 0 -> {
                ReaderEmptyOrLoadingState(
                    isLoading = uiState.isLoading,
                    loadingProgress = loadingProgress,
                    onCancelLoading = onCancelLoading,
                    onChooseLogFile = onChooseLogFile,
                    onClose = onClose,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                val readerStateKey = readerScrollStateKey(uiState)
                var controlsVisible by remember { mutableStateOf(true) }
                val scope = rememberCoroutineScope()
                val focusRequester = remember { FocusRequester() }
                val pagerState = key(readerStateKey) {
                    rememberPagerState(
                        initialPage = uiState.currentPage,
                        pageCount = { uiState.pageCount },
                    )
                }
                val continuousListState = key(readerStateKey) {
                    rememberLazyListState(
                        initialFirstVisibleItemIndex = uiState.currentPage,
                    )
                }
                val isContinuousVertical = readingDirection == ReadingDirection.VERTICAL_CONTINUOUS
                LaunchedEffect(volumeKeysTurnPages) {
                    if (volumeKeysTurnPages) {
                        runCatching { focusRequester.requestFocus() }
                        ReaderDiagnosticLog.event("reader_volume_key_turn_pages_enabled")
                    }
                }
                LaunchedEffect(pagerState, isContinuousVertical) {
                    if (isContinuousVertical) return@LaunchedEffect
                    snapshotFlow {
                        reportableSettledPage(
                            currentPage = pagerState.currentPage,
                            settledPage = pagerState.settledPage,
                        )
                    }
                        .reportableReaderPageChanges()
                        .collect { page ->
                            ReaderDiagnosticLog.event("pager_report_page page=$page")
                            onPageChanged(page)
                        }
                }
                LaunchedEffect(pagerState, uiState.currentPage, uiState.pageCount, isContinuousVertical) {
                    if (isContinuousVertical) return@LaunchedEffect
                    snapshotFlow {
                        ReaderPagerSnapshot(
                            currentPage = pagerState.currentPage,
                            settledPage = pagerState.settledPage,
                            targetPage = pagerState.targetPage,
                            offsetFraction = pagerState.currentPageOffsetFraction,
                            isScrollInProgress = pagerState.isScrollInProgress,
                            uiCurrentPage = uiState.currentPage,
                            pageCount = uiState.pageCount,
                        )
                    }
                        .distinctUntilChanged()
                        .collect { snapshot ->
                            ReaderDiagnosticLog.event(formatPagerSnapshot(snapshot))
                            reportablePagerDemandPages(snapshot).forEach { demand ->
                                onPageDemanded(demand.page, demand.source)
                            }
                        }
                }
                LaunchedEffect(continuousListState, isContinuousVertical) {
                    if (!isContinuousVertical) return@LaunchedEffect
                    snapshotFlow {
                        reportableContinuousPageChange(
                            firstVisiblePage = continuousListState.firstVisibleItemIndex,
                            isScrollInProgress = continuousListState.isScrollInProgress,
                            pageCount = uiState.pageCount,
                        )
                    }
                        .distinctUntilChanged()
                        .collect { page ->
                            if (page == null) return@collect
                            ReaderDiagnosticLog.event("continuous_scroll_report_page page=$page")
                            onPageChanged(page)
                        }
                }
                LaunchedEffect(continuousListState, isContinuousVertical) {
                    if (!isContinuousVertical) return@LaunchedEffect
                    snapshotFlow {
                        continuousListState.layoutInfo.visibleItemsInfo.map { it.index }.distinct()
                    }
                        .distinctUntilChanged()
                        .collect { visiblePages ->
                            visiblePages.forEach { page ->
                                onPageDemanded(page, "continuous_visible")
                            }
                        }
                }
                LaunchedEffect(
                    autoPageEnabled,
                    autoPageIntervalMillis,
                    pagerState,
                    continuousListState,
                    uiState.pageCount,
                    isContinuousVertical,
                ) {
                    while (autoPageEnabled && autoPageIntervalMillis > 0L && uiState.pageCount > 1) {
                        delay(autoPageIntervalMillis.coerceAtLeast(1_000L))
                        val targetPage = autoPageTargetPage(
                            currentPage = if (isContinuousVertical) {
                                continuousListState.firstVisibleItemIndex
                            } else {
                                pagerState.currentPage
                            },
                            pageCount = uiState.pageCount,
                            isScrollInProgress = if (isContinuousVertical) {
                                continuousListState.isScrollInProgress
                            } else {
                                pagerState.isScrollInProgress
                            },
                        )
                        if (targetPage != null) {
                            if (isContinuousVertical) {
                                continuousListState.animateScrollToItem(targetPage)
                            } else {
                                pagerState.animateScrollToPage(targetPage)
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable(enabled = volumeKeysTurnPages)
                        .onPreviewKeyEvent { event ->
                            if (!volumeKeysTurnPages || event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            val targetPage = volumeKeyTargetPage(
                                currentPage = if (isContinuousVertical) {
                                    continuousListState.firstVisibleItemIndex
                                } else {
                                    pagerState.currentPage
                                },
                                pageCount = uiState.pageCount,
                                key = event.key,
                            ) ?: return@onPreviewKeyEvent false
                            val currentPage = if (isContinuousVertical) {
                                continuousListState.firstVisibleItemIndex
                            } else {
                                pagerState.currentPage
                            }
                            if (targetPage == currentPage) return@onPreviewKeyEvent true
                            scope.launch {
                                if (isContinuousVertical) {
                                    continuousListState.animateScrollToItem(targetPage)
                                } else {
                                    pagerState.animateScrollToPage(targetPage)
                                }
                            }
                            true
                        }
                        .pointerInput(uiState.pageCount) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                            )
                        },
                ) {
                    if (isContinuousVertical) {
                        LazyColumn(
                            state = continuousListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(uiState.pageCount) { page ->
                                ReaderImagePage(
                                    page = page,
                                    pageFile = uiState.pageFiles[page],
                                    onImageLoadStarted = onImageLoadStarted,
                                    onImageLoadSucceeded = onImageLoadSucceeded,
                                    onImageLoadFailed = onImageLoadFailed,
                                    fillWidth = true,
                                )
                            }
                        }
                    } else if (readingDirection == ReadingDirection.VERTICAL) {
                        VerticalPager(
                            state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        ReaderImagePage(
                            page = page,
                            pageFile = uiState.pageFiles[page],
                            onImageLoadStarted = onImageLoadStarted,
                                onImageLoadSucceeded = onImageLoadSucceeded,
                                onImageLoadFailed = onImageLoadFailed,
                            )
                        }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                            reverseLayout = readingDirection == ReadingDirection.RIGHT_TO_LEFT,
                        ) { page ->
                            ReaderImagePage(
                                page = page,
                                pageFile = uiState.pageFiles[page],
                                onImageLoadStarted = onImageLoadStarted,
                                onImageLoadSucceeded = onImageLoadSucceeded,
                                onImageLoadFailed = onImageLoadFailed,
                            )
                        }
                    }
                }

                if (controlsVisible) {
                    val displayPage = if (isContinuousVertical) {
                        continuousListState.firstVisibleItemIndex + 1
                    } else {
                        pagerState.currentPage + 1
                    }.coerceIn(1, uiState.pageCount)
                    ReaderTopBar(
                        title = "正在阅读",
                        subtitle = "共 ${uiState.pageCount} 页",
                        onChooseLogFile = onChooseLogFile,
                        onClose = onClose,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    ReaderBottomOverlay(
                        currentPage = displayPage,
                        pageCount = uiState.pageCount,
                        autoPageEnabled = autoPageEnabled,
                        onAutoPageEnabledChange = onAutoPageEnabledChange,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

internal fun autoPageTargetPage(
    currentPage: Int,
    pageCount: Int,
    isScrollInProgress: Boolean,
): Int? {
    if (isScrollInProgress || pageCount <= 1) return null
    val nextPage = currentPage + 1
    return nextPage.takeIf { it < pageCount }
}

internal fun volumeKeyTargetPage(
    currentPage: Int,
    pageCount: Int,
    key: Key,
): Int? {
    if (pageCount <= 0) return null
    return when (key) {
        Key.VolumeDown -> currentPage + 1
        Key.VolumeUp -> currentPage - 1
        else -> return null
    }.coerceIn(0, pageCount - 1)
}

internal fun readerScrollStateKey(uiState: ReaderUiState): String =
    uiState.readerKey ?: "unkeyed-${uiState.pageCount}"

internal fun reportableContinuousPageChange(
    firstVisiblePage: Int,
    isScrollInProgress: Boolean,
    pageCount: Int,
): Int? {
    if (!isScrollInProgress) return null
    return firstVisiblePage.takeIf { it in 0 until pageCount }
}

@Composable
private fun ReaderImagePage(
    page: Int,
    pageFile: java.io.File?,
    onImageLoadStarted: (Int) -> Unit,
    onImageLoadSucceeded: (Int) -> Unit,
    onImageLoadFailed: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    var continuousImageReady by remember(pageFile?.absolutePath, fillWidth) {
        mutableStateOf(!fillWidth || pageFile == null)
    }
    Box(
        modifier = if (fillWidth) {
            modifier
                .fillMaxWidth()
                .background(Color.Black)
        } else {
            modifier.fillMaxSize()
        },
        contentAlignment = Alignment.Center,
    ) {
        if (pageFile == null) {
            Box(
                modifier = if (fillWidth) {
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                } else {
                    Modifier.fillMaxSize()
                },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ReaderOnDark)
            }
        } else {
            AsyncImage(
                model = pageFile,
                contentDescription = "第 ${page + 1} 页",
                modifier = if (fillWidth) {
                    Modifier
                        .fillMaxWidth()
                        .then(if (continuousImageReady) Modifier else Modifier.height(ContinuousPageLoadingHeight))
                } else {
                    Modifier.fillMaxSize()
                },
                contentScale = if (fillWidth) ContentScale.FillWidth else ContentScale.Fit,
                onLoading = {
                    continuousImageReady = false
                    onImageLoadStarted(page)
                },
                onSuccess = {
                    continuousImageReady = true
                    onImageLoadSucceeded(page)
                },
                onError = {
                    continuousImageReady = false
                    onImageLoadFailed(page)
                },
            )
        }
    }
}

private val ContinuousPageLoadingHeight = 320.dp
private val ReaderOnDark = Color.White
private val ReaderMutedOnDark = Color.White.copy(alpha = 0.74f)
private val ReaderDividerOnDark = Color.White.copy(alpha = 0.18f)
private val ReaderPanelOnDark = Color.Black.copy(alpha = 0.62f)

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String? = null,
    onChooseLogFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.92f),
                        Color.Black.copy(alpha = 0.68f),
                        Color.Transparent,
                    ),
                ),
            )
            .statusBarsPadding()
            .padding(start = 18.dp, top = 10.dp, end = 12.dp, bottom = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = ReaderOnDark,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = ReaderMutedOnDark,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ReaderChromeButton(text = ComicDavCopy.readerLog, onClick = onChooseLogFile)
        ReaderChromeButton(text = ComicDavCopy.readerClose, onClick = onClose)
    }
}

@Composable
private fun ReaderChromeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = ReaderOnDark,
            containerColor = ReaderPanelOnDark,
        ),
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReaderBottomOverlay(
    currentPage: Int,
    pageCount: Int,
    autoPageEnabled: Boolean,
    onAutoPageEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = currentPage.toFloat() / pageCount.toFloat()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.58f),
                        Color.Black.copy(alpha = 0.88f),
                    ),
                ),
            )
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 34.dp, end = 20.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "第 $currentPage 页",
                color = ReaderOnDark,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "共 $pageCount 页 · ${(progress * 100f).toInt()}%",
                color = ReaderMutedOnDark,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "自动翻页",
                    color = ReaderOnDark,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (autoPageEnabled) "已开启，按设置速度前进" else "已关闭",
                    color = ReaderMutedOnDark,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = autoPageEnabled,
                onCheckedChange = onAutoPageEnabledChange,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = ReaderOnDark,
            trackColor = ReaderDividerOnDark,
        )
    }
}

@Composable
private fun ReaderEmptyOrLoadingState(
    isLoading: Boolean,
    loadingProgress: ReaderLoadingProgress?,
    onCancelLoading: (() -> Unit)?,
    onChooseLogFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ReaderTopBar(
            title = if (isLoading) ComicDavCopy.readerLoading else "未打开漫画",
            subtitle = null,
            onChooseLogFile = onChooseLogFile,
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (isLoading) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (loadingProgress == null) {
                    CircularProgressIndicator(color = ReaderOnDark)
                    Text(
                        text = ComicDavCopy.readerLoading,
                        color = ReaderOnDark,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    val percent = (loadingProgress.fraction * 100f).toInt()
                    Text(
                        text = ComicDavCopy.readerDownloading,
                        color = ReaderOnDark,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "${loadingProgress.label} ($percent%)",
                        color = ReaderMutedOnDark,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { loadingProgress.fraction },
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .height(6.dp),
                        color = ReaderOnDark,
                        trackColor = ReaderDividerOnDark,
                    )
                }

                if (onCancelLoading != null) {
                    Button(
                        onClick = onCancelLoading,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ReaderOnDark,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Text("取消")
                    }
                }
            }
        } else {
            Text(
                text = "从来源或书架打开漫画",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                color = ReaderMutedOnDark,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReaderErrorState(
    message: String,
    onChooseLogFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ReaderTopBar(
            title = ComicDavCopy.readerError,
            subtitle = null,
            onChooseLogFile = onChooseLogFile,
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = ComicDavCopy.readerError,
                color = ReaderOnDark,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                color = ReaderMutedOnDark,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
