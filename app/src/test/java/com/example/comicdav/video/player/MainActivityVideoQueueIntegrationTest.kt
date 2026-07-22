package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVideoEpisodeIntegrationTest {
    @Test
    fun localAndWebDavDirectoryVideosBuildEpisodeQueues() {
        val source = mainActivitySourceFile().readText()

        assertTrue(source.contains("buildLocalDirectoryEpisodeQueue("))
        assertTrue(source.contains("buildWebDavDirectoryEpisodeQueue("))
        assertTrue(source.contains("fileDirectoryViewModel.playbackDirectoryEntries()"))
        assertTrue(source.contains("webDavViewModel.playbackDirectoryItems()"))
        assertTrue(source.contains("episodeQueue = episodeQueue"))
        assertTrue(source.contains("findSidecarSubtitles("))
    }

    @Test
    fun playerActivityCarriesAndSwitchesEpisodeQueue() {
        val source = playerActivitySourceFile().readText()

        assertTrue(source.contains("EXTRA_EPISODE_QUEUE_ID"))
        assertTrue(source.contains("VideoEpisodeQueueRegistry.register"))
        assertTrue(source.contains("putEpisodeQueueExtra"))
        assertTrue(source.contains("private fun switchToEpisode("))
        assertTrue(source.contains("EpisodeSelectionPage("))
    }

    @Test
    fun episodeSelectionContentRespectsNavigationBarInsets() {
        val source = playerControlsSourceFile().readText()
        val selectionPage = source.substringAfter("internal fun EpisodeSelectionPage(")
            .substringBefore("internal fun PlayerBottomControls(")

        assertTrue(selectionPage.contains(".navigationBarsPadding()"))
    }

    @Test
    fun mainActivityRestoresMainPortraitAfterVideoPlayerResult() {
        val source = mainActivitySourceFile().readText()

        assertTrue(source.contains("ActivityResultContracts.StartActivityForResult()"))
        assertTrue(source.contains("fun openVideoPlayer(intent: Intent)"))
        assertTrue(source.contains("videoPlayerLauncher.launch(intent)"))
        assertTrue(source.contains("forceMainPortraitState.value = true"))
    }

    private fun mainActivitySourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/MainActivity.kt"),
            File("app/src/main/java/com/example/comicdav/MainActivity.kt"),
        ).first { it.isFile }

    private fun playerActivitySourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
        ).first { it.isFile }

    private fun playerControlsSourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt"),
        ).first { it.isFile }
}
