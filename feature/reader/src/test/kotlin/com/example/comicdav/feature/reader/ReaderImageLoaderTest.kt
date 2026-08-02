package com.example.comicdav.feature.reader

import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import com.example.comicdav.ui.ComicDavCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun defaultLandscapeContinuousPageFitsViewportWhenFillWidthWouldExceedScreen() {
        val policy = readerPageScalePolicy(
            fillWidth = true,
            viewportSize = IntSize(width = 1600, height = 900),
            imageSize = IntSize(width = 1000, height = 800),
            landscapeScaleMode = ReaderLandscapeScaleMode.FIT_VIEWPORT,
        )

        assertEquals(ReaderPageScalePolicy.FitViewport, policy)
    }

    @Test
    fun fillLandscapeContinuousPageKeepsImageFullWidthAndUncropped() {
        val policy = readerPageScalePolicy(
            fillWidth = true,
            viewportSize = IntSize(width = 1600, height = 900),
            imageSize = IntSize(width = 1000, height = 800),
            landscapeScaleMode = ReaderLandscapeScaleMode.FILL_WIDTH,
        )

        assertEquals(ReaderPageScalePolicy.FillWidth, policy)
    }

    @Test
    fun landscapeScaleButtonTogglesBetweenFillAndFitViewport() {
        assertEquals("填充", readerLandscapeScaleButtonLabel(ReaderLandscapeScaleMode.FIT_VIEWPORT))
        assertEquals(
            ReaderLandscapeScaleMode.FILL_WIDTH,
            readerLandscapeScaleButtonTarget(ReaderLandscapeScaleMode.FIT_VIEWPORT),
        )

        assertEquals("适应", readerLandscapeScaleButtonLabel(ReaderLandscapeScaleMode.FILL_WIDTH))
        assertEquals(
            ReaderLandscapeScaleMode.FIT_VIEWPORT,
            readerLandscapeScaleButtonTarget(ReaderLandscapeScaleMode.FILL_WIDTH),
        )
    }

    @Test
    fun topBarActionsIncludeLandscapeScaleButtonOnlyWhenVisible() {
        assertEquals(
            listOf("横屏", ComicDavCopy.readerClose),
            readerTopBarActionLabels(
                readerLandscapeModeEnabled = false,
                showLandscapeScaleButton = false,
            ),
        )
        assertEquals(
            listOf("横屏", "填充", ComicDavCopy.readerClose),
            readerTopBarActionLabels(
                readerLandscapeModeEnabled = false,
                showLandscapeScaleButton = true,
                readerLandscapeScaleMode = ReaderLandscapeScaleMode.FIT_VIEWPORT,
            ),
        )
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

    @Test
    fun detectsGifFromHeaderWhenCachedPageHasGenericExtension() {
        assertTrue(isPlatformAnimatedReaderImage(mimeType = null, header = "GIF89a".encodeToByteArray()))
    }

    @Test
    fun detectsWebpFromHeaderWhenCachedPageHasGenericExtension() {
        val header = "RIFF\u0000\u0000\u0000\u0000WEBP".encodeToByteArray()

        assertTrue(isPlatformAnimatedReaderImage(mimeType = null, header = header))
    }

    @Test
    fun ignoresStaticImageHeaders() {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        assertFalse(isPlatformAnimatedReaderImage(mimeType = null, header = header))
    }

    @Test
    fun readerImageRequestsDoNotKeepDecodedPagesInCoilCaches() {
        val pageFile = temp.newFile("page-1.img")

        val request = readerImageRequest(
            context = ApplicationProvider.getApplicationContext(),
            pageFile = pageFile,
        )

        assertEquals(pageFile, request.data)
        assertEquals(CachePolicy.DISABLED, request.memoryCachePolicy)
        assertEquals(CachePolicy.DISABLED, request.diskCachePolicy)
    }

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
