# MuPDF Local Document Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add local direct reading for PDF, EPUB, MOBI, and AZW3 through MuPDF while preserving the existing ComicDav reader pipeline.

**Architecture:** Local opens are dispatched by extension: existing image archives keep using the Rust `comic-core` FD path, while PDF/EPUB/MOBI/AZW3 use a MuPDF-backed `ComicReaderSession`. MuPDF rendering is isolated behind adapter interfaces so unit tests use fakes and the official AAR can be upgraded without touching reader flow code.

**Tech Stack:** Android Kotlin, Compose reader, Robolectric unit tests, MuPDF `fitz-1.27.1.aar`, existing Rust `comic-core` unchanged.

---

## File Structure

- `app/libs/fitz-1.27.1.aar`
  - Official MuPDF Android AAR supplied at `/tmp/mupdf-plan/fitz-1.27.1.aar`.
- `app/build.gradle.kts`
  - Adds a local file dependency on `libs/fitz-1.27.1.aar`.
- `app/src/main/java/com/example/comicdav/data/LocalComicFormat.kt`
  - Keeps `LocalArchiveFormat`; adds `LocalDocumentFormat`; updates filename support/title helpers.
- `app/src/main/java/com/example/comicdav/feature/reader/LocalComicOpener.kt`
  - Dispatches local SAF opens to archive or MuPDF document sessions.
- `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt`
  - Implements `ComicReaderSession` for rendered MuPDF pages.
- `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt`
  - Defines testable MuPDF document/page abstractions.
- `app/src/main/java/com/example/comicdav/feature/reader/mupdf/RealMuPdfDocumentAdapter.kt`
  - Opens real MuPDF documents from SAF file descriptors and renders pages.
- `app/src/main/java/com/example/comicdav/feature/reader/mupdf/ParcelFileDescriptorSeekableInputStream.kt`
  - Adapts `ParcelFileDescriptor` to MuPDF `SeekableInputStream`.
- `app/src/test/java/com/example/comicdav/feature/reader/LocalComicFormatTest.kt`
  - Adds document extension coverage.
- `app/src/test/java/com/example/comicdav/feature/reader/LocalComicOpenerTest.kt`
  - Adds archive-vs-document dispatch coverage.
- `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt`
  - Tests session page count, cache hit, render, close, and error mapping with fakes.
- `app/src/test/java/com/example/comicdav/feature/reader/mupdf/ParcelFileDescriptorSeekableInputStreamTest.kt`
  - Tests read/seek behavior without loading MuPDF native code.

## Task 1: Add MuPDF AAR Dependency

**Files:**
- Create: `app/libs/fitz-1.27.1.aar`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Copy the official AAR into the app module**

Run:

```bash
mkdir -p app/libs
cp /tmp/mupdf-plan/fitz-1.27.1.aar app/libs/fitz-1.27.1.aar
```

Expected: `app/libs/fitz-1.27.1.aar` exists and is about 20 MiB.

- [ ] **Step 2: Add the local AAR dependency**

Modify `app/build.gradle.kts` inside `dependencies { ... }`:

```kotlin
    implementation(files("libs/fitz-1.27.1.aar"))
```

Place it near the other `implementation(...)` dependencies.

- [ ] **Step 3: Verify Gradle sees the dependency**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

Expected: output includes `fitz-1.27.1.aar`.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/libs/fitz-1.27.1.aar
git commit -m "build: add mupdf android dependency"
```

## Task 2: Extend Local Format Detection

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/feature/reader/LocalComicFormatTest.kt`
- Modify: `app/src/main/java/com/example/comicdav/data/LocalComicFormat.kt`

- [ ] **Step 1: Write failing tests for MuPDF document extensions**

Replace the existing test class contents with this shape, preserving the package/imports:

