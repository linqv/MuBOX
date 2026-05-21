package com.example.comicdav.video.player

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class LocalVideoUriResolverTest {
    private val authority = "local-video-test"

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        ShadowContentResolver.reset()
    }

    @Test
    fun resolveReturnsNonContentUriUnchanged() {
        val resolver = LocalVideoUriResolver(context)

        val resolved = resolver.resolve("file:///storage/emulated/0/Movies/clip.mp4")

        assertEquals("file:///storage/emulated/0/Movies/clip.mp4", resolved)
    }

    @Test
    fun resolveReturnsReadableMediaStoreDataPathForContentUri() {
        val video = temp.newFile("movie.mp4").apply {
            writeText("video")
        }
        val uri = Uri.parse("content://$authority/data-path")
        registerProvider(TestVideoProvider(dataPath = video.absolutePath))
        val resolver = LocalVideoUriResolver(context)

        val resolved = resolver.resolve(uri.toString())

        assertEquals(video.absolutePath, resolved)
    }

    @Test
    fun resolveFallsBackToDetachedFileDescriptorUriWhenDataPathUnavailable() {
        val video = temp.newFile("fallback.mp4").apply {
            writeText("video")
        }
        val uri = Uri.parse("content://$authority/fd-fallback")
        registerProvider(TestVideoProvider(openFile = video))
        val resolver = LocalVideoUriResolver(context)

        val resolved = resolver.resolve(uri.toString())

        assertTrue(resolved, resolved.startsWith("fd://"))
        val fd = resolved.removePrefix("fd://").toInt()
        assertTrue(fd > 0)
    }

    @Test
    fun resolveSubtitleCopiesContentUriFallbackToCacheFileWithDisplayNameExtension() {
        val subtitle = temp.newFile("source.ass").apply {
            writeText("[Script Info]\nTitle: demo\n")
        }
        val uri = Uri.parse("content://$authority/subtitle")
        registerProvider(TestVideoProvider(openFile = subtitle))
        val resolver = LocalVideoUriResolver(context)

        val resolved = resolver.resolveSubtitle(uri.toString(), displayName = "anime.ass")

        assertTrue(resolved, resolved.endsWith(".ass"))
        assertEquals("[Script Info]\nTitle: demo\n", File(resolved).readText())
    }

    @Test
    fun resolveSubtitlePreservesExtensionWhenDisplayNameHasNoAsciiBaseName() {
        val subtitle = temp.newFile("source.sub").apply {
            writeText("[SUBTITLE]\n00:00:01.00,00:00:02.00\n你好\n")
        }
        val uri = Uri.parse("content://$authority/non-ascii-subtitle")
        registerProvider(TestVideoProvider(openFile = subtitle))
        val resolver = LocalVideoUriResolver(context)

        val resolved = resolver.resolveSubtitle(uri.toString(), displayName = "中文字幕.sub")

        assertTrue(resolved, resolved.endsWith(".sub"))
        assertEquals("[SUBTITLE]\n00:00:01.00,00:00:02.00\n你好\n", File(resolved).readText())
    }

    private fun registerProvider(provider: ContentProvider) {
        provider.attachInfo(
            context,
            ProviderInfo().apply {
                authority = this@LocalVideoUriResolverTest.authority
            },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
}

private class TestVideoProvider(
    private val dataPath: String? = null,
    private val openFile: File? = null,
) : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val path = dataPath ?: return null
        return MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply {
            addRow(arrayOf(path))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = openFile ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
