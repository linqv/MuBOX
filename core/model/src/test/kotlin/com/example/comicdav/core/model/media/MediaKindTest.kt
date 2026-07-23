package com.example.comicdav.core.model.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKindTest {
    @Test
    fun identifiesSupportedMediaKindsFromFileNames() {
        assertEquals(MediaKind.Comic, mediaKindForFileName("Book.CBZ"))
        assertEquals(MediaKind.Comic, mediaKindForFileName("scan.pdf"))
        assertEquals(MediaKind.Video, mediaKindForFileName("movie.mkv"))
        assertEquals(MediaKind.Video, mediaKindForFileName("clip.MP4"))
        assertEquals(MediaKind.Audio, mediaKindForFileName("track.flac"))
        assertEquals(MediaKind.Subtitle, mediaKindForFileName("movie.srt"))
        assertEquals(MediaKind.Subtitle, mediaKindForFileName("movie.ass"))
        assertEquals(MediaKind.Subtitle, mediaKindForFileName("movie.vtt"))
        assertEquals(MediaKind.Subtitle, mediaKindForFileName("movie.sub"))
        assertEquals(MediaKind.Unknown, mediaKindForFileName("notes.txt"))
        assertEquals(MediaKind.Unknown, mediaKindForFileName("archive"))
    }

    @Test
    fun directoryOverridesFileExtension() {
        assertEquals(
            MediaKind.Directory,
            mediaKindFor(name = "video.mp4", isDirectory = true),
        )
    }

    @Test
    fun plainTextMimeTypeDoesNotMakeUnknownTextFilesBrowsableSubtitles() {
        assertEquals(
            MediaKind.Unknown,
            mediaKindFor(name = "notes.txt", isDirectory = false, mimeType = "text/plain"),
        )
        assertEquals(
            MediaKind.Subtitle,
            mediaKindFor(name = "movie.srt", isDirectory = false, mimeType = "text/plain"),
        )
    }

    @Test
    fun firstPhaseBrowseKindsExcludeAudioAndUnknownFiles() {
        assertTrue(MediaKind.Directory.isBrowsableInSources)
        assertTrue(MediaKind.Comic.isBrowsableInSources)
        assertTrue(MediaKind.Video.isBrowsableInSources)
        assertTrue(MediaKind.Subtitle.isBrowsableInSources)
        assertEquals(false, MediaKind.Audio.isBrowsableInSources)
        assertEquals(false, MediaKind.Unknown.isBrowsableInSources)
    }

    @Test
    fun mapsCommonMediaMimeTypes() {
        assertEquals("video/mp4", mimeTypeForMediaFileName("clip.mp4"))
        assertEquals("video/x-matroska", mimeTypeForMediaFileName("movie.mkv"))
        assertEquals("audio/flac", mimeTypeForMediaFileName("track.flac"))
        assertEquals("application/x-subrip", mimeTypeForMediaFileName("movie.srt"))
        assertEquals("text/x-ass", mimeTypeForMediaFileName("movie.ass"))
        assertEquals("text/vtt", mimeTypeForMediaFileName("movie.vtt"))
        assertEquals("text/plain", mimeTypeForMediaFileName("movie.sub"))
        assertEquals(null, mimeTypeForMediaFileName("notes.txt"))
    }
}
