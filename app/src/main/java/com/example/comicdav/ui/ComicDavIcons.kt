package com.example.comicdav.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ComicDavIcons {
    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "ComicDavFolder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 6f)
                lineTo(9f, 6f)
                lineTo(11f, 8f)
                lineTo(21f, 8f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
            }
        }.build()
    }

    val Archive: ImageVector by lazy {
        ImageVector.Builder(
            name = "ComicDavArchive",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 3f)
                lineTo(15f, 3f)
                lineTo(19f, 7f)
                lineTo(19f, 21f)
                lineTo(6f, 21f)
                close()
                moveTo(15f, 3f)
                lineTo(15f, 8f)
                lineTo(19f, 8f)
                lineTo(15f, 3f)
                close()
                moveTo(9f, 11f)
                lineTo(16f, 11f)
                lineTo(16f, 13f)
                lineTo(9f, 13f)
                close()
                moveTo(9f, 15f)
                lineTo(14f, 15f)
                lineTo(14f, 17f)
                lineTo(9f, 17f)
                close()
            }
        }.build()
    }
}
