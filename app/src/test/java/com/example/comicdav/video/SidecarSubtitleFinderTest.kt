package com.example.comicdav.video

import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarSubtitleFinderTest {
    @Test
    fun findsMatchingSubtitleFilesNextToVideo() {
        val candidates = listOf(
            Candidate("Movie.srt"),
            Candidate("Movie-en.srt"),
            Candidate("Movie_en.srt"),
            Candidate("Movie.zh-Hans.srt"),
            Candidate("Movie.zh-Hans.ass"),
            Candidate("Movie.sub"),
            Candidate("Movie.vtt"),
            Candidate("Movie Trailer.srt"),
            Candidate("Movie-Trailer.srt"),
            Candidate("Other.srt"),
            Candidate("Movie.nfo"),
            Candidate("Movie.en.vtt", isDirectory = true),
        )

        val matches = findSidecarSubtitles(
            videoFileName = "Movie.mkv",
            candidates = candidates,
            nameOf = Candidate::name,
            isDirectoryOf = Candidate::isDirectory,
        )

        assertEquals(
            listOf("Movie.srt", "Movie.sub", "Movie.vtt", "Movie-en.srt", "Movie.zh-Hans.ass", "Movie.zh-Hans.srt", "Movie_en.srt"),
            matches.map { it.name },
        )
    }

    @Test
    fun matchesBaseNameAndExtensionIgnoringCase() {
        val candidates = listOf(
            Candidate("EPISODE 01.SRT"),
            Candidate("episode 01.en.VTT"),
            Candidate("episode 010.srt"),
        )

        val matches = findSidecarSubtitles(
            videoFileName = "Episode 01.MP4",
            candidates = candidates,
            nameOf = Candidate::name,
            isDirectoryOf = Candidate::isDirectory,
        )

        assertEquals(listOf("EPISODE 01.SRT", "episode 01.en.VTT"), matches.map { it.name })
    }

    private data class Candidate(
        val name: String,
        val isDirectory: Boolean = false,
    )
}
