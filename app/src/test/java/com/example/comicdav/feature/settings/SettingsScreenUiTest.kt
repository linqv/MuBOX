package com.example.comicdav.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenUiTest {
    @Test
    fun settingsLayoutUsesCommonComicAndVideoGroups() {
        val layout = settingsGroupLayout()

        assertEquals(
            listOf("显示", "漫画", "视频", "自动翻页", "下载记录", "缓存"),
            layout.map { it.title },
        )
    }

    @Test
    fun videoRowsContainVideoSpecificSettings() {
        val videoRows = settingsGroupLayout().rowsInGroup("视频")

        assertTrue(videoRows.contains("播放器方向"))
        assertTrue(videoRows.contains("提取加入影视库的视频缩略图作为封面"))
    }

    @Test
    fun comicRowsContainComicSpecificSettings() {
        val comicRows = settingsGroupLayout().rowsInGroup("漫画")

        assertTrue(comicRows.contains("阅读方向"))
        assertTrue(comicRows.contains("书架封面"))
    }

    @Test
    fun displayRowsKeepCommonColorScheme() {
        val displayRows = settingsGroupLayout().rowsInGroup("显示")

        assertTrue(displayRows.contains("配色方案"))
    }

    private fun List<SettingsGroupLayout>.rowsInGroup(title: String): List<String> =
        single { it.title == title }.rows
}
