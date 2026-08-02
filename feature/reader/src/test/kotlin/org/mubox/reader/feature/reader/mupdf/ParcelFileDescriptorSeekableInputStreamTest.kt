package org.mubox.reader.feature.reader.mupdf

import android.os.ParcelFileDescriptor
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParcelFileDescriptorSeekableInputStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readsFromCurrentPosition() {
        val file = temp.newFile("doc.bin").apply {
            writeBytes(byteArrayOf(10, 11, 12, 13, 14))
        }
        val stream = openStream(file)
        val buffer = ByteArray(3)

        val count = stream.read(buffer)

        assertEquals(3, count)
        assertArrayEquals(byteArrayOf(10, 11, 12), buffer)
        assertEquals(3L, stream.position())
        stream.close()
    }

    @Test
    fun seekSupportsSetCurrentAndEnd() {
        val file = temp.newFile("doc.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        }
        val stream = openStream(file)
        val buffer = ByteArray(2)

        assertEquals(2L, stream.seek(2, ParcelFileDescriptorSeekableInputStream.SEEK_SET))
        assertEquals(4L, stream.seek(2, ParcelFileDescriptorSeekableInputStream.SEEK_CUR))
        assertEquals(5L, stream.seek(-1, ParcelFileDescriptorSeekableInputStream.SEEK_END))
        assertEquals(1, stream.read(buffer))

        assertArrayEquals(byteArrayOf(6, 0), buffer)
        stream.close()
    }

    @Test
    fun closeClosesDescriptor() {
        val file = temp.newFile("doc.bin").apply {
            writeBytes(byteArrayOf(1))
        }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val stream = ParcelFileDescriptorSeekableInputStream(descriptor)

        stream.close()

        val error = runCatching {
            stream.read(ByteArray(1))
        }.exceptionOrNull()

        assertNotNull(error)
    }

    private fun openStream(file: File): ParcelFileDescriptorSeekableInputStream {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return ParcelFileDescriptorSeekableInputStream(descriptor)
    }
}
