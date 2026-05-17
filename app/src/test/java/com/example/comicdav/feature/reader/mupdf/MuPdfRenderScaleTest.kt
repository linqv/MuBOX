package com.example.comicdav.feature.reader.mupdf

import org.junit.Assert.assertEquals
import org.junit.Test

class MuPdfRenderScaleTest {
    @Test
    fun scaleIsOneForSmallPage() {
        assertEquals(1f, mupdfRenderScale(width = 1000f, height = 1000f, maxPixels = 4_000_000), 0.0001f)
    }

    @Test
    fun scaleCapsLargePageToMaximumPixels() {
        val scale = mupdfRenderScale(width = 4000f, height = 4000f, maxPixels = 4_000_000)

        assertEquals(0.5f, scale, 0.0001f)
    }

    @Test
    fun scaleHandlesInvalidBounds() {
        assertEquals(1f, mupdfRenderScale(width = 0f, height = 1000f, maxPixels = 4_000_000), 0.0001f)
        assertEquals(1f, mupdfRenderScale(width = 1000f, height = -1f, maxPixels = 4_000_000), 0.0001f)
    }
}
