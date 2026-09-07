package org.mubox.reader.infrastructure.image

import android.graphics.Bitmap
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size as CoilSize
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig
import com.radzivon.bartoshyk.avif.coder.ScaleMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import org.mubox.reader.core.diagnostics.DiagnosticCategory
import org.mubox.reader.core.diagnostics.Diagnostics
import kotlin.math.roundToInt

class AvifDecodeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface AvifImageCoder {
    fun getSize(byteArray: ByteArray): Size?
    fun decode(byteArray: ByteArray, preferredColorConfig: PreferredColorConfig): Bitmap?
    fun decodeSampled(
        byteArray: ByteArray,
        scaledWidth: Int,
        scaledHeight: Int,
        preferredColorConfig: PreferredColorConfig,
        scaleMode: ScaleMode,
    ): Bitmap?
}

class DefaultAvifImageCoder(
    private val coder: HeifCoder = HeifCoder(),
) : AvifImageCoder {
    override fun getSize(byteArray: ByteArray): Size? = coder.getSize(byteArray)

    override fun decode(byteArray: ByteArray, preferredColorConfig: PreferredColorConfig): Bitmap? =
        coder.decode(byteArray, preferredColorConfig)

    override fun decodeSampled(
        byteArray: ByteArray,
        scaledWidth: Int,
        scaledHeight: Int,
        preferredColorConfig: PreferredColorConfig,
        scaleMode: ScaleMode,
    ): Bitmap? = coder.decodeSampled(byteArray, scaledWidth, scaledHeight, preferredColorConfig, scaleMode)
}

