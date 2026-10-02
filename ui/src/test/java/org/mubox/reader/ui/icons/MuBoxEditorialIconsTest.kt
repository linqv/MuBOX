package org.mubox.reader.ui.icons

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class MuBoxEditorialIconsTest {

    @Test
    fun editorialIconsHaveStandard24dpDimensions() {
        val icons = listOf(
            MuBoxEditorialIcons.HomeOutlined,
            MuBoxEditorialIcons.HomeFilled,
            MuBoxEditorialIcons.SourcesOutlined,
            MuBoxEditorialIcons.SourcesFilled,
            MuBoxEditorialIcons.DownloadsOutlined,
            MuBoxEditorialIcons.DownloadsFilled,
            MuBoxEditorialIcons.SettingsOutlined,
            MuBoxEditorialIcons.SettingsFilled,
            MuBoxEditorialIcons.ComicBook,
            MuBoxEditorialIcons.CinemaVideo,
            MuBoxEditorialIcons.Search,
        )

        for (icon in icons) {
            assertNotNull(icon)
            assertEquals("Default width must be 24dp for ${icon.name}", 24.dp, icon.defaultWidth)
            assertEquals("Default height must be 24dp for ${icon.name}", 24.dp, icon.defaultHeight)
            assertEquals("Viewport width must be 24f for ${icon.name}", 24f, icon.viewportWidth)
            assertEquals("Viewport height must be 24f for ${icon.name}", 24f, icon.viewportHeight)
        }
    }

    @Test
    fun outlinedAndFilledIconsAreDistinct() {
        assertNotEquals(MuBoxEditorialIcons.HomeOutlined, MuBoxEditorialIcons.HomeFilled)
        assertNotEquals(MuBoxEditorialIcons.SourcesOutlined, MuBoxEditorialIcons.SourcesFilled)
        assertNotEquals(MuBoxEditorialIcons.DownloadsOutlined, MuBoxEditorialIcons.DownloadsFilled)
        assertNotEquals(MuBoxEditorialIcons.SettingsOutlined, MuBoxEditorialIcons.SettingsFilled)
    }

    @Test
    fun xmlDrawablesExistAndHaveCorrectAttributes() {
        val drawableNames = listOf(
            "ic_editorial_home.xml",
            "ic_editorial_sources.xml",
            "ic_editorial_downloads.xml",
            "ic_editorial_settings.xml",
            "ic_editorial_book.xml",
            "ic_editorial_video.xml",
            "ic_editorial_search.xml",
        )

        val resDir = File("src/main/res/drawable")
        for (name in drawableNames) {
            val file = File(resDir, name)
            assert(file.exists()) { "Expected $name to exist in ${file.absolutePath}" }
            val content = file.readText()
            assert(content.contains("android:width=\"24dp\"")) { "$name missing 24dp width" }
            assert(content.contains("android:height=\"24dp\"")) { "$name missing 24dp height" }
            assert(content.contains("android:viewportWidth=\"24\"")) { "$name missing viewportWidth 24" }
            assert(content.contains("android:viewportHeight=\"24\"")) { "$name missing viewportHeight 24" }
            assert(content.contains("android:strokeWidth=\"1.5\"")) { "$name missing strokeWidth 1.5" }
            assert(content.contains("android:strokeLineCap=\"round\"")) { "$name missing round lineCap" }
            assert(content.contains("android:strokeLineJoin=\"round\"")) { "$name missing round lineJoin" }
        }
    }
}
