package com.example.comicdav.feature.settings

import com.example.comicdav.data.AppColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenUiTest {
    @Test
    fun defaultPaletteLabelMatchesAdwaitaTheme() {
        assertEquals("Adwaita 深色（默认）", AppColorPalette.DEFAULT.settingsLabel())
    }

    @Test
    fun settingsRowsExposeStableControlPanelMetrics() {
        assertEquals(64, settingsControlRowMinHeightDp())
    }

    @Test
    fun rootSettingsLayoutKeepsOnlyCommonAndManagementGroups() {
        val layout = rootSettingsGroupLayout()

        assertEquals(
            listOf("通用", "内容设置", "缓存"),
            layout.map { it.title },
        )
    }

    @Test
    fun rootSettingsLayoutLinksToComicAndVideoSettings() {
        val contentRows = rootSettingsGroupLayout().rowsInGroup("内容设置")

        assertEquals(listOf("漫画设置", "视频设置"), contentRows)
    }

    @Test
    fun rootSettingsLayoutDoesNotExposeMediaSpecificRows() {
        val rootRows = rootSettingsGroupLayout().flatMap { it.rows }

        assertFalse(rootRows.contains("阅读方向"))
        assertFalse(rootRows.contains("恢复播放位置"))
        assertFalse(rootRows.contains("MPV Profile"))
        assertTrue(rootRows.contains("配色方案"))
        assertTrue(rootRows.contains("屏幕旋转锁定"))
    }

    @Test
    fun rootSettingsLayoutExposesPageImageCacheToggleBeforeLimit() {
        val cacheRows = rootSettingsGroupLayout().rowsInGroup("缓存")

        assertTrue(cacheRows.indexOf("页面图片缓存") < cacheRows.indexOf("页面图片缓存上限"))
        assertTrue(cacheRows.indexOf("页面图片缓存上限") < cacheRows.indexOf("页面图片缓存占用"))
    }

    @Test
    fun diskCacheLimitOptionsStartAtFiveHundredMb() {
        assertEquals(500, coerceDiskCacheLimitMb(0))
        assertEquals(500, coerceDiskCacheLimitMb(500))
        assertEquals(1024, coerceDiskCacheLimitMb(1024))
        assertEquals(5120, coerceDiskCacheLimitMb(9000))
        assertEquals("500 MB", diskCacheLimitLabel(0))
    }

    @Test
    fun pageCacheLimitUsesZeroBytesWhenPageImageCacheDisabled() {
        assertEquals(0L, pageCacheLimitBytesForSettings(pageImageCacheEnabled = false, limitMb = 1024))
        assertEquals(1_073_741_824L, pageCacheLimitBytesForSettings(pageImageCacheEnabled = true, limitMb = 1024))
    }

    @Test
    fun comicSettingsLayoutContainsComicSpecificSettings() {
        val comicRows = comicSettingsGroupLayout().rowsInGroup("漫画设置")

        assertEquals(
            listOf(
                "阅读方向",
                "音量键翻页",
                "双指缩放",
                "WebDAV 预取页数",
                "诊断日志",
                "AVIF 图片",
                "书架封面",
                "启用自动翻页",
                "翻页速度",
            ),
            comicRows,
        )
    }

    @Test
    fun videoSettingsLayoutContainsVideoSpecificSettings() {
        val videoRows = videoSettingsGroupLayout().rowsInGroup("视频设置")

        assertEquals(
            listOf(
                "恢复播放位置",
                "后台行为",
                "WebDAV 视频 seek 优化",
                "向前预读",
                "视频代理诊断日志",
                "播放信息显示代理/Range 调试信息",
                "视频输出 (VO)",
                "GPU API",
                "Anime4K",
                "Anime4K 预设",
                "Anime4K 质量",
                "默认解码器",
                "MPV Profile",
                "控制自动隐藏",
                "播放器方向",
                "提取加入影视库的视频缩略图作为封面",
            ),
            videoRows,
        )
    }

    private fun List<SettingsGroupLayout>.rowsInGroup(title: String): List<String> =
        single { it.title == title }.rows
}
