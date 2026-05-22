package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVideoQueueIntegrationTest {
    @Test
    fun localDirectoryVideoOpenBuildsQueueFromCurrentDirectoryVideos() {
        val source = mainActivitySourceFile().readText()

        assertTrue(source.contains("val localPlaybackQueue = buildLocalDirectoryPlaybackQueue("))
        assertTrue(source.contains("entries = fileDirectoryUiState.entries"))
        assertTrue(source.contains("currentItem = item"))
        assertTrue(source.contains("queue = localPlaybackQueue"))
        assertTrue(source.contains("localVideoPlaybackKey("))
        assertTrue(source.contains("VideoQueueSource.LOCAL"))
    }

    @Test
    fun webDavVideoOpenBuildsQueueFromCurrentRemoteDirectoryVideos() {
        val source = mainActivitySourceFile().readText()

        assertTrue(source.contains("val webDavPlaybackQueue = buildWebDavPlaybackQueue("))
        assertTrue(source.contains("items = uiState.items"))
        assertTrue(source.contains("currentItem = item"))
        assertTrue(source.contains("queue = webDavPlaybackQueue"))
        assertTrue(source.contains("webDavVideoPlaybackKey("))
        assertTrue(source.contains("VideoQueueSource.WEB_DAV"))
    }

    private fun mainActivitySourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/MainActivity.kt"),
            File("app/src/main/java/com/example/comicdav/MainActivity.kt"),
        ).first { it.isFile }
}
