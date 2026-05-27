package com.example.comicdav.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MuBoxDesignSystemTest {
    @Test
    fun defaultPaletteMapsToMuBoxFoundationColorRoles() {
        val colorScheme = comicDavColorSchemeFor(AppColorPalette.DEFAULT)
        val colors = muBoxColorsFor(colorScheme)

        assertEquals(colorScheme.background, colors.background)
        assertEquals(colorScheme.surfaceContainer, colors.panel)
        assertEquals(colorScheme.surfaceContainerHigh, colors.panelHigh)
        assertEquals(colorScheme.primary, colors.mediaAccent)
        assertEquals(colorScheme.secondary, colors.comicAccent)
        assertEquals(colorScheme.tertiary, colors.statusAccent)

        assertTrue("background should stay dark", colors.background.luminance() < 0.05f)
        assertTrue("panel should layer above background", colors.panel.luminance() > colors.background.luminance())
        assertTrue("panelHigh should layer above panel", colors.panelHigh.luminance() > colors.panel.luminance())
        assertTrue("text should be readable on dark surfaces", colors.text.luminance() > 0.70f)
    }

    @Test
    fun mediaKindLabelsAreLocalizedForMediaFoundationRows() {
        assertEquals("文件夹", muBoxMediaKindLabel(MediaKind.Directory))
        assertEquals("漫画文件", muBoxMediaKindLabel(MediaKind.Comic))
        assertEquals("视频文件", muBoxMediaKindLabel(MediaKind.Video))
        assertEquals("字幕文件", muBoxMediaKindLabel(MediaKind.Subtitle))
        assertEquals("音频文件", muBoxMediaKindLabel(MediaKind.Audio))
        assertEquals("文件", muBoxMediaKindLabel(MediaKind.Unknown))
    }

    @Test
    fun posterAspectRatiosSeparateComicAndVideoSurfaces() {
        assertEquals(0.72f, muBoxPosterAspectRatio(MuBoxPosterKind.Comic), 0.0001f)
        assertEquals(16f / 9f, muBoxPosterAspectRatio(MuBoxPosterKind.Video), 0.0001f)
    }

    @Test
    fun metricsExposeFoundationSizingTokens() {
        assertEquals(44.dp, MuBoxMetrics.MinTouchTargetDp)
        assertEquals(14.dp, MuBoxMetrics.DenseRowCornerDp)
        assertEquals(22.dp, MuBoxMetrics.PlayerPanelCornerDp)
        assertEquals(64.dp, MuBoxMetrics.PlayerCenterControlVisualDp)
        assertEquals(80.dp, MuBoxMetrics.PlayerCenterControlTouchDp)
    }

    @Test
    fun sharedComposableApiKeepsModifierAsFirstOptionalParameter() {
        assertSignatureOrder(
            functionName = "MuBoxPageHeader",
            orderedParameters = listOf("title", "modifier", "subtitle", "trailing"),
        )
        assertSignatureOrder(
            functionName = "MuBoxMessagePanel",
            orderedParameters = listOf("text", "modifier", "isError", "onDismiss", "dismissLabel"),
        )
        assertSignatureOrder(
            functionName = "MuBoxEmptyState",
            orderedParameters = listOf("icon", "title", "modifier", "body", "actionLabel", "onAction"),
        )
        assertSignatureOrder(
            functionName = "MuBoxDenseMediaRow",
            orderedParameters = listOf(
                "title",
                "mediaKind",
                "onClick",
                "modifier",
                "subtitle",
                "selected",
                "onLongClick",
                "onLongClickLabel",
                "trailing",
            ),
        )
    }

    @Test
    fun denseMediaRowExposesSelectedAndLongPressAccessibilityContracts() {
        val source = muBoxComponentsSource()

        assertTrue("MuBoxDenseMediaRow should use combinedClickable for long-press actions", "combinedClickable" in source)
        assertTrue("MuBoxDenseMediaRow should expose selected semantics", "this.selected = selected" in source)
        assertTrue("MuBoxDenseMediaRow should pass onLongClick to combinedClickable", "onLongClick = onLongClick" in source)
        assertTrue(
            "MuBoxDenseMediaRow should pass onLongClickLabel to combinedClickable",
            "onLongClickLabel = onLongClickLabel" in source,
        )
    }

    private fun assertSignatureOrder(functionName: String, orderedParameters: List<String>) {
        val signature = functionSignature(functionName)
        val parameterOffsets = orderedParameters.map { parameterName ->
            val offset = signature.indexOf("$parameterName:")
            assertTrue("$functionName should declare parameter '$parameterName'", offset >= 0)
            offset
        }

        assertEquals(
            "$functionName parameter order changed",
            parameterOffsets.sorted(),
            parameterOffsets,
        )
    }

    private fun functionSignature(functionName: String): String {
        val source = muBoxComponentsSource()
        val start = source.indexOf("fun $functionName(")
        assertTrue("Missing function $functionName", start >= 0)

        val bodyStart = source.indexOf(") {", start)
        assertTrue("Missing function body for $functionName", bodyStart >= 0)
        return source.substring(start, bodyStart)
    }

    private fun muBoxComponentsSource(): String =
        File("src/main/java/com/example/comicdav/ui/MuBoxComponents.kt").readText()
}
