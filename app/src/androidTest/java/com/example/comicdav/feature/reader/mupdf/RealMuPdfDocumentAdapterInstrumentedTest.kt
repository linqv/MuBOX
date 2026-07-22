package com.example.comicdav.feature.reader.mupdf

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comicdav.data.LocalDocumentFormat
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealMuPdfDocumentAdapterInstrumentedTest {
    @Test
    fun opensAndRendersSupportedDocumentFixtures() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureDir = File(targetContext.cacheDir, "mupdf-integration-fixtures").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        val outputDir = File(targetContext.cacheDir, "mupdf-integration-pages").also {
            it.deleteRecursively()
            it.mkdirs()
        }

        fixtures.forEach { fixture ->
            val documentFile = copyAssetToFile(fixture.assetName, File(fixtureDir, fixture.assetName))
            val descriptor = ParcelFileDescriptor.open(documentFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val handle = RealMuPdfDocumentAdapter().open(descriptor, documentFile.name, fixture.format)
            try {
                assertTrue("${fixture.assetName} should report pages", handle.pageCount > 0)

                val outputFile = File(outputDir, "${fixture.assetName}.page-0.jpg")
                handle.renderPageToJpeg(
                    pageIndex = 0,
                    outputFile = outputFile,
                    maxPixels = 8_000_000,
                    quality = 90,
                )

                assertTrue("${fixture.assetName} should render a JPEG", outputFile.isFile)
                assertTrue("${fixture.assetName} render should not be empty", outputFile.length() > 0L)
            } finally {
                handle.close()
            }
        }
    }

    private fun copyAssetToFile(assetName: String, destination: File): File {
        destination.parentFile?.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("mupdf/$assetName")
            .use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        return destination
    }

    private data class Fixture(
        val assetName: String,
        val format: LocalDocumentFormat,
    )

    private companion object {
        val fixtures = listOf(
            Fixture("sample.pdf", LocalDocumentFormat.Pdf),
            Fixture("sample.epub", LocalDocumentFormat.Epub),
            Fixture("sample.mobi", LocalDocumentFormat.Mobi),
            Fixture("sample.azw3", LocalDocumentFormat.Azw3),
        )
    }
}