```kotlin
package com.example.comicdav.feature.reader

import com.example.comicdav.data.LocalArchiveFormat
import com.example.comicdav.data.LocalDocumentFormat
import com.example.comicdav.data.isSupportedLocalComicFileName
import com.example.comicdav.data.localArchiveFormatForFileName
import com.example.comicdav.data.localComicTitleFromFileName
import com.example.comicdav.data.localDocumentFormatForFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalComicFormatTest {
    @Test
    fun supportedLocalComicNamesIncludeArchivesAndMuPdfDocumentsButNotRar() {
        assertTrue(isSupportedLocalComicFileName("book.cbz"))
        assertTrue(isSupportedLocalComicFileName("book.zip"))
        assertTrue(isSupportedLocalComicFileName("book.cb7"))
        assertTrue(isSupportedLocalComicFileName("book.7z"))
        assertTrue(isSupportedLocalComicFileName("book.cbt"))
        assertTrue(isSupportedLocalComicFileName("book.tar"))
        assertTrue(isSupportedLocalComicFileName("book.pdf"))
        assertTrue(isSupportedLocalComicFileName("book.epub"))
        assertTrue(isSupportedLocalComicFileName("book.mobi"))
        assertTrue(isSupportedLocalComicFileName("book.azw3"))
        assertFalse(isSupportedLocalComicFileName("book.cbr"))
        assertFalse(isSupportedLocalComicFileName("book.rar"))
        assertFalse(isSupportedLocalComicFileName("notes.txt"))
    }

    @Test
    fun localArchiveFormatIsDerivedFromSupportedArchiveFileName() {
        assertEquals(LocalArchiveFormat.Zip, localArchiveFormatForFileName("book.cbz"))
        assertEquals(LocalArchiveFormat.Zip, localArchiveFormatForFileName("book.zip"))
        assertEquals(LocalArchiveFormat.SevenZ, localArchiveFormatForFileName("book.cb7"))
        assertEquals(LocalArchiveFormat.SevenZ, localArchiveFormatForFileName("book.7z"))
        assertEquals(LocalArchiveFormat.Tar, localArchiveFormatForFileName("book.cbt"))
        assertEquals(LocalArchiveFormat.Tar, localArchiveFormatForFileName("book.tar"))
        assertEquals(null, localArchiveFormatForFileName("book.pdf"))
    }

    @Test
    fun localDocumentFormatIsDerivedFromSupportedDocumentFileName() {
        assertEquals(LocalDocumentFormat.Pdf, localDocumentFormatForFileName("book.pdf"))
        assertEquals(LocalDocumentFormat.Epub, localDocumentFormatForFileName("book.epub"))
        assertEquals(LocalDocumentFormat.Mobi, localDocumentFormatForFileName("book.mobi"))
        assertEquals(LocalDocumentFormat.Azw3, localDocumentFormatForFileName("book.azw3"))
        assertEquals(null, localDocumentFormatForFileName("book.cbz"))
        assertEquals(null, localDocumentFormatForFileName("book.rar"))
    }

    @Test
    fun localComicTitleStripsSupportedArchiveAndDocumentSuffixes() {
        assertEquals("book", localComicTitleFromFileName("book.cbz"))
        assertEquals("book", localComicTitleFromFileName("book.zip"))
        assertEquals("book", localComicTitleFromFileName("book.cb7"))
        assertEquals("book", localComicTitleFromFileName("book.7z"))
        assertEquals("book", localComicTitleFromFileName("book.cbt"))
        assertEquals("book", localComicTitleFromFileName("book.tar"))
        assertEquals("book", localComicTitleFromFileName("book.pdf"))
        assertEquals("book", localComicTitleFromFileName("book.epub"))
        assertEquals("book", localComicTitleFromFileName("book.mobi"))
        assertEquals("book", localComicTitleFromFileName("book.azw3"))
        assertEquals("book.rar", localComicTitleFromFileName("book.rar"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.LocalComicFormatTest
```

Expected: FAIL because `LocalDocumentFormat` and `localDocumentFormatForFileName` do not exist.

- [ ] **Step 3: Implement document format detection**

Update `app/src/main/java/com/example/comicdav/data/LocalComicFormat.kt` to:

