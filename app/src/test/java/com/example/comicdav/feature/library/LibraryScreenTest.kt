package com.example.comicdav.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScreenTest {
    @Test
    fun comicLibraryUsesComicPosterKind() {
        assertEquals(com.example.comicdav.ui.MuBoxPosterKind.Comic, libraryPosterKind())
    }
}
