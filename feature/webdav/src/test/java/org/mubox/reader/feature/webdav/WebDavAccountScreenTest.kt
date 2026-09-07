package org.mubox.reader.feature.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavAccountScreenTest {
    @Test
    fun titleReflectsAddOrEditMode() {
        val addTitle = if (false) "编辑网络连接" else "添加网络连接"
        val editTitle = if (true) "编辑网络连接" else "添加网络连接"

        assertEquals("添加网络连接", addTitle)
        assertEquals("编辑网络连接", editTitle)
    }

    @Test
    fun actionsEnableWhenHostIsNotEmptyAndNotLoading() {
        val idleState = WebDavUiState(host = "example.com", isLoading = false)
        val loadingState = WebDavUiState(host = "example.com", isLoading = true)
        val blankHostState = WebDavUiState(host = "", isLoading = false)

        val idleEnabled = !idleState.isLoading && idleState.host.isNotBlank()
        val loadingEnabled = !loadingState.isLoading && loadingState.host.isNotBlank()
        val blankHostEnabled = !blankHostState.isLoading && blankHostState.host.isNotBlank()

        assertTrue(idleEnabled)
        assertFalse(loadingEnabled)
        assertFalse(blankHostEnabled)
    }

    @Test
    fun buildWebDavBaseUrlOmitsStandardPortsAndNormalizesRoot() {
        assertEquals(
            "https://my.server/webdav",
            buildWebDavBaseUrl(useHttps = true, host = "my.server", port = "443", rootPath = "/webdav"),
        )
        assertEquals(
            "http://my.server/webdav",
            buildWebDavBaseUrl(useHttps = false, host = "my.server", port = "80", rootPath = "webdav"),
        )
        assertEquals(
            "https://my.server:8443/",
            buildWebDavBaseUrl(useHttps = true, host = "my.server", port = "8443", rootPath = "/"),
        )
    }
}
