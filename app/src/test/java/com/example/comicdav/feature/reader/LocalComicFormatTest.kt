package com.example.comicdav.feature.reader

import com.example.comicdav.data.LocalArchiveFormat
import com.example.comicdav.data.isSupportedLocalComicFileName
import com.example.comicdav.data.localArchiveFormatForFileName
import com.example.comicdav.data.localComicTitleFromFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalComicFormatTest {
    @Test
    fun supportedLocalComicNamesIncludeZipSevenZAndTarButNotRar() {
        assertTrue(isSupportedLocalComicFileName("book.cbz"))
        assertTrue(isSupportedLocalComicFileName("book.zip"))
        assertTrue(isSupportedLocalComicFileName("book.cb7"))
        assertTrue(isSupportedLocalComicFileName("book.7z"))
        assertTrue(isSupportedLocalComicFileName("book.cbt"))
        assertTrue(isSupportedLocalComicFileName("book.tar"))
        assertFalse(isSupportedLocalComicFileName("book.cbr"))
        assertFalse(isSupportedLocalComicFileName("book.rar"))
        assertFalse(isSupportedLocalComicFileName("notes.txt"))
    }

    @Test
    fun localArchiveFormatIsDerivedFromSupportedFileName() {
        assertEquals(LocalArchiveFormat.Zip, localArchiveFormatForFileName("book.cbz"))
        assertEquals(LocalArchiveFormat.Zip, localArchiveFormatForFileName("book.zip"))
        assertEquals(LocalArchiveFormat.SevenZ, localArchiveFormatForFileName("book.cb7"))
        assertEquals(LocalArchiveFormat.SevenZ, localArchiveFormatForFileName("book.7z"))
        assertEquals(LocalArchiveFormat.Tar, localArchiveFormatForFileName("book.cbt"))
        assertEquals(LocalArchiveFormat.Tar, localArchiveFormatForFileName("book.tar"))
        assertEquals(null, localArchiveFormatForFileName("book.rar"))
    }

    @Test
    fun localComicTitleStripsSupportedArchiveSuffixes() {
        assertEquals("book", localComicTitleFromFileName("book.cbz"))
        assertEquals("book", localComicTitleFromFileName("book.zip"))
        assertEquals("book", localComicTitleFromFileName("book.cb7"))
        assertEquals("book", localComicTitleFromFileName("book.7z"))
        assertEquals("book", localComicTitleFromFileName("book.cbt"))
        assertEquals("book", localComicTitleFromFileName("book.tar"))
        assertEquals("book.rar", localComicTitleFromFileName("book.rar"))
    }
}
