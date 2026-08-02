package com.example.comicdav.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicDavCopyTest {
    @Test
    fun primaryNavigationUsesChineseReaderTerms() {
        assertEquals("来源", ComicDavCopy.sourcesTab)
        assertEquals("书架", ComicDavCopy.libraryTab)
        assertEquals("设置", ComicDavCopy.settingsTab)
    }

    @Test
    fun primaryActionsUseChineseCopy() {
        assertEquals("添加本地文件夹", ComicDavCopy.addLocalFolder)
        assertEquals("添加 WebDAV", ComicDavCopy.addWebDav)
        assertEquals("阅读", ComicDavCopy.read)
        assertEquals("加入书架", ComicDavCopy.addToLibrary)
        assertEquals("保存当前目录", ComicDavCopy.saveCurrentDirectory)
    }

    @Test
    fun firstRunDataFolderCopyUsesMuBoxBrand() {
        assertEquals("选择 MuBOX 数据文件夹", ComicDavCopy.chooseDataFolderTitle)
        assertEquals(
            "MuBOX 会把封面、离线漫画和后续导出的文件保存在你选择的文件夹中。",
            ComicDavCopy.chooseDataFolderBody,
        )
    }
}
