package com.example.comicdav.feature.reader

import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderImageRequestTest {
    @get:Rule
    val temp = TemporaryFolder()

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
}
