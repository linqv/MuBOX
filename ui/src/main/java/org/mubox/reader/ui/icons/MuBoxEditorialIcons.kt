package org.mubox.reader.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke 1.5dp stroke Anthropic-inspired vector icon system for MuBOX.
 *
 * Characteristics:
 * - 24x24 grid with 1.5dp strokes
 * - Round caps and round joins for warmth and editorial restraint
 * - Precision geometric alignment
 */
object MuBoxEditorialIcons {

    private const val StrokeWidth = 1.5f
    private val DefaultColor = SolidColor(Color.Black)

    /**
     * Pavilion / Study: clean 1.5dp geometric lines, roof, door line.
     */
    val HomeOutlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "HomeOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Roof
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 10.5f)
                lineTo(12f, 3.5f)
                lineTo(20.5f, 10.5f)
            }
            // Pavilion walls & baseline
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.5f, 9.5f)
                lineTo(5.5f, 19.5f)
                curveTo(5.5f, 20.05f, 5.95f, 20.5f, 6.5f, 20.5f)
                lineTo(17.5f, 20.5f)
                curveTo(18.05f, 20.5f, 18.5f, 20.05f, 18.5f, 19.5f)
                lineTo(18.5f, 9.5f)
            }
            // Door portal
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 20.5f)
                lineTo(10f, 14.5f)
                curveTo(10f, 14.22f, 10.22f, 14f, 10.5f, 14f)
                lineTo(13.5f, 14f)
                curveTo(13.78f, 14f, 14f, 14.22f, 14f, 14.5f)
                lineTo(14f, 20.5f)
            }
        }.build()
    }

    val HomeFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "HomeFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Filled pavilion silhouette with cutout door
            path(
                fill = DefaultColor,
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.EvenOdd,
            ) {
                // Outer pavilion contour
                moveTo(12f, 3.5f)
                lineTo(20.5f, 10.5f)
                lineTo(18.5f, 10.5f)
                lineTo(18.5f, 19.5f)
                curveTo(18.5f, 20.05f, 18.05f, 20.5f, 17.5f, 20.5f)
                lineTo(6.5f, 20.5f)
                curveTo(5.95f, 20.5f, 5.5f, 20.05f, 5.5f, 19.5f)
                lineTo(5.5f, 10.5f)
                lineTo(3.5f, 10.5f)
                close()

                // Door cutout
                moveTo(10f, 20.5f)
                lineTo(14f, 20.5f)
                lineTo(14f, 14.5f)
                curveTo(14f, 14.22f, 13.78f, 14f, 13.5f, 14f)
                lineTo(10.5f, 14f)
                curveTo(10.22f, 14f, 10f, 14.22f, 10f, 14.5f)
                close()
            }
        }.build()
    }

    /**
     * Folio Archive / Codex: clean 1.5dp frame with ledger lines.
     */
    val SourcesOutlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "SourcesOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Outer folio frame
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.5f, 3.5f)
                lineTo(17.5f, 3.5f)
                curveTo(18.6f, 3.5f, 19.5f, 4.4f, 19.5f, 5.5f)
                lineTo(19.5f, 18.5f)
                curveTo(19.5f, 19.6f, 18.6f, 20.5f, 17.5f, 20.5f)
                lineTo(6.5f, 20.5f)
                curveTo(5.4f, 20.5f, 4.5f, 19.6f, 4.5f, 18.5f)
                lineTo(4.5f, 5.5f)
                curveTo(4.5f, 4.4f, 5.4f, 3.5f, 6.5f, 3.5f)
                close()
            }
            // Spine margin line
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 3.5f)
                lineTo(9f, 20.5f)
            }
            // Ledger lines
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 8f)
                lineTo(16.5f, 8f)

                moveTo(12f, 12f)
                lineTo(16.5f, 12f)

                moveTo(12f, 16f)
                lineTo(15f, 16f)
            }
        }.build()
    }

    val SourcesFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "SourcesFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Filled folio with cutout ledger lines and spine
            path(
                fill = DefaultColor,
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.EvenOdd,
            ) {
                // Outer folio
                moveTo(6.5f, 3.5f)
                lineTo(17.5f, 3.5f)
                curveTo(18.6f, 3.5f, 19.5f, 4.4f, 19.5f, 5.5f)
                lineTo(19.5f, 18.5f)
                curveTo(19.5f, 19.6f, 18.6f, 20.5f, 17.5f, 20.5f)
                lineTo(6.5f, 20.5f)
                curveTo(5.4f, 20.5f, 4.5f, 19.6f, 4.5f, 18.5f)
                lineTo(4.5f, 5.5f)
                curveTo(4.5f, 4.4f, 5.4f, 3.5f, 6.5f, 3.5f)
                close()

                // Spine slit cutout
                moveTo(8.5f, 4.5f)
                lineTo(9.5f, 4.5f)
                lineTo(9.5f, 19.5f)
                lineTo(8.5f, 19.5f)
                close()

                // Ledger 1 cutout
                moveTo(12f, 7.5f)
                lineTo(16.5f, 7.5f)
                lineTo(16.5f, 8.7f)
                lineTo(12f, 8.7f)
                close()

                // Ledger 2 cutout
                moveTo(12f, 11.5f)
                lineTo(16.5f, 11.5f)
                lineTo(16.5f, 12.7f)
                lineTo(12f, 12.7f)
                close()

                // Ledger 3 cutout
                moveTo(12f, 15.5f)
                lineTo(15f, 15.5f)
                lineTo(15f, 16.7f)
                lineTo(12f, 16.7f)
                close()
            }
        }.build()
    }

    /**
     * Tray & Arrow: clean open tray with downward arrow.
     */
    val DownloadsOutlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "DownloadsOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Open tray
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 14f)
                lineTo(4f, 19f)
                curveTo(4f, 19.83f, 4.67f, 20.5f, 5.5f, 20.5f)
                lineTo(18.5f, 20.5f)
                curveTo(19.33f, 20.5f, 20f, 19.83f, 20f, 19f)
                lineTo(20f, 14f)
            }
            // Downward arrow shaft & head
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.5f)
                lineTo(12f, 15f)

                moveTo(7.5f, 10.5f)
                lineTo(12f, 15f)
                lineTo(16.5f, 10.5f)
            }
        }.build()
    }

    val DownloadsFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "DownloadsFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Open tray
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 14f)
                lineTo(4f, 19f)
                curveTo(4f, 19.83f, 4.67f, 20.5f, 5.5f, 20.5f)
                lineTo(18.5f, 20.5f)
                curveTo(19.33f, 20.5f, 20f, 19.83f, 20f, 19f)
                lineTo(20f, 14f)
            }
            // Solid downward arrow
            path(
                fill = DefaultColor,
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10.5f, 3.5f)
                lineTo(13.5f, 3.5f)
                lineTo(13.5f, 10f)
                lineTo(16.5f, 10f)
                lineTo(12f, 15f)
                lineTo(7.5f, 10f)
                lineTo(10.5f, 10f)
                close()
            }
        }.build()
    }

    /**
     * Curated Tuner: 3 horizontal tracks with indicator notches.
     */
    val SettingsOutlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "SettingsOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Track 1
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 6.5f)
                lineTo(6.5f, 6.5f)
                moveTo(10.5f, 6.5f)
                lineTo(20.5f, 6.5f)
            }
            // Notch 1 (outlined circle)
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.5f, 4.5f)
                curveTo(9.6f, 4.5f, 10.5f, 5.4f, 10.5f, 6.5f)
                curveTo(10.5f, 7.6f, 9.6f, 8.5f, 8.5f, 8.5f)
                curveTo(7.4f, 8.5f, 6.5f, 7.6f, 6.5f, 6.5f)
                curveTo(6.5f, 5.4f, 7.4f, 4.5f, 8.5f, 4.5f)
                close()
            }
            // Track 2
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 12f)
                lineTo(13.5f, 12f)
                moveTo(17.5f, 12f)
                lineTo(20.5f, 12f)
            }
            // Notch 2 (outlined circle)
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15.5f, 10f)
                curveTo(16.6f, 10f, 17.5f, 10.9f, 17.5f, 12f)
                curveTo(17.5f, 13.1f, 16.6f, 14f, 15.5f, 14f)
                curveTo(14.4f, 14f, 13.5f, 13.1f, 13.5f, 12f)
                curveTo(13.5f, 10.9f, 14.4f, 10f, 15.5f, 10f)
                close()
            }
            // Track 3
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 17.5f)
                lineTo(7.5f, 17.5f)
                moveTo(11.5f, 17.5f)
                lineTo(20.5f, 17.5f)
            }
            // Notch 3 (outlined circle)
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 15.5f)
                curveTo(10.6f, 15.5f, 11.5f, 16.4f, 11.5f, 17.5f)
                curveTo(11.5f, 18.6f, 10.6f, 19.5f, 9.5f, 19.5f)
                curveTo(8.4f, 19.5f, 7.5f, 18.6f, 7.5f, 17.5f)
                curveTo(7.5f, 16.4f, 8.4f, 15.5f, 9.5f, 15.5f)
                close()
            }
        }.build()
    }

    val SettingsFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "SettingsFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Track 1
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 6.5f)
                lineTo(20.5f, 6.5f)
            }
            // Notch 1 (solid circle)
            path(
                fill = DefaultColor,
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.5f, 4.5f)
                curveTo(9.6f, 4.5f, 10.5f, 5.4f, 10.5f, 6.5f)
                curveTo(10.5f, 7.6f, 9.6f, 8.5f, 8.5f, 8.5f)
                curveTo(7.4f, 8.5f, 6.5f, 7.6f, 6.5f, 6.5f)
                curveTo(6.5f, 5.4f, 7.4f, 4.5f, 8.5f, 4.5f)
                close()
            }
            // Track 2
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 12f)
                lineTo(20.5f, 12f)
            }
            // Notch 2 (solid circle)
            path(
                fill = DefaultColor,
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15.5f, 10f)
                curveTo(16.6f, 10f, 17.5f, 10.9f, 17.5f, 12f)
                curveTo(17.5f, 13.1f, 16.6f, 14f, 15.5f, 14f)
                curveTo(14.4f, 14f, 13.5f, 13.1f, 13.5f, 12f)
                curveTo(13.5f, 10.9f, 14.4f, 10f, 15.5f, 10f)
                close()
            }
            // Track 3
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 17.5f)
                lineTo(20.5f, 17.5f)
            }
            // Notch 3 (solid circle)
            path(
                fill = DefaultColor,
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 15.5f)
                curveTo(10.6f, 15.5f, 11.5f, 16.4f, 11.5f, 17.5f)
                curveTo(11.5f, 18.6f, 10.6f, 19.5f, 9.5f, 19.5f)
                curveTo(8.4f, 19.5f, 7.5f, 18.6f, 7.5f, 17.5f)
                curveTo(7.5f, 16.4f, 8.4f, 15.5f, 9.5f, 15.5f)
                close()
            }
        }.build()
    }

    /**
     * Open Codex: gentle curved leaves with center binding spine.
     */
    val ComicBook: ImageVector by lazy {
        ImageVector.Builder(
            name = "ComicBook",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Left page
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 6.5f)
                curveTo(8.5f, 4.8f, 5.5f, 5.2f, 3.5f, 6f)
                lineTo(3.5f, 18f)
                curveTo(5.5f, 17.2f, 8.5f, 16.8f, 12f, 18.5f)
            }
            // Right page
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 6.5f)
                curveTo(15.5f, 4.8f, 18.5f, 5.2f, 20.5f, 6f)
                lineTo(20.5f, 18f)
                curveTo(18.5f, 17.2f, 15.5f, 16.8f, 12f, 18.5f)
            }
            // Center spine binding
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 6.5f)
                lineTo(12f, 19.5f)
            }
        }.build()
    }

    /**
     * Slender Film Frame: rounded rectangle with geometric play triangle.
     */
    val CinemaVideo: ImageVector by lazy {
        ImageVector.Builder(
            name = "CinemaVideo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Slender film frame
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.5f, 5f)
                lineTo(17.5f, 5f)
                curveTo(19.16f, 5f, 20.5f, 6.34f, 20.5f, 8f)
                lineTo(20.5f, 16f)
                curveTo(20.5f, 17.66f, 19.16f, 19f, 17.5f, 19f)
                lineTo(6.5f, 19f)
                curveTo(4.84f, 19f, 3.5f, 17.66f, 3.5f, 16f)
                lineTo(3.5f, 8f)
                curveTo(3.5f, 6.34f, 4.84f, 5f, 6.5f, 5f)
                close()
            }
            // Centered geometric play triangle
            path(
                fill = DefaultColor,
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 9f)
                lineTo(15.5f, 12f)
                lineTo(10f, 15f)
                close()
            }
        }.build()
    }

    /**
     * Editorial Loupe: minimal circle with 45-degree handle.
     */
    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "Search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Loupe lens circle (center: 10.5, 10.5, radius: 6)
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10.5f, 4.5f)
                curveTo(13.81f, 4.5f, 16.5f, 7.19f, 16.5f, 10.5f)
                curveTo(16.5f, 13.81f, 13.81f, 16.5f, 10.5f, 16.5f)
                curveTo(7.19f, 16.5f, 4.5f, 13.81f, 4.5f, 10.5f)
                curveTo(4.5f, 7.19f, 7.19f, 4.5f, 10.5f, 4.5f)
                close()
            }
            // 45-degree handle
            path(
                stroke = DefaultColor,
                strokeLineWidth = StrokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(14.74f, 14.74f)
                lineTo(19.5f, 19.5f)
            }
        }.build()
    }
}
