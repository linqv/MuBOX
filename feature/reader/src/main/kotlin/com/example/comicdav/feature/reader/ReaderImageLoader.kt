package com.example.comicdav.feature.reader

import android.content.Context
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import java.util.Locale

fun installReaderImageLoader(context: Context) {
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(PlatformReaderImageDecoder.Factory())
                }
            }
            .build()
    }
}

@RequiresApi(Build.VERSION_CODES.P)
internal class PlatformReaderImageDecoder(
    private val source: ImageDecoder.Source,
    private val closeable: AutoCloseable,
    private val decodeAsBitmap: Boolean,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val image = try {
            if (decodeAsBitmap) {
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                bitmap.asImage(false)
            } else {
                ImageDecoder.decodeDrawable(source).asImage()
            }
        } finally {
            closeable.close()
        }
        return DecodeResult(image, false)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val header = result.headerBytes()
            if (!isPlatformReaderImage(result.mimeType, header)) return null
            val file = result.source.fileOrNull()?.toFile() ?: return null
            val source = ImageDecoder.createSource(file)
            return PlatformReaderImageDecoder(
                source = source,
                closeable = result.source,
                decodeAsBitmap = isAvifReaderImage(result.mimeType, header),
            )
        }
    }
}

internal fun isPlatformReaderImage(mimeType: String?, header: ByteArray): Boolean {
    val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
    if (normalizedMimeType == "image/gif" || normalizedMimeType == "image/webp") {
        return true
    }
    return isAvifReaderImage(normalizedMimeType, header) || header.isGifHeader() || header.isWebpHeader()
}

internal fun isPlatformAnimatedReaderImage(mimeType: String?, header: ByteArray): Boolean {
    val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
    if (normalizedMimeType == "image/gif" || normalizedMimeType == "image/webp") {
        return true
    }
    return header.isGifHeader() || header.isWebpHeader()
}

internal fun isAvifReaderImage(mimeType: String?, header: ByteArray): Boolean {
    val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
    return normalizedMimeType == "image/avif" || header.isAvifHeader()
}

private fun SourceFetchResult.headerBytes(maxBytes: Long = 32L): ByteArray {
    val peek = runCatching { source.source().peek() }.getOrNull() ?: return ByteArray(0)
    val buffer = Buffer()
    while (buffer.size < maxBytes) {
        val read = runCatching { peek.read(buffer, maxBytes - buffer.size) }.getOrDefault(-1L)
        if (read <= 0L) break
    }
    return buffer.readByteArray()
}

private fun ByteArray.isGifHeader(): Boolean =
    size >= 6 &&
        this[0] == 'G'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == '8'.code.toByte() &&
        (this[4] == '7'.code.toByte() || this[4] == '9'.code.toByte()) &&
        this[5] == 'a'.code.toByte()

private fun ByteArray.isWebpHeader(): Boolean =
    size >= 12 &&
        this[0] == 'R'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() &&
        this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() &&
        this[11] == 'P'.code.toByte()

private fun ByteArray.isAvifHeader(): Boolean {
    if (size < 12) return false
    if (
        this[4] != 'f'.code.toByte() ||
        this[5] != 't'.code.toByte() ||
        this[6] != 'y'.code.toByte() ||
        this[7] != 'p'.code.toByte()
    ) {
        return false
    }
    var offset = 8
    while (offset + 4 <= size) {
        if (hasAvifBrand(offset)) return true
        offset += 4
    }
    return false
}

private fun ByteArray.hasAvifBrand(offset: Int): Boolean =
    size >= offset + 4 &&
        this[offset] == 'a'.code.toByte() &&
        this[offset + 1] == 'v'.code.toByte() &&
        this[offset + 2] == 'i'.code.toByte() &&
        (this[offset + 3] == 'f'.code.toByte() || this[offset + 3] == 's'.code.toByte())
