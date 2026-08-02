package org.mubox.reader.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScreenTest {
    @Test
    fun comicLibraryUsesComicPosterKind() {
        assertEquals(org.mubox.reader.ui.MuBoxPosterKind.Comic, libraryPosterKind())
    }
}
