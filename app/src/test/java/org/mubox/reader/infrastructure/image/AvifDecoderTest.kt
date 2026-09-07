package org.mubox.reader.infrastructure.image

import android.graphics.Bitmap
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size as CoilSize
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig
import com.radzivon.bartoshyk.avif.coder.ScaleMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mubox.reader.core.diagnostics.DiagnosticCategory
import org.mubox.reader.core.diagnostics.Diagnostics
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AvifDecoderTest {

    private class RecordingDiagnostics : Diagnostics {
        data class ErrorEntry(val category: DiagnosticCategory, val event: String, val error: Throwable?)
        val errors = mutableListOf<ErrorEntry>()

        override fun error(category: DiagnosticCategory, event: String, error: Throwable?) {
            errors.add(ErrorEntry(category, event, error))
        }

        override fun fatal(category: DiagnosticCategory, event: String, error: Throwable) {}
        override fun fatalBlocking(category: DiagnosticCategory, event: String, error: Throwable) {}
    }

    private class FakeAvifCoder(
        var reportedSize: Size? = Size(100, 100),
        var returnedBitmap: Bitmap? = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
        var getSizeError: Throwable? = null,
        var decodeError: Throwable? = null,
    ) : AvifImageCoder {
        var decodeCalled = false
        var decodeSampledCalled = false
        var lastPreferredColorConfig: PreferredColorConfig? = null
        var lastScaleMode: ScaleMode? = null
        var lastTargetWidth: Int = 0
        var lastTargetHeight: Int = 0

        override fun getSize(byteArray: ByteArray): Size? {
            getSizeError?.let { throw it }
            return reportedSize
        }

        override fun decode(byteArray: ByteArray, preferredColorConfig: PreferredColorConfig): Bitmap? {
            decodeCalled = true
            lastPreferredColorConfig = preferredColorConfig
            decodeError?.let { throw it }
            return returnedBitmap
        }

        override fun decodeSampled(
            byteArray: ByteArray,
            scaledWidth: Int,
            scaledHeight: Int,
            preferredColorConfig: PreferredColorConfig,
            scaleMode: ScaleMode,
        ): Bitmap? {
            decodeSampledCalled = true
            lastTargetWidth = scaledWidth
            lastTargetHeight = scaledHeight
            lastPreferredColorConfig = preferredColorConfig
            lastScaleMode = scaleMode
            decodeError?.let { throw it }
            return returnedBitmap
        }
    }

    private fun createFtypBox(
        majorBrand: String,
        minorVersion: Int = 0,
        compatibleBrands: List<String> = emptyList(),
    ): ByteArray {
        val boxLength = 8 + 4 + 4 + (compatibleBrands.size * 4)
        val buffer = Buffer()
        buffer.writeInt(boxLength)
        buffer.writeUtf8("ftyp")
        buffer.writeUtf8(majorBrand)
        buffer.writeInt(minorVersion)
        for (brand in compatibleBrands) {
            buffer.writeUtf8(brand)
        }
        return buffer.readByteArray()
    }

    private fun createOptions(
        size: CoilSize = CoilSize.ORIGINAL,
        scale: Scale = Scale.FIT,
    ): Options {
        return Options(
            context = ApplicationProvider.getApplicationContext(),
            size = size,
            scale = scale,
        )
    }

    @Test
    fun sniffingIdentifiesAvifWithMajorBrandAvifAndAvis() {
        val avifData = createFtypBox(majorBrand = "avif")
        val avifBuffer = Buffer().write(avifData)
        assertTrue(AvifDecoder.isAvif(avifBuffer))
        assertEquals(avifData.size.toLong(), avifBuffer.size)

        val avisData = createFtypBox(majorBrand = "avis")
        val avisBuffer = Buffer().write(avisData)
        assertTrue(AvifDecoder.isAvif(avisBuffer))
        assertEquals(avisData.size.toLong(), avisBuffer.size)
    }

    @Test
    fun sniffingIdentifiesAvifWithCompatibleBrandAvifOrAvis() {
        val compatAvifData = createFtypBox(
            majorBrand = "mif1",
            minorVersion = 0,
            compatibleBrands = listOf("miaf", "avif"),
        )
        val compatAvifBuffer = Buffer().write(compatAvifData)
        assertTrue(AvifDecoder.isAvif(compatAvifBuffer))
        assertEquals(compatAvifData.size.toLong(), compatAvifBuffer.size)

        val compatAvisData = createFtypBox(
            majorBrand = "mif1",
            minorVersion = 0,
            compatibleBrands = listOf("miaf", "avis"),
        )
        val compatAvisBuffer = Buffer().write(compatAvisData)
        assertTrue(AvifDecoder.isAvif(compatAvisBuffer))
        assertEquals(compatAvisData.size.toLong(), compatAvisBuffer.size)
    }

    @Test
    fun sniffingRejectsNonAvif() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 0x4A, 0x46, 0x49, 0x46, 0)
        assertFalse(AvifDecoder.isAvif(Buffer().write(jpegHeader)))

        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertFalse(AvifDecoder.isAvif(Buffer().write(pngHeader)))

        val heicFtyp = createFtypBox(
            majorBrand = "heic",
            minorVersion = 0,
            compatibleBrands = listOf("mif1", "msf1", "hevc"),
        )
        assertFalse(AvifDecoder.isAvif(Buffer().write(heicFtyp)))

        val generalHeif = createFtypBox(
            majorBrand = "mif1",
            minorVersion = 0,
            compatibleBrands = listOf("miaf", "msf1"),
        )
        assertFalse(AvifDecoder.isAvif(Buffer().write(generalHeif)))

        assertFalse(AvifDecoder.isAvif(Buffer()))
        assertFalse(AvifDecoder.isAvif(Buffer().write(byteArrayOf(0, 0, 0, 8))))
        assertFalse(AvifDecoder.isAvif(Buffer().write(byteArrayOf(0, 0, 0, 12, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))))
    }

    @Test
    fun factoryReturnsNullForNonAvifAndDecoderForAvif() {
        val diagnostics = RecordingDiagnostics()
        val factory = AvifDecoder.Factory(diagnostics, coderProvider = { FakeAvifCoder() })
        val imageLoader = ImageLoader.Builder(ApplicationProvider.getApplicationContext()).build()

        val heicData = createFtypBox(majorBrand = "heic")
        val heicSource = ImageSource(Buffer().write(heicData), FileSystem.SYSTEM)
        val heicResult = SourceFetchResult(heicSource, null, DataSource.DISK)
        assertNull(factory.create(heicResult, createOptions(), imageLoader))

        val avifData = createFtypBox(majorBrand = "avif")
        val avifSource = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val avifResult = SourceFetchResult(avifSource, null, DataSource.DISK)
        val decoder = factory.create(avifResult, createOptions(), imageLoader)
        assertNotNull(decoder)
        assertTrue(decoder is AvifDecoder)
    }

    @Test
    fun decodingEnforcesArgb8888AndForceRgba8888Config() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val rgb565Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.RGB_565)
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(100, 100),
            returnedBitmap = rgb565Bitmap,
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        val result = decoder.decode()
        assertNotNull(result)
        assertEquals(PreferredColorConfig.RGBA_8888, fakeCoder.lastPreferredColorConfig)
        val decodedBitmap = (result.image as coil3.BitmapImage).bitmap
        assertEquals(Bitmap.Config.ARGB_8888, decodedBitmap.config)
        assertFalse(result.isSampled)
    }

    @Test
    fun resourceLimitsMaxCompressedBytesThrowsAvifDecodeException() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val fakeCoder = FakeAvifCoder()

        val oversizedSource = object : Source {
            var bytesLeft = AvifDecoder.MAX_COMPRESSED_AVIF_BYTES + 2048
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (bytesLeft <= 0) return -1L
                val toRead = minOf(byteCount, bytesLeft, 8192L)
                sink.write(ByteArray(toRead.toInt()))
                bytesLeft -= toRead
                return toRead
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() {}
        }

        val imageSource = ImageSource(oversizedSource.buffer(), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = imageSource,
            options = createOptions(),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        try {
            decoder.decode()
            fail("Expected AvifDecodeException due to exceeding MAX_COMPRESSED_AVIF_BYTES")
        } catch (e: AvifDecodeException) {
            assertTrue(e.message?.contains("exceeds limit") == true)
        }

        assertEquals(1, diagnostics.errors.size)
        val log = diagnostics.errors.single().event
        assertTrue(log.startsWith("avif_decode_failed decoder=io.github.awxkee:avif-coder-coil:2.2.1"))
    }

    @Test
    fun resourceLimitsMaxSourcePixelsThrowsAvifDecodeException() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(10001, 10000), // 100,010,000 > 100,000,000
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        try {
            decoder.decode()
            fail("Expected AvifDecodeException due to exceeding MAX_SOURCE_PIXELS")
        } catch (e: AvifDecodeException) {
            assertTrue(e.message?.contains("Source pixels exceed limit") == true)
        }

        assertFalse(fakeCoder.decodeCalled)
        assertEquals(1, diagnostics.errors.size)
        val log = diagnostics.errors.single().event
        assertTrue(log.contains("size=10001x10000"))
    }

    @Test
    fun resourceLimitsMaxOutputPixelsThrowsAvifDecodeException() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(100, 100),
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(
                size = CoilSize(Dimension.Pixels(10001), Dimension.Pixels(10000)),
            ),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        try {
            decoder.decode()
            fail("Expected AvifDecodeException due to exceeding MAX_OUTPUT_PIXELS")
        } catch (e: AvifDecodeException) {
            assertTrue(e.message?.contains("Requested output pixels exceed limit") == true)
        }

        assertFalse(fakeCoder.decodeCalled)
    }

    @Test
    fun corruptedInputThrowsAvifDecodeExceptionWithoutReturningNull() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val fakeCoder = FakeAvifCoder(
            getSizeError = IllegalStateException("Corrupted bit depth table"),
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        try {
            decoder.decode()
            fail("Expected AvifDecodeException on corrupted input")
        } catch (e: AvifDecodeException) {
            assertTrue(e.message?.contains("Failed to get AVIF dimensions") == true)
        }

        assertEquals(1, diagnostics.errors.size)
        val log = diagnostics.errors.single().event
        assertTrue(log.startsWith("avif_decode_failed decoder=io.github.awxkee:avif-coder-coil:2.2.1"))
        assertTrue(log.contains("reason=Corrupted bit depth table"))
    }

    @Test
    fun cancellationPropagatesCancellationExceptionWithoutLoggingError() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val started = CompletableDeferred<Unit>()
        val fakeCoder = object : AvifImageCoder {
            override fun getSize(byteArray: ByteArray): Size? {
                started.complete(Unit)
                while (true) {
                    Thread.sleep(10)
                }
            }

            override fun decode(byteArray: ByteArray, preferredColorConfig: PreferredColorConfig): Bitmap? = null
            override fun decodeSampled(
                byteArray: ByteArray,
                scaledWidth: Int,
                scaledHeight: Int,
                preferredColorConfig: PreferredColorConfig,
                scaleMode: ScaleMode,
            ): Bitmap? = null
        }

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        val job = async {
            decoder.decode()
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(0, diagnostics.errors.size)
    }

    @Test
    fun downsamplingOccursWhenRequestedDimensionsAreSmaller() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val sampledBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(100, 100),
            returnedBitmap = sampledBitmap,
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(
                size = CoilSize(Dimension.Pixels(50), Dimension.Pixels(50)),
                scale = Scale.FIT,
            ),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        val result = decoder.decode()
        assertNotNull(result)
        assertTrue(result.isSampled)
        assertTrue(fakeCoder.decodeSampledCalled)
        assertFalse(fakeCoder.decodeCalled)
        assertEquals(50, fakeCoder.lastTargetWidth)
        assertEquals(50, fakeCoder.lastTargetHeight)
        assertEquals(ScaleMode.FIT, fakeCoder.lastScaleMode)
    }

    @Test
    fun fullSizeDecodeWhenRequestedDimensionsAreLargerOrEqual() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val fullBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(100, 100),
            returnedBitmap = fullBitmap,
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(
                size = CoilSize(Dimension.Pixels(200), Dimension.Pixels(200)),
                scale = Scale.FIT,
            ),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        val result = decoder.decode()
        assertNotNull(result)
        assertFalse(result.isSampled)
        assertTrue(fakeCoder.decodeCalled)
        assertFalse(fakeCoder.decodeSampledCalled)
    }

    @Test
    fun fourKImageDownsampledPreservesAspectRatioWithoutCroppingUnderFillScale() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val sampledBitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(2160, 3840),
            returnedBitmap = sampledBitmap,
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        // Simulated Compose continuous reading: width is screen width (1080), height is placeholder (800), Scale.FILL
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(
                size = CoilSize(Dimension.Pixels(1080), Dimension.Pixels(800)),
                scale = Scale.FILL,
            ),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        val result = decoder.decode()
        assertNotNull(result)
        assertTrue(result.isSampled)
        assertTrue(fakeCoder.decodeSampledCalled)
        assertFalse(fakeCoder.decodeCalled)
        // Must preserve full 9:16 aspect ratio (1080x1920), NEVER crop down to 1080x800
        assertEquals(1080, fakeCoder.lastTargetWidth)
        assertEquals(1920, fakeCoder.lastTargetHeight)
        assertEquals(ScaleMode.FIT, fakeCoder.lastScaleMode)
    }

    @Test
    fun fourKImageDownsampledPreservesAspectRatioUnderFitScale() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val sampledBitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(2160, 3840),
            returnedBitmap = sampledBitmap,
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        // Simulated Compose FitViewport: 1080x2400 screen
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(
                size = CoilSize(Dimension.Pixels(1080), Dimension.Pixels(2400)),
                scale = Scale.FIT,
            ),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        val result = decoder.decode()
        assertNotNull(result)
        assertTrue(result.isSampled)
        assertTrue(fakeCoder.decodeSampledCalled)
        assertFalse(fakeCoder.decodeCalled)
        assertEquals(1080, fakeCoder.lastTargetWidth)
        assertEquals(1920, fakeCoder.lastTargetHeight)
        assertEquals(ScaleMode.FIT, fakeCoder.lastScaleMode)
    }

    @Test
    fun fourKImageWithSingleConstrainedDimensionPreservesAspectRatio() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val sampledBitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val fakeCoder = FakeAvifCoder(
            reportedSize = Size(2160, 3840),
            returnedBitmap = sampledBitmap,
        )

        val avifData = createFtypBox(majorBrand = "avif")
        val source = ImageSource(Buffer().write(avifData), FileSystem.SYSTEM)
        val decoder = AvifDecoder(
            source = source,
            options = createOptions(
                size = CoilSize(Dimension.Pixels(1080), Dimension.Undefined),
                scale = Scale.FIT,
            ),
            diagnostics = diagnostics,
            coder = fakeCoder,
        )

        val result = decoder.decode()
        assertNotNull(result)
        assertTrue(result.isSampled)
        assertTrue(fakeCoder.decodeSampledCalled)
        assertFalse(fakeCoder.decodeCalled)
        assertEquals(1080, fakeCoder.lastTargetWidth)
        assertEquals(1920, fakeCoder.lastTargetHeight)
        assertEquals(ScaleMode.FIT, fakeCoder.lastScaleMode)
    }
}