```kotlin
package com.example.comicdav.data

import java.util.Locale

enum class LocalArchiveFormat(
    val nativeName: String,
    private val suffixes: Set<String>,
) {
    Zip("zip", setOf("cbz", "zip")),
    SevenZ("7z", setOf("cb7", "7z")),
    Tar("tar", setOf("cbt", "tar"));

    fun matchesExtension(extension: String): Boolean = extension in suffixes
}

enum class LocalDocumentFormat(
    val displayName: String,
    private val suffixes: Set<String>,
) {
    Pdf("PDF", setOf("pdf")),
    Epub("EPUB", setOf("epub")),
    Mobi("MOBI", setOf("mobi")),
    Azw3("AZW3", setOf("azw3"));

    fun matchesExtension(extension: String): Boolean = extension in suffixes
}

fun isSupportedLocalComicFileName(fileName: String): Boolean =
    localArchiveFormatForFileName(fileName) != null || localDocumentFormatForFileName(fileName) != null

fun localArchiveFormatForFileName(fileName: String): LocalArchiveFormat? {
    val extension = localFileExtension(fileName)
    if (extension.isBlank()) return null
    return LocalArchiveFormat.values().firstOrNull { it.matchesExtension(extension) }
}

fun localDocumentFormatForFileName(fileName: String): LocalDocumentFormat? {
    val extension = localFileExtension(fileName)
    if (extension.isBlank()) return null
    return LocalDocumentFormat.values().firstOrNull { it.matchesExtension(extension) }
}

fun localComicTitleFromFileName(fileName: String): String {
    val extension = localFileExtension(fileName)
    if (extension.isBlank()) return fileName
    val isSupported = LocalArchiveFormat.values().any { it.matchesExtension(extension) } ||
        LocalDocumentFormat.values().any { it.matchesExtension(extension) }
    return if (isSupported) fileName.dropLast(extension.length + 1) else fileName
}

private fun localFileExtension(fileName: String): String =
    fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.LocalComicFormatTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/data/LocalComicFormat.kt app/src/test/java/com/example/comicdav/feature/reader/LocalComicFormatTest.kt
git commit -m "feat: detect local mupdf document formats"
```

## Task 3: Add MuPDF Session Abstractions

**Files:**
- Create: `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfDocumentAdapter.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSession.kt`

- [ ] **Step 1: Write failing tests for the session seam**

Create `MuPdfReaderSessionTest.kt`:

```kotlin
package com.example.comicdav.feature.reader.mupdf

import com.example.comicdav.data.LocalDocumentFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MuPdfReaderSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun pageCountComesFromDocument() {
        val document = FakeMuPdfDocument(pageCount = 3)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        assertEquals(3, session.pageCount)
    }

    @Test
    fun loadPageToFileSkipsRenderingWhenOutputAlreadyExists() {
        val output = temp.newFile("page.png").apply {
            writeText("cached")
        }
        val document = FakeMuPdfDocument(pageCount = 2)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        val result = session.loadPageToFile(1, output)

        assertEquals(output, result)
        assertEquals(emptyList<Int>(), document.renderedPages)
        assertEquals("cached", output.readText())
    }

    @Test
    fun loadPageToFileRendersRequestedPage() {
        val output = File(temp.root, "page-2.png")
        val document = FakeMuPdfDocument(pageCount = 4)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Epub)

        val result = session.loadPageToFile(2, output)

        assertEquals(output, result)
        assertEquals(listOf(2), document.renderedPages)
        assertEquals("rendered-2", output.readText())
    }

    @Test
    fun loadPageToFileRejectsOutOfRangePage() {
        val output = File(temp.root, "page-9.png")
        val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 2), LocalDocumentFormat.Mobi)

        val error = runCatching {
            session.loadPageToFile(9, output)
        }.exceptionOrNull()

        assertEquals("页面渲染失败", error?.message)
        assertFalse(output.exists())
    }

    @Test
    fun closeClosesDocumentOnce() {
        val document = FakeMuPdfDocument(pageCount = 1)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Azw3)

        session.close()
        session.close()

        assertTrue(document.closed)
        assertEquals(1, document.closeCount)
    }

    private class FakeMuPdfDocument(
        override val pageCount: Int,
    ) : MuPdfDocumentHandle {
        val renderedPages = mutableListOf<Int>()
        var closed = false
        var closeCount = 0

        override fun renderPageToPng(pageIndex: Int, outputFile: File, maxPixels: Int) {
            if (pageIndex !in 0 until pageCount) error("bad page")
            renderedPages += pageIndex
            outputFile.writeText("rendered-$pageIndex")
        }

        override fun close() {
            closeCount++
            closed = true
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest
```

