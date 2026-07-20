package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVideoQueueRemovalTest {
    @Test
    fun localAndWebDavVideoOpenNoLongerBuildPlaybackQueues() {
        val source = mainActivitySourceFile().readText()

        assertFalse(source.contains("val localPlaybackQueue = buildLocalDirectoryPlaybackQueue("))
        assertFalse(source.contains("val webDavPlaybackQueue = buildWebDavPlaybackQueue("))
        assertFalse(source.contains("queue = localPlaybackQueue"))
        assertFalse(source.contains("queue = webDavPlaybackQueue"))
        assertFalse(source.contains("VideoQueueSource."))
    }

    @Test
    fun playerActivityNoLongerDefinesQueueExtras() {
        val source = playerActivitySourceFile().readText()

        assertFalse(source.contains("EXTRA_QUEUE_"))
        assertFalse(source.contains("putQueueExtras"))
        assertFalse(source.contains("playbackQueue()"))
    }

    @Test
    fun mainActivityRestoresMainPortraitAfterVideoPlayerResult() {
        val source = mainActivitySourceFile().readText()

        assertTrue(source.contains("ActivityResultContracts.StartActivityForResult()"))
        assertTrue(source.contains("fun openVideoPlayer(intent: Intent)"))
        assertTrue(source.contains("videoPlayerLauncher.launch(intent)"))
        assertTrue(source.contains("forceMainPortraitState.value = true"))
        assertFalse(source.contains("context.startActivity(\n            VideoPlayerActivity."))
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
}
