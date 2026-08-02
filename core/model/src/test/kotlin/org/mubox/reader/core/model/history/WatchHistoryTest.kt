package org.mubox.reader.core.model.history

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchHistoryTest {
    @Test
    fun percentEncodedUtf8TitleIsDecodedForDisplay() {
        assertEquals(
            "中文漫画.cbz",
            decodePercentEncodedMediaTitle("%E4%B8%AD%E6%96%87%E6%BC%AB%E7%94%BB.cbz"),
        )
    }

    @Test
    fun literalPlusSignIsPreservedWhilePercentEncodedSpacesAreDecoded() {
        assertEquals(
            "C++ Guide.cbz",
            decodePercentEncodedMediaTitle("C++%20Guide.cbz"),
        )
    }

    @Test
    fun malformedPercentEncodingKeepsOriginalTitle() {
        assertEquals(
            "100% fun.cbz",
            decodePercentEncodedMediaTitle("100% fun.cbz"),
        )
    }
}