Expected: FAIL because `MuPdfReaderSession` and `MuPdfDocumentHandle` do not exist.

- [ ] **Step 3: Create MuPDF document interfaces**

Create `MuPdfDocumentAdapter.kt`:

```kotlin
package com.example.comicdav.feature.reader.mupdf

import android.os.ParcelFileDescriptor
import com.example.comicdav.data.LocalDocumentFormat
import java.io.Closeable
import java.io.File

interface MuPdfDocumentAdapter {
    fun open(
        descriptor: ParcelFileDescriptor,
        fileName: String,
        format: LocalDocumentFormat,
    ): MuPdfDocumentHandle
}

interface MuPdfDocumentHandle : Closeable {
    val pageCount: Int

    fun renderPageToPng(
        pageIndex: Int,
        outputFile: File,
        maxPixels: Int = DEFAULT_MUPDF_RENDER_MAX_PIXELS,
    )
}

const val DEFAULT_MUPDF_RENDER_MAX_PIXELS: Int = 4_000_000
```

- [ ] **Step 4: Create the MuPDF reader session**

Create `MuPdfReaderSession.kt`:

```kotlin
package com.example.comicdav.feature.reader.mupdf

import com.example.comicdav.data.LocalDocumentFormat
import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File

class MuPdfReaderSession(
    private val document: MuPdfDocumentHandle,
    private val format: LocalDocumentFormat,
    private val maxPixels: Int = DEFAULT_MUPDF_RENDER_MAX_PIXELS,
) : ComicReaderSession {
    override val pageCount: Int = document.pageCount

    private var isClosed = false

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        if (outputFile.isFile && outputFile.length() > 0L) {
            return outputFile
        }
        if (pageIndex !in 0 until pageCount) {
            throw IllegalStateException("页面渲染失败")
        }
        runCatching {
            outputFile.parentFile?.mkdirs()
            document.renderPageToPng(pageIndex, outputFile, maxPixels)
        }.getOrElse {
            outputFile.delete()
            throw IllegalStateException("页面渲染失败", it)
        }
        if (!outputFile.isFile || outputFile.length() == 0L) {
            outputFile.delete()
            throw IllegalStateException("页面渲染失败")
        }
        return outputFile
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        document.close()
    }

    override fun diagnostics(): String =
        "reader=mupdf;format=${format.displayName};pageCount=$pageCount"
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/mupdf app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfReaderSessionTest.kt
git commit -m "feat: add mupdf reader session seam"
```

## Task 4: Implement Seekable SAF Stream

**Files:**
- Create: `app/src/test/java/com/example/comicdav/feature/reader/mupdf/ParcelFileDescriptorSeekableInputStreamTest.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/ParcelFileDescriptorSeekableInputStream.kt`

- [ ] **Step 1: Write failing tests for read and seek**

Create `ParcelFileDescriptorSeekableInputStreamTest.kt`:

```kotlin
package com.example.comicdav.feature.reader.mupdf

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
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.ParcelFileDescriptorSeekableInputStreamTest
```

Expected: FAIL because `ParcelFileDescriptorSeekableInputStream` does not exist.

- [ ] **Step 3: Implement seekable stream**

Create `ParcelFileDescriptorSeekableInputStream.kt`:

