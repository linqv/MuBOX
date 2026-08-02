package org.mubox.reader.feature.reader.mupdf

import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

class ParcelFileDescriptorSeekableInputStream(
    descriptor: ParcelFileDescriptor,
) : SeekableInputStream, Closeable {
    private val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    private val channel = input.channel

    override fun read(buffer: ByteArray): Int {
        return channel.read(ByteBuffer.wrap(buffer))
    }

    override fun seek(offset: Long, whence: Int): Long {
        val target = when (whence) {
            SeekableStream.SEEK_SET -> offset
            SeekableStream.SEEK_CUR -> channel.position() + offset
            SeekableStream.SEEK_END -> channel.size() + offset
            else -> throw IOException("unsupported seek mode: $whence")
        }.coerceAtLeast(0L)
        channel.position(target)
        return target
    }

    override fun position(): Long = channel.position()

    override fun close() {
        input.close()
    }

    companion object {
        val SEEK_SET: Int = SeekableStream.SEEK_SET
        val SEEK_CUR: Int = SeekableStream.SEEK_CUR
        val SEEK_END: Int = SeekableStream.SEEK_END
    }
}
