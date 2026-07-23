package com.example.comicdav.feature.reader.mupdf

import com.example.comicdav.core.model.media.LocalDocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MuPdfRenderScaleTest {
    @Test
    fun scaleUpsSmallPageToReaderWidth() {
        assertEquals(1.6f, mupdfRenderScale(width = 1000f, height = 1000f, maxPixels = 4_000_000), 0.0001f)
    }

    @Test
    fun scaleUpsPdfPageToReaderWidthWhenWithinPixelBudget() {
        val scale = mupdfRenderScale(width = 612f, height = 792f, maxPixels = 4_000_000)

        assertEquals(1600f / 612f, scale, 0.0001f)
        assertTrue(612f * scale * 792f * scale <= 4_000_000f)
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

    @Test
    fun openErrorMapsDrmFailure() {
        val error = mapMuPdfOpenError(LocalDocumentFormat.Pdf, Exception("DRM protected"))

        assertEquals("暂不支持受 DRM 保护的文件", error.message)
    }

    @Test
    fun openErrorMapsPasswordFailure() {
        val error = mapMuPdfOpenError(LocalDocumentFormat.Epub, Exception("password required"))

        assertEquals("暂不支持加密或需要密码的文件", error.message)
    }

    @Test
    fun openErrorMapsEncryptedFailure() {
        val error = mapMuPdfOpenError(LocalDocumentFormat.Mobi, Exception("encrypted document"))

        assertEquals("暂不支持加密或需要密码的文件", error.message)
    }

    @Test
    fun openErrorMapsGenericFailureWithFormatName() {
        val error = mapMuPdfOpenError(LocalDocumentFormat.Azw3, Exception("broken file"))

        assertEquals("无法打开这个 AZW3 文件", error.message)
    }

    @Test
    fun openErrorPreservesExplicitNoPagesFailure() {
        val explicit = IllegalStateException("这个文件没有可读取的页面")

        val error = mapMuPdfOpenError(LocalDocumentFormat.Pdf, explicit)

        assertSame(explicit, error)
    }

    @Test
    fun openErrorPreservesExplicitPasswordFailure() {
        val explicit = IllegalStateException("暂不支持加密或需要密码的文件")

        val error = mapMuPdfOpenError(LocalDocumentFormat.Pdf, explicit)

        assertSame(explicit, error)
    }
}