```kotlin
package com.example.comicdav.feature.reader.mupdf

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
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.ParcelFileDescriptorSeekableInputStreamTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/mupdf/ParcelFileDescriptorSeekableInputStream.kt app/src/test/java/com/example/comicdav/feature/reader/mupdf/ParcelFileDescriptorSeekableInputStreamTest.kt
git commit -m "feat: adapt local descriptors for mupdf"
```

## Task 5: Implement Real MuPDF Adapter

**Files:**
- Create: `app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfRenderScaleTest.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/reader/mupdf/RealMuPdfDocumentAdapter.kt`

- [ ] **Step 1: Write failing tests for render scaling**

Create `MuPdfRenderScaleTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfRenderScaleTest
```

Expected: FAIL because `mupdfRenderScale` does not exist.

- [ ] **Step 3: Implement real adapter and scale helper**

Create `RealMuPdfDocumentAdapter.kt`:

```kotlin
package com.example.comicdav.feature.reader.mupdf

import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.example.comicdav.data.LocalDocumentFormat
import java.io.File
import kotlin.math.sqrt

class RealMuPdfDocumentAdapter : MuPdfDocumentAdapter {
    override fun open(
        descriptor: ParcelFileDescriptor,
        fileName: String,
        format: LocalDocumentFormat,
    ): MuPdfDocumentHandle {
        val stream = ParcelFileDescriptorSeekableInputStream(descriptor)
        return try {
            val document = Document.openDocument(stream, fileName)
            if (document.needsPassword()) {
                document.destroy()
                stream.close()
                throw IllegalStateException("暂不支持加密或需要密码的文件")
            }
            if (document.isReflowable) {
                document.layout(DEFAULT_MUPDF_REFLOW_WIDTH, DEFAULT_MUPDF_REFLOW_HEIGHT, DEFAULT_MUPDF_REFLOW_EM)
            }
            val pageCount = document.countPages()
            if (pageCount <= 0) {
                document.destroy()
                stream.close()
                throw IllegalStateException("这个文件没有可读取的页面")
            }
            RealMuPdfDocumentHandle(
                document = document,
                stream = stream,
                format = format,
                pageCount = pageCount,
            )
        } catch (error: Throwable) {
            runCatching { stream.close() }
            throw mapMuPdfOpenError(format, error)
        }
    }
}

class RealMuPdfDocumentHandle(
    private val document: Document,
    private val stream: ParcelFileDescriptorSeekableInputStream,
    private val format: LocalDocumentFormat,
    override val pageCount: Int,
) : MuPdfDocumentHandle {
    private var isClosed = false

    override fun renderPageToPng(pageIndex: Int, outputFile: File, maxPixels: Int) {
        val page = document.loadPage(pageIndex)
        try {
            val bounds = page.bounds
            val width = bounds.x1 - bounds.x0
            val height = bounds.y1 - bounds.y0
            val scale = mupdfRenderScale(width, height, maxPixels)
            val pixmap = page.toPixmap(Matrix.Scale(scale), ColorSpace.DeviceRGB, false)
            try {
                outputFile.parentFile?.mkdirs()
                pixmap.saveAsPNG(outputFile.absolutePath)
            } finally {
                pixmap.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { document.destroy() }
        runCatching { stream.close() }
    }
}

fun mupdfRenderScale(width: Float, height: Float, maxPixels: Int): Float {
    if (width <= 0f || height <= 0f || maxPixels <= 0) return 1f
    val pixels = width * height
    if (pixels <= maxPixels.toFloat()) return 1f
    return sqrt(maxPixels.toFloat() / pixels)
}

private const val DEFAULT_MUPDF_REFLOW_WIDTH = 1080f
private const val DEFAULT_MUPDF_REFLOW_HEIGHT = 1920f
private const val DEFAULT_MUPDF_REFLOW_EM = 12f

fun mapMuPdfOpenError(format: LocalDocumentFormat, error: Throwable): Throwable {
    val message = error.message.orEmpty()
    if (message == "这个文件没有可读取的页面" || message == "暂不支持加密或需要密码的文件") {
        return error
    }
    if (message.contains("drm", ignoreCase = true)) {
        return IllegalStateException("暂不支持受 DRM 保护的文件", error)
    }
    if (message.contains("password", ignoreCase = true) || message.contains("encrypted", ignoreCase = true)) {
        return IllegalStateException("暂不支持加密或需要密码的文件", error)
    }
    return IllegalStateException("无法打开这个 ${format.displayName} 文件", error)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfRenderScaleTest
```

