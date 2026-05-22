package com.example.comicdav.video.player

import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerOptionPanelUiTest {
    @Test
    fun sideRailPanelsUseIconsAndAccessibleLabelsInsteadOfVisibleText() {
        val descriptors = PlayerOptionPanel.entries.map { it.sideRailDescriptor() }

        assertEquals(
            listOf(
                "倍速",
                "音轨与字幕",
                "音画同步",
                "画面模式",
                "播放信息",
                "播放队列",
            ),
            descriptors.map { it.contentDescription },
        )
        assertEquals(
            listOf(
                Icons.Filled.Speed,
                Icons.Filled.Subtitles,
                Icons.Filled.Sync,
                Icons.Filled.AspectRatio,
                Icons.Filled.Info,
                Icons.AutoMirrored.Filled.QueueMusic,
            ),
            descriptors.map { it.icon },
        )
        assertFalse(descriptors.any { it.visibleText.isNotBlank() })
    }
}