class AvifDecoder internal constructor(
    private val source: ImageSource,
    private val options: Options,
    private val diagnostics: Diagnostics,
    private val coder: AvifImageCoder,
) : Decoder {

    constructor(
        source: ImageSource,
        options: Options,
        diagnostics: Diagnostics,
    ) : this(source, options, diagnostics, DefaultAvifImageCoder())

    override suspend fun decode(): DecodeResult = decodeSemaphore.withPermit {
        withContext(Dispatchers.IO) {
            runInterruptible {
                var w = (options.size.width as? Dimension.Pixels)?.px ?: 0
                var h = (options.size.height as? Dimension.Pixels)?.px ?: 0
                try {
                    val bytes = readBoundedBytes(source.source(), MAX_COMPRESSED_AVIF_BYTES)

                    val size = try {
                        coder.getSize(bytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Throwable) {
                        throw AvifDecodeException("Failed to get AVIF dimensions: ${e.message}", e)
                    }

                    if (size == null || size.width <= 0 || size.height <= 0) {
                        throw AvifDecodeException("Invalid AVIF dimensions: $size")
                    }

                    w = size.width
                    h = size.height

                    val sourcePixels = w.toLong() * h.toLong()
                    if (sourcePixels > MAX_SOURCE_PIXELS) {
                        throw AvifDecodeException(
                            "Source pixels exceed limit: $sourcePixels > $MAX_SOURCE_PIXELS (${w}x${h})",
                        )
                    }

                    val dstWidth = (options.size.width as? Dimension.Pixels)?.px ?: 0
                    val dstHeight = (options.size.height as? Dimension.Pixels)?.px ?: 0
                    if (dstWidth > 0 && dstHeight > 0) {
                        val requestedPixels = dstWidth.toLong() * dstHeight.toLong()
                        if (requestedPixels > MAX_OUTPUT_PIXELS) {
                            throw AvifDecodeException(
                                "Requested output pixels exceed limit: $requestedPixels > $MAX_OUTPUT_PIXELS (${dstWidth}x${dstHeight})",
                            )
                        }
                    }

                    val (decodedBitmap, isSampled) = decodeBitmap(bytes, w, h, dstWidth, dstHeight)

                    val outputPixels = decodedBitmap.width.toLong() * decodedBitmap.height.toLong()
                    if (outputPixels > MAX_OUTPUT_PIXELS) {
                        decodedBitmap.recycle()
                        throw AvifDecodeException(
                            "Decoded output pixels exceed limit: $outputPixels > $MAX_OUTPUT_PIXELS (${decodedBitmap.width}x${decodedBitmap.height})",
                        )
                    }

                    val finalBitmap = if (decodedBitmap.config != Bitmap.Config.ARGB_8888) {
                        val converted = decodedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw AvifDecodeException(
                                "Failed to convert bitmap config from ${decodedBitmap.config} to ARGB_8888",
                            )
                        decodedBitmap.recycle()
                        converted
                    } else {
                        decodedBitmap
                    }

                    DecodeResult(
                        image = finalBitmap.asImage(),
                        isSampled = isSampled,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (error: Throwable) {
                    val decodeException = if (error is AvifDecodeException) {
                        error
                    } else {
                        AvifDecodeException("AVIF decoding failed: ${error.message}", error)
                    }
                    val cause = decodeException.cause ?: decodeException
                    diagnostics.error(
                        DiagnosticCategory.APPLICATION,
                        "avif_decode_failed decoder=io.github.awxkee:avif-coder-coil:2.2.1 size=${w}x${h} reason=${cause?.message}",
                        decodeException,
                    )
                    throw decodeException
                }
            }
        }
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): Pair<Bitmap, Boolean> {
        val hasExplicitDimensions = dstWidth > 0 || dstHeight > 0
        if (options.size == CoilSize.ORIGINAL || !hasExplicitDimensions) {
            val bitmap = coder.decode(bytes, PreferredColorConfig.RGBA_8888)
                ?: throw AvifDecodeException("Decoder returned null bitmap for full-size decode")
            return bitmap to false
        }

        val multiplier = when {
            dstWidth > 0 && dstHeight > 0 -> {
                val widthPercent = dstWidth.toDouble() / srcWidth
                val heightPercent = dstHeight.toDouble() / srcHeight
                when (options.scale) {
                    Scale.FILL -> maxOf(widthPercent, heightPercent)
                    Scale.FIT -> minOf(widthPercent, heightPercent)
                }
            }
            dstWidth > 0 -> dstWidth.toDouble() / srcWidth
            dstHeight > 0 -> dstHeight.toDouble() / srcHeight
            else -> 1.0
        }

        if (multiplier >= 1.0) {
            val bitmap = coder.decode(bytes, PreferredColorConfig.RGBA_8888)
                ?: throw AvifDecodeException("Decoder returned null bitmap for full-size decode")
            return bitmap to false
        }

        val targetW = (srcWidth * multiplier).roundToInt().coerceAtLeast(1)
        val targetH = (srcHeight * multiplier).roundToInt().coerceAtLeast(1)

        val targetPixels = targetW.toLong() * targetH.toLong()
        if (targetPixels > MAX_OUTPUT_PIXELS) {
            throw AvifDecodeException(
                "Calculated target pixels exceed limit: $targetPixels > $MAX_OUTPUT_PIXELS (${targetW}x${targetH})",
            )
        }

        val bitmap = coder.decodeSampled(bytes, targetW, targetH, PreferredColorConfig.RGBA_8888, ScaleMode.FIT)
            ?: throw AvifDecodeException("Decoder returned null bitmap for sampled decode (${targetW}x${targetH})")

        return bitmap to true
    }

    class Factory(
        private val diagnostics: Diagnostics,
        private val coderProvider: () -> AvifImageCoder = { DefaultAvifImageCoder() },
    ) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isAvif(result.source.source())) {
                return null
            }
            return AvifDecoder(
                source = result.source,
                options = options,
                diagnostics = diagnostics,
                coder = coderProvider(),
            )
        }
    }

    companion object {
        const val MAX_COMPRESSED_AVIF_BYTES = 64L * 1024 * 1024

        // ~33MP keeps one decoded RGBA_8888 frame near ~130MB of bitmap memory;
        // beyond that a decode is more likely to OOM mid-way than to succeed.
        const val MAX_SOURCE_PIXELS = 33_000_000L
        const val MAX_OUTPUT_PIXELS = 33_000_000L
        const val MAX_CONCURRENT_AVIF_DECODES = 4

        private val decodeSemaphore = Semaphore(MAX_CONCURRENT_AVIF_DECODES)

        private val FTYP = "ftyp".encodeUtf8()
        private val AVIF = "avif".encodeUtf8()
        private val AVIS = "avis".encodeUtf8()

        fun isAvif(source: BufferedSource): Boolean {
            val peek = source.peek()
            if (!peek.request(12)) return false
            val boxLength = peek.readInt().toLong() and 0xFFFF_FFFFL
            val ftyp = peek.readByteString(4)
            if (ftyp != FTYP) return false
            val majorBrand = peek.readByteString(4)
            if (majorBrand == AVIF || majorBrand == AVIS) return true

            val limit = minOf(boxLength, 4096L)
            if (limit < 16L) return false

            if (!peek.request(4)) return false
            peek.skip(4)

            var currentOffset = 16L
            while (currentOffset + 4 <= limit && peek.request(4)) {
                val brand = peek.readByteString(4)
                if (brand == AVIF || brand == AVIS) return true
                currentOffset += 4
            }
            return false
        }

        private fun readBoundedBytes(source: BufferedSource, maxBytes: Long): ByteArray {
            if (source.buffer.size > maxBytes) {
                throw AvifDecodeException("Compressed AVIF size exceeds limit: ${source.buffer.size} > $maxBytes bytes")
            }
            val buffer = Buffer()
            var totalRead = 0L
            val chunkSize = 8192L
            while (true) {
                val read = source.read(buffer, chunkSize)
                if (read == -1L) break
                totalRead += read
                if (totalRead > maxBytes) {
                    throw AvifDecodeException("Compressed AVIF size exceeds limit: $totalRead > $maxBytes bytes")
                }
            }
            if (totalRead == 0L) {
                throw AvifDecodeException("AVIF source is empty")
            }
            return buffer.readByteArray()
        }
    }
}