Expected: PASS.

- [ ] **Step 5: Compile app unit tests for MuPDF classes**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.mupdf.MuPdfReaderSessionTest
```

Expected: PASS and compile succeeds with `com.artifex.mupdf.fitz.*` imports.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/mupdf/RealMuPdfDocumentAdapter.kt app/src/test/java/com/example/comicdav/feature/reader/mupdf/MuPdfRenderScaleTest.kt
git commit -m "feat: render local documents with mupdf"
```

## Task 6: Dispatch Local Opens To MuPDF

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/feature/reader/LocalComicOpenerTest.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/LocalComicOpener.kt`

- [ ] **Step 1: Write failing tests for archive and document dispatch**

Update `LocalComicOpenerTest.kt` to include these tests and helper types:

```kotlin
@Test
fun openerPassesDocumentDescriptorAndFormatToDocumentFactory() {
    val document = temp.newFile("book.pdf").apply {
        writeBytes(ByteArray(1024) { 8 })
    }
    val documentCalls = mutableListOf<OpenLocalDocumentCall>()
    val archiveCalls = mutableListOf<OpenLocalFdCall>()
    val opener = LocalComicOpener(
        context = ApplicationProvider.getApplicationContext(),
        openSession = { fd, size, format ->
            archiveCalls += OpenLocalFdCall(fd = fd, size = size, format = format)
            FakeReaderSession(pageCount = 1)
        },
        openDocumentSession = { descriptor, fileName, format ->
            documentCalls += OpenLocalDocumentCall(
                fd = descriptor.fd,
                fileName = fileName,
                format = format,
            )
            FakeReaderSession(pageCount = 2)
        },
    )

    val session = opener.open(Uri.fromFile(document), document.name)

    assertEquals(2, session.pageCount)
    assertTrue(archiveCalls.isEmpty())
    assertEquals(listOf("book.pdf"), documentCalls.map { it.fileName })
    assertEquals(listOf(LocalDocumentFormat.Pdf), documentCalls.map { it.format })
    assertTrue(documentCalls.single().fd > 0)
}

@Test
fun openerRejectsUnsupportedLocalComicExtensionBeforeOpeningAnySession() {
    val opener = LocalComicOpener(
        context = ApplicationProvider.getApplicationContext(),
        openSession = { _, _, _ -> error("archive factory should not be called") },
        openDocumentSession = { _, _, _ -> error("document factory should not be called") },
    )

    val error = runCatching {
        opener.open(Uri.fromFile(temp.newFile("book.rar")), "book.rar")
    }.exceptionOrNull()

    assertEquals("暂不支持这个本地阅读格式", error?.message)
}
```

Add imports:

```kotlin
import com.example.comicdav.data.LocalDocumentFormat
```

Add helper:

```kotlin
private data class OpenLocalDocumentCall(
    val fd: Int,
    val fileName: String,
    val format: LocalDocumentFormat,
)
```

Keep the existing archive FD test and update the old unsupported-extension assertion to the new message.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.LocalComicOpenerTest
```

Expected: FAIL because `openDocumentSession` constructor parameter does not exist and unsupported message still differs.

- [ ] **Step 3: Implement local opener dispatch**

Update `LocalComicOpener.kt`:

