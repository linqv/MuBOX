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
            path(fill = SolidColor(Color(0x22000000))) {
                moveTo(4.2f, 20.8f)
                lineTo(20.4f, 20.8f)
                lineTo(19.2f, 22.2f)
                lineTo(5.4f, 22.2f)
                close()
            }
            path(fill = SolidColor(Color(0xFF3F8BEE))) {
                moveTo(2.2f, 4.2f)
                lineTo(8.8f, 4.2f)
                lineTo(11.1f, 6.3f)
                lineTo(21.8f, 6.3f)
                lineTo(21.8f, 11.7f)
                lineTo(2.2f, 11.7f)
                close()
            }
            path(fill = SolidColor(Color(0xFF9ECBF5))) {
                moveTo(2.4f, 7.8f)
                lineTo(22f, 7.8f)
                lineTo(22f, 20.8f)
                lineTo(2.4f, 20.8f)
                close()
            }
            path(fill = SolidColor(Color(0xFF7DB7F1))) {
                moveTo(2.4f, 7.8f)
                lineTo(22f, 7.8f)
                lineTo(22f, 10.4f)
                lineTo(2.4f, 10.4f)
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
            path(fill = SolidColor(Color(0x22000000))) {
                moveTo(5.7f, 3.5f)
                lineTo(15.5f, 3.5f)
                lineTo(20.2f, 8.2f)
                lineTo(20.2f, 21.4f)
                lineTo(5.7f, 21.4f)
                close()
            }
            path(fill = SolidColor(Color(0xFFFFFFFF))) {
                moveTo(4f, 2.2f)
                lineTo(14.8f, 2.2f)
                lineTo(19.8f, 7.2f)
                lineTo(19.8f, 20.9f)
                lineTo(4f, 20.9f)
                close()
            }
            path(fill = SolidColor(Color(0xFFE9ECEF))) {
                moveTo(14.8f, 2.2f)
                lineTo(19.8f, 7.2f)
                lineTo(14.8f, 7.2f)
                close()
            }
            path(fill = SolidColor(Color(0xFFD2D6DA))) {
                moveTo(14.8f, 2.2f)
                lineTo(14.8f, 7.2f)
                lineTo(19.8f, 7.2f)
                lineTo(19.8f, 8f)
                lineTo(14f, 8f)
                lineTo(14f, 2.2f)
                close()
            }
            path(fill = SolidColor(Color(0xFFD8DEE3))) {
                moveTo(6.4f, 6.3f)
                lineTo(12.3f, 6.3f)
                lineTo(12.3f, 7.1f)
                lineTo(6.4f, 7.1f)
                close()
                moveTo(6.4f, 8.9f)
                lineTo(17.2f, 8.9f)
                lineTo(17.2f, 9.7f)
                lineTo(6.4f, 9.7f)
                close()
                moveTo(14.1f, 11.5f)
                lineTo(17.2f, 11.5f)
                lineTo(17.2f, 12.3f)
                lineTo(14.1f, 12.3f)
                close()
                moveTo(14.1f, 13.8f)
                lineTo(17.2f, 13.8f)
                lineTo(17.2f, 14.6f)
                lineTo(14.1f, 14.6f)
                close()
                moveTo(14.1f, 16.1f)
                lineTo(17.2f, 16.1f)
                lineTo(17.2f, 16.9f)
                lineTo(14.1f, 16.9f)
                close()
            }
            path(fill = SolidColor(Color(0xFF6AE7A8))) {
                moveTo(6.3f, 10.8f)
                lineTo(13.1f, 10.8f)
                lineTo(13.1f, 18.8f)
                lineTo(6.3f, 18.8f)
                close()
            }
            path(fill = SolidColor(Color(0xFF49D8C8))) {
                moveTo(6.3f, 13.3f)
                lineTo(13.1f, 13.3f)
                lineTo(13.1f, 18.8f)
                lineTo(6.3f, 18.8f)
                close()
            }
            path(fill = SolidColor(Color(0xFFAFA4FF))) {
                moveTo(6.3f, 15.6f)
                lineTo(13.1f, 15.6f)
                lineTo(13.1f, 18.8f)
                lineTo(6.3f, 18.8f)
                close()
            }
            path(fill = SolidColor(Color(0xFFE4E8EC))) {
                moveTo(4f, 2.2f)
                lineTo(14.8f, 2.2f)
                lineTo(14.8f, 2.9f)
                lineTo(4.7f, 2.9f)
                lineTo(4.7f, 20.9f)
                lineTo(4f, 20.9f)
                close()
            }
        }.build()
    }
}
