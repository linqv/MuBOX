package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuBoxMpvViewFactoryTest {
    @Test
    fun factoryInflatesMpvViewFromRealAndroidXmlLayout() {
        val source = sourceFile().readText()
        val layout = layoutFile().readText()

        assertTrue(source.contains("LayoutInflater.from(context)"))
        assertTrue(source.contains(".inflate(R.layout.view_mubox_mpv, parent, false)"))
        assertFalse(source.contains(".inflate(R.layout.view_mubox_mpv, null)"))
        assertFalse(source.contains("Xml.asAttributeSet"))
        assertTrue(layout.contains("com.example.comicdav.video.player.MuBoxMpvView"))
    }

    private fun sourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
        ).first { it.isFile }

    private fun layoutFile(): File =
        listOf(
            File("src/main/res/layout/view_mubox_mpv.xml"),
            File("app/src/main/res/layout/view_mubox_mpv.xml"),
        ).first { it.isFile }
}