```kotlin
package com.example.comicdav.feature.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.comicdav.data.LocalArchiveFormat
import com.example.comicdav.data.LocalDocumentFormat
import com.example.comicdav.data.localArchiveFormatForFileName
import com.example.comicdav.data.localDocumentFormatForFileName
import com.example.comicdav.feature.reader.mupdf.MuPdfReaderSession
import com.example.comicdav.feature.reader.mupdf.RealMuPdfDocumentAdapter
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession

typealias OpenLocalFdSessionFactory = (
    fd: Int,
    size: Long,
    format: LocalArchiveFormat,
) -> ComicReaderSession

typealias OpenLocalDocumentSessionFactory = (
    descriptor: ParcelFileDescriptor,
    fileName: String,
    format: LocalDocumentFormat,
) -> ComicReaderSession

class LocalComicOpener(
    private val context: Context,
    private val openSession: OpenLocalFdSessionFactory = { fd, size, format ->
        ComicEngine().openLocalFd(fd, size, format.nativeName)
    },
    private val openDocumentSession: OpenLocalDocumentSessionFactory = { descriptor, fileName, format ->
        val document = RealMuPdfDocumentAdapter().open(descriptor, fileName, format)
        MuPdfReaderSession(document, format)
    },
) {
    fun open(uri: Uri, fileName: String): ComicReaderSession {
        localArchiveFormatForFileName(fileName)?.let { format ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
            val fd = descriptor.detachFd()
            return openSession(fd, size, format)
        }

        localDocumentFormatForFileName(fileName)?.let { format ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            return openDocumentSession(descriptor, fileName, format)
        }

        error("暂不支持这个本地阅读格式")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.reader.LocalComicOpenerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/reader/LocalComicOpener.kt app/src/test/java/com/example/comicdav/feature/reader/LocalComicOpenerTest.kt
git commit -m "feat: route local documents to mupdf"
```

## Task 7: Cover Directory And Library Integration

**Files:**
- Test:
  - `app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryViewModelTest.kt`
  - `app/src/test/java/com/example/comicdav/data/LibraryRepositoryTest.kt`

- [ ] **Step 1: Add a library title regression for local documents**

Add this test before the final closing brace in `LibraryRepositoryTest.kt`:

```kotlin
    @Test
    fun addLocalDocumentStoresTitleWithoutMuPdfDocumentExtension() = runTest {
        val libraryItemId = repository.addLocalComic(
            uri = "content://documents/tree/books/document/book.pdf",
            fileName = "book.pdf",
            size = 100L,
            lastModified = 10L,
        )

        val library = repository.observeLibrary().first()

        assertTrue(libraryItemId > 0L)
        assertEquals("book", library.single().item.title)
        assertEquals("book", library.single().item.displayName)
        assertEquals("book.pdf", library.single().localSource?.fileName)
    }
```

- [ ] **Step 2: Add a local source browser regression for document rows**

In `FileDirectoryViewModelTest.openLocalSourceListsChildrenWithoutRecordingRecentAccess`, update the fake children and assertion:

```kotlin
            children = listOf(
                FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book-cbz", isDirectory = false),
                FileDirectoryBrowserItem("book.pdf", "content://tree/comics/book-pdf", isDirectory = false),
            ),
```

Update the assertion in the same test:

```kotlin
        assertEquals(listOf("Series", "book.cbz", "book.pdf"), viewModel.uiState.entries.map { it.name })
```

- [ ] **Step 3: Run tests to verify they pass**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.filedirectory.FileDirectoryViewModelTest --tests com.example.comicdav.data.LibraryRepositoryTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryViewModelTest.kt app/src/test/java/com/example/comicdav/data/LibraryRepositoryTest.kt
git commit -m "test: cover local document library integration"
```

## Task 8: Full Verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run app unit tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Run Rust tests**

Run:

```bash
cd comic-core && cargo test
```

Expected: PASS.

- [ ] **Step 3: Assemble debug APK**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

Expected: PASS. The APK should package `libmupdf_java.so` for the app's configured ABIs and continue packaging `libcomic_core.so`.

- [ ] **Step 4: Inspect git status**

Run:

```bash
git status --short
```

Expected: clean except for intentionally untracked build outputs ignored by `.gitignore`.
