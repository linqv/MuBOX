package org.mubox.reader.feature.reader

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import org.mubox.reader.ui.MuBoxCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun readerImageRequestUsesCoilDecoderRegistry() {
        val request = readerImageRequest(
            context = ApplicationProvider.getApplicationContext(),
            pageFile = temp.newFile("page-1.img"),
        )

        assertNull(request.decoderFactory)
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
            listOf("横屏", MuBoxCopy.readerClose),
            readerTopBarActionLabels(
                readerLandscapeModeEnabled = false,
                showLandscapeScaleButton = false,
            ),
        )
        assertEquals(
            listOf("横屏", "填充", MuBoxCopy.readerClose),
            readerTopBarActionLabels(
                readerLandscapeModeEnabled = false,
                showLandscapeScaleButton = true,
                readerLandscapeScaleMode = ReaderLandscapeScaleMode.FIT_VIEWPORT,
            ),
        )
    }

    @Test
    fun readerImageRequestsUseVersionedMemoryCacheButNoDiskCache() {
        val pageFile = temp.newFile("page-1.img")

        val request = readerImageRequest(
            context = ApplicationProvider.getApplicationContext(),
            pageFile = pageFile,
        )

        assertEquals(pageFile, request.data)
        assertEquals(CachePolicy.ENABLED, request.memoryCachePolicy)
        assertEquals(CachePolicy.DISABLED, request.diskCachePolicy)
        assertEquals(readerPageFileVersion(pageFile).memoryCacheKey, request.memoryCacheKey)

        pageFile.writeText("new content")
        pageFile.setLastModified(pageFile.lastModified() + 2_000L)
        val nextRequest = readerImageRequest(
            context = ApplicationProvider.getApplicationContext(),
            pageFile = pageFile,
        )
        assertNotEquals(request.memoryCacheKey, nextRequest.memoryCacheKey)
    }

    @Test
    fun bitmapBoundsAreReadWithoutDecodingAndMissingFilesReturnNull() {
        val pageFile = temp.newFile("page.png")
        val bitmap = Bitmap.createBitmap(40, 90, Bitmap.Config.ARGB_8888)
        pageFile.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        bitmap.recycle()

        assertEquals(IntSize(40, 90), readReaderPageDimensions(pageFile))
        assertNull(readReaderPageDimensions(temp.root.resolve("missing.png")))
    }

    @Test
    fun continuousPageHeightMatchesFullWidthAspectRatioBeforeImageDecode() {
        assertEquals(
            1_000,
            readerContinuousPageHeightPx(
                viewportSize = IntSize(500, 900),
                imageSize = IntSize(1_000, 2_000),
                landscapeScaleMode = ReaderLandscapeScaleMode.FIT_VIEWPORT,
            ),
        )
        assertEquals(
            900,
            readerContinuousPageHeightPx(
                viewportSize = IntSize(1_600, 900),
                imageSize = IntSize(1_000, 800),
                landscapeScaleMode = ReaderLandscapeScaleMode.FIT_VIEWPORT,
            ),
        )
        assertNull(
            readerContinuousPageHeightPx(
                viewportSize = IntSize(500, 900),
                imageSize = null,
                landscapeScaleMode = ReaderLandscapeScaleMode.FIT_VIEWPORT,
            ),
        )
    }

    @Test
    fun decodedDownsamplingKeepsGeometryButRotationChangesIt() {
        assertEquals(false, readerImageAspectRatioDiffers(IntSize(1_000, 1_500), IntSize(333, 500)))
        assertEquals(true, readerImageAspectRatioDiffers(IntSize(1_000, 1_500), IntSize(1_500, 1_000)))
    }
}
