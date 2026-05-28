package com.example.comicdav.feature.reader

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderImageLoaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun platformReaderImageIncludesAvifByMimeType() {
        assertTrue(isPlatformReaderImage("image/avif", ByteArray(0)))
    }

    @Test
    fun readerImageRequestForcesReaderPlatformDecoder() {
        val request = readerImageRequest(
            context = ApplicationProvider.getApplicationContext(),
            pageFile = temp.newFile("page-1.img"),
        )

        val decoderFactory = request.decoderFactory
        assertNotNull(decoderFactory)
        assertTrue(decoderFactory!!.javaClass.name.contains("PlatformReaderImageDecoder"))
    }

    @Test
    fun platformReaderImageIncludesAvifByHeaderWithoutExtension() {
        assertTrue(isPlatformReaderImage(null, avifHeader()))
    }

    @Test
    fun platformReaderImageIncludesAvifByCompatibleBrandWithoutExtension() {
        assertTrue(isPlatformReaderImage(null, avifCompatibleBrandHeader()))
    }

    @Test
    fun readerScreenDoesNotRouteAvifPagesThroughWebView() {
        val source = readerScreenSource()

        assertFalse(source.contains("ReaderAvifWebViewPage"))
        assertFalse(source.contains("shouldInterceptRequest"))
        assertFalse(source.contains("WebView"))
    }

    @Test
    fun readerChromeDoesNotKeepLegacyVerticalGradientPanels() {
        val source = readerScreenSource()

        assertFalse(source.contains("Brush.verticalGradient"))
    }

    @Test
    fun platformReaderImageRejectsUnknownIsoBmffBrands() {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), '1'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            'm'.code.toByte(), 'i'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(),
        )

        assertFalse(isPlatformReaderImage(null, header))
    }

    private fun readerScreenSource(): String =
        File("src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt").readText()

    private fun avifHeader(): ByteArray =
        byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
        )

    private fun avifCompatibleBrandHeader(): ByteArray =
        byteArrayOf(
            0x00, 0x00, 0x00, 0x20,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'm'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), '1'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
            'm'.code.toByte(), 'i'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(),
        )
}
