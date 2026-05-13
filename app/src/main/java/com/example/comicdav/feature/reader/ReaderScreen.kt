package com.example.comicdav.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (uiState.pageCount > 0) {
                    "${uiState.currentPage + 1} / ${uiState.pageCount}"
                } else {
                    "No comic open"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onClose) {
                Text("Close")
            }
        }

        when {
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = uiState.error)
                }
            }

            uiState.pageCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text(text = "Open a CBZ file")
                    }
                }
            }

            else -> {
                val pagerState = rememberPagerState(
                    initialPage = uiState.currentPage,
                    pageCount = { uiState.pageCount },
                )
                LaunchedEffect(pagerState.currentPage) {
                    onPageChanged(pagerState.currentPage)
                }

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
                            CircularProgressIndicator()
                        } else {
                            AsyncImage(
                                model = pageFile,
                                contentDescription = "Page ${page + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
    }
}
