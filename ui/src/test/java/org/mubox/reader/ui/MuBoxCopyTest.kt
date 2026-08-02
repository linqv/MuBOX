package org.mubox.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MuBoxCopyTest {
    @Test
    fun primaryNavigationUsesChineseReaderTerms() {
        assertEquals("来源", MuBoxCopy.sourcesTab)
        assertEquals("书架", MuBoxCopy.libraryTab)
        assertEquals("设置", MuBoxCopy.settingsTab)
    }

    @Test
    fun primaryActionsUseChineseCopy() {
        assertEquals("添加本地文件夹", MuBoxCopy.addLocalFolder)
        assertEquals("添加 WebDAV", MuBoxCopy.addWebDav)
        assertEquals("阅读", MuBoxCopy.read)
        assertEquals("加入书架", MuBoxCopy.addToLibrary)
        assertEquals("保存当前目录", MuBoxCopy.saveCurrentDirectory)
    }

    @Test
    fun firstRunDataFolderCopyUsesMuBoxBrand() {
        assertEquals("选择 MuBOX 数据文件夹", MuBoxCopy.chooseDataFolderTitle)
        assertEquals(
            "MuBOX 会把封面、离线漫画和后续导出的文件保存在你选择的文件夹中。",
            MuBoxCopy.chooseDataFolderBody,
        )
    }
}
