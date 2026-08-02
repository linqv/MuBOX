package org.mubox.reader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreenSettingsTest {
    @Test
    fun readerZoomScaleIsClampedToSupportedRange() {
        val viewport = IntSize(width = 1_000, height = 1_500)

        val zoomedOut = readerZoomStateAfterTransform(
            current = ReaderZoomState(scale = 2f, offsetX = 20f, offsetY = 30f),
            zoomChange = 0.1f,
            pan = Offset.Zero,
            viewportSize = viewport,
        )
        val zoomedIn = readerZoomStateAfterTransform(
            current = ReaderZoomState(scale = 2f),
            zoomChange = 10f,
            pan = Offset.Zero,
            viewportSize = viewport,
        )

        assertEquals(1f, zoomedOut.scale, 0.001f)
        assertEquals(0f, zoomedOut.offsetX, 0.001f)
        assertEquals(0f, zoomedOut.offsetY, 0.001f)
        assertEquals(4f, zoomedIn.scale, 0.001f)
    }

    @Test
    fun readerZoomPanIsClampedToScaledViewport() {
        val viewport = IntSize(width = 1_000, height = 1_500)

        val state = readerZoomStateAfterTransform(
            current = ReaderZoomState(scale = 2f),
            zoomChange = 1f,
            pan = Offset(x = 900f, y = -900f),
            viewportSize = viewport,
        )

        assertEquals(500f, state.offsetX, 0.001f)
        assertEquals(-750f, state.offsetY, 0.001f)
    }

    @Test
    fun zoomedReaderViewportSuspendsUnderlyingPageScrolling() {
        assertTrue(readerViewportScrollEnabled(ReaderZoomState()))
        assertFalse(readerViewportScrollEnabled(ReaderZoomState(scale = 2f)))
    }

    @Test
    fun continuousLandscapePageFitsWholeViewportWhenFillWidthWouldOverflowHeight() {
        val scale = readerPageScalePolicy(
            fillWidth = true,
            viewportSize = IntSize(width = 2400, height = 1080),
            imageSize = IntSize(width = 1600, height = 1200),
        )

        assertEquals(ReaderPageScalePolicy.FitViewport, scale)
    }

    @Test
    fun continuousPortraitPageKeepsFillWidthForStripReading() {
        val scale = readerPageScalePolicy(
            fillWidth = true,
            viewportSize = IntSize(width = 2400, height = 1080),
            imageSize = IntSize(width = 900, height = 1600),
        )

        assertEquals(ReaderPageScalePolicy.FillWidth, scale)
    }
}
