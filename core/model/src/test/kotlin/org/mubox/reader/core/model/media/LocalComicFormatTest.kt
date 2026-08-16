package org.mubox.reader.core.model.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalComicFormatTest {
    @Test
    fun supportedLocalComicNamesIncludeZipAndCbzButNotOtherArchivesOrDocuments() {
        assertTrue(isSupportedLocalComicFileName("book.cbz"))
        assertTrue(isSupportedLocalComicFileName("book.zip"))
        assertFalse(isSupportedLocalComicFileName("book.cb7"))
        assertFalse(isSupportedLocalComicFileName("book.7z"))
        assertFalse(isSupportedLocalComicFileName("book.cbt"))
        assertFalse(isSupportedLocalComicFileName("book.tar"))
        assertFalse(isSupportedLocalComicFileName("book.pdf"))
        assertFalse(isSupportedLocalComicFileName("book.epub"))
        assertFalse(isSupportedLocalComicFileName("book.mobi"))
        assertFalse(isSupportedLocalComicFileName("book.azw3"))
        assertFalse(isSupportedLocalComicFileName("book.cbr"))
        assertFalse(isSupportedLocalComicFileName("book.rar"))
        assertFalse(isSupportedLocalComicFileName("notes.txt"))
    }

    @Test
    fun localComicTitleStripsSupportedArchiveSuffixes() {
        assertEquals("book", localComicTitleFromFileName("book.cbz"))
        assertEquals("book", localComicTitleFromFileName("book.zip"))
        assertEquals("book.rar", localComicTitleFromFileName("book.rar"))
        assertEquals("book.pdf", localComicTitleFromFileName("book.pdf"))
    }
}
