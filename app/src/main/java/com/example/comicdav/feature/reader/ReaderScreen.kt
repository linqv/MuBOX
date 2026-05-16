package com.example.comicdav.feature.reader

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
                var controlsVisible by remember { mutableStateOf(true) }
                val pagerState = rememberPagerState(
                    initialPage = uiState.currentPage,
                    pageCount = { uiState.pageCount },
                )
                LaunchedEffect(pagerState) {
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
                LaunchedEffect(pagerState, uiState.currentPage, uiState.pageCount) {
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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.pageCount) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                            )
                        },
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val pageFile = uiState.pageFiles[page]
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (pageFile == null) {
                                CircularProgressIndicator(color = ReaderOnDark)
                            } else {
                                AsyncImage(
                                    model = pageFile,
                                    contentDescription = "Page ${page + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    onLoading = { onImageLoadStarted(page) },
                                    onSuccess = { onImageLoadSucceeded(page) },
                                    onError = { onImageLoadFailed(page) },
                                )
                            }
                        }
                    }
                }

                if (controlsVisible) {
                    val displayPage = (pagerState.currentPage + 1).coerceIn(1, uiState.pageCount)
                    ReaderTopOverlay(
                        pageCount = uiState.pageCount,
                        onChooseLogFile = onChooseLogFile,
                        onClose = onClose,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    ReaderBottomOverlay(
                        currentPage = displayPage,
                        pageCount = uiState.pageCount,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

private val ReaderOnDark = Color.White
private val ReaderMutedOnDark = Color.White.copy(alpha = 0.74f)
private val ReaderDividerOnDark = Color.White.copy(alpha = 0.18f)

@Composable
private fun ReaderTopOverlay(
    pageCount: Int,
    onChooseLogFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderTopBar(
        title = formatPageCount(pageCount),
        subtitle = "Reader",
        onChooseLogFile = onChooseLogFile,
        onClose = onClose,
        modifier = modifier,
    )
}

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
                        Color.Black.copy(alpha = 0.86f),
                        Color.Black.copy(alpha = 0.54f),
                        Color.Transparent,
                    ),
                ),
            )
            .statusBarsPadding()
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 28.dp),
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
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ReaderChromeButton(text = "Log", onClick = onChooseLogFile)
        ReaderChromeButton(text = "Close", onClick = onClose)
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
        colors = ButtonDefaults.textButtonColors(contentColor = ReaderOnDark),
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
            .padding(start = 20.dp, top = 32.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$currentPage / $pageCount",
                color = ReaderOnDark,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${(progress * 100f).toInt()}%",
                color = ReaderMutedOnDark,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            title = if (isLoading) "Loading comic" else "No comic open",
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
                        text = "Preparing comic",
                        color = ReaderOnDark,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    val percent = (loadingProgress.fraction * 100f).toInt()
                    Text(
                        text = "Downloading comic",
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
                        Text("Cancel")
                    }
                }
            }
        } else {
            Text(
                text = "Open a CBZ file",
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
            title = "Reader error",
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
                text = "Could not open comic",
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

private fun formatPageCount(pageCount: Int): String =
    if (pageCount == 1) {
        "1 page"
    } else {
        "$pageCount pages"
    }
