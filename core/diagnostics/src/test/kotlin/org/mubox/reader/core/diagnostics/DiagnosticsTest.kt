package org.mubox.reader.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun handledExceptionIncludesSeverityCategoryThreadAndStackTrace() {
        val records = mutableListOf<Record>()
        val diagnostics = ExceptionDiagnostics(recordingSink(records))

        diagnostics.error(
            DiagnosticCategory.WEBDAV_NETWORK,
            "request_failed path=/private/book.cbz",
            IllegalStateException("bad state"),
        )

        val record = records.single()
        assertEquals(DiagnosticSeverity.ERROR, record.severity)
        assertFalse(record.blocking)
        assertTrue(record.line.contains("level=error category=WEBDAV_NETWORK thread="))
        assertTrue(record.line.contains("exception=java.lang.IllegalStateException"))
        assertTrue(record.line.contains("IllegalStateException: bad state"))
        assertFalse(record.line.contains("/private/book.cbz"))
    }

    @Test
    fun fatalCrashUsesBlockingSink() {
        val records = mutableListOf<Record>()
        val diagnostics = ExceptionDiagnostics(recordingSink(records))

        diagnostics.fatalBlocking("uncaught_exception", AssertionError("boom"))

        assertEquals(DiagnosticSeverity.FATAL, records.single().severity)
        assertTrue(records.single().blocking)
        assertTrue(records.single().line.contains("level=fatal"))
    }

    @Test
    fun disabledRecorderDropsHandledExceptionsAndFatalCrashes() {
        val records = mutableListOf<Record>()
        val diagnostics = ExceptionDiagnostics(recordingSink(records))

        diagnostics.setEnabled(false)
        diagnostics.error("handled_exception", IllegalStateException("handled"))
        diagnostics.fatalBlocking("uncaught_exception", AssertionError("fatal"))

        assertTrue(records.isEmpty())

        diagnostics.setEnabled(true)
        diagnostics.error("logging_restored")
        assertEquals(1, records.size)
    }

    @Test
    fun sensitiveResourceAndCredentialValuesAreRedacted() {
        val redacted = redactDiagnosticText(
            "uri=content://private/book.cbz path=/secret/book.cbz fileName=book.cbz " +
                "url=https://user:pass@example.com/private/file.cbz?signature=secret token=secret Authorization=BearerSecret " +
                "message=/another/private/movie.mkv " +
                "header=Bearer abc.def",
        )

        assertFalse(redacted.contains("content://private"))
        assertFalse(redacted.contains("/secret/book.cbz"))
        assertFalse(redacted.contains("fileName=book.cbz"))
        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("/private/file.cbz"))
        assertFalse(redacted.contains("/another/private/movie.mkv"))
        assertFalse(redacted.contains("token=secret"))
        assertFalse(redacted.contains("Bearer abc.def"))
        assertTrue(redacted.contains("uriId=local:"))
        assertTrue(redacted.contains("pathId=path:"))
        assertTrue(redacted.contains("fileId=file:"))
        assertTrue(redacted.contains("fileExt=cbz"))
    }

    @Test
    fun sinkFailureDoesNotEscapeExceptionRecorder() {
        val diagnostics = ExceptionDiagnostics(
            object : DiagnosticSink {
                override fun log(severity: DiagnosticSeverity, line: String) {
                    error("sink failed")
                }
            },
        )

        diagnostics.error("operation_failed", IllegalArgumentException("bad input"))
    }

    private fun recordingSink(records: MutableList<Record>) = object : DiagnosticSink {
        override fun log(severity: DiagnosticSeverity, line: String) {
            records += Record(severity, line, blocking = false)
        }

        override fun logBlocking(severity: DiagnosticSeverity, line: String) {
            records += Record(severity, line, blocking = true)
        }
    }

    private data class Record(
        val severity: DiagnosticSeverity,
        val line: String,
        val blocking: Boolean,
    )
}
