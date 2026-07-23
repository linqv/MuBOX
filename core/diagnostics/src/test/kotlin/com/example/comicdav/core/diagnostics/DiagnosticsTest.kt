package com.example.comicdav.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun verbosityFiltersDetailButKeepsSummary() {
        val lines = mutableListOf<String>()
        val diagnostics = ConfigurableDiagnostics(
            defaultSink = collectingSink(lines),
            initialVerbosity = DiagnosticVerbosity.SUMMARY,
        )

        diagnostics.detail(DiagnosticCategory.PAGE_LOAD) { "detail=true" }
        diagnostics.summary(DiagnosticCategory.SESSION) { "opened=true" }

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("category=SESSION opened=true"))
    }

    @Test
    fun sensitiveResourceValuesAreReplacedWithStableIds() {
        val redacted = redactDiagnosticText(
            "uri=content://private/book.cbz path=/secret/book.cbz fileName=book.cbz status=ready",
        )

        assertFalse(redacted.contains("content://private"))
        assertFalse(redacted.contains("/secret/book.cbz"))
        assertFalse(redacted.contains("fileName=book.cbz"))
        assertTrue(redacted.contains("uriId=local:"))
        assertTrue(redacted.contains("pathId=path:"))
        assertTrue(redacted.contains("fileId=file:"))
        assertTrue(redacted.contains("fileExt=cbz"))
    }

    private fun collectingSink(lines: MutableList<String>) = object : DiagnosticSink {
        override fun log(line: String) {
            lines += line
        }

        override fun logBlocking(line: String) {
            lines += line
        }
    }
}
