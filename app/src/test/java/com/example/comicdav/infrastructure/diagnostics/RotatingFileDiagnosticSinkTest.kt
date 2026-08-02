package com.example.comicdav.infrastructure.diagnostics

import com.example.comicdav.core.diagnostics.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RotatingFileDiagnosticSinkTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exceptionLinesSurviveAcrossSinkInstances() {
        val file = temp.root.resolve("diagnostics/exceptions.log")

        RotatingFileDiagnosticSink(file).log(DiagnosticSeverity.ERROR, "first exception")
        RotatingFileDiagnosticSink(file).logBlocking(DiagnosticSeverity.FATAL, "fatal crash")

        assertEquals(listOf("first exception", "fatal crash"), file.readLines())
    }

    @Test
    fun fullLogIsRotatedBeforeWritingNextException() {
        val file = temp.root.resolve("diagnostics/exceptions.log")
        val sink = RotatingFileDiagnosticSink(file, maxBytes = 20L)

        sink.log(DiagnosticSeverity.ERROR, "123456789012345")
        sink.log(DiagnosticSeverity.ERROR, "next")

        val previous = requireNotNull(file.parentFile).resolve("exceptions.previous.log")
        assertTrue(previous.readText().contains("123456789012345"))
        assertTrue(file.readText().contains("next"))
        assertFalse(file.readText().contains("123456789012345"))
    }

    @Test
    fun failedPreviousDeletionReplacesActiveLogAndReportsFailure() {
        val file = temp.root.resolve("diagnostics/exceptions.log")
        val previous = requireNotNull(file.parentFile).resolve("exceptions.previous.log")
        previous.mkdirs()
        previous.resolve("undeletable-child").writeText("keep directory non-empty")
        val failures = mutableListOf<String>()
        val sink = RotatingFileDiagnosticSink(
            file = file,
            maxBytes = 20L,
            failureReporter = { event, _ -> failures += event },
        )

        sink.log(DiagnosticSeverity.ERROR, "123456789012345")
        sink.log(DiagnosticSeverity.ERROR, "latest")

        assertEquals(listOf("latest"), file.readLines())
        assertTrue(file.length() <= 20L)
        assertEquals(listOf("exception_log_rotation_delete_failed"), failures)
    }

    @Test
    fun failedMoveReplacesActiveLogAndReportsFailure() {
        val file = temp.root.resolve("diagnostics/exceptions.log")
        val failures = mutableListOf<String>()
        val sink = RotatingFileDiagnosticSink(
            file = file,
            maxBytes = 20L,
            failureReporter = { event, _ -> failures += event },
            moveFile = { _, _ -> false },
        )

        sink.log(DiagnosticSeverity.ERROR, "123456789012345")
        sink.log(DiagnosticSeverity.ERROR, "latest")

        assertEquals(listOf("latest"), file.readLines())
        assertTrue(file.length() <= 20L)
        assertEquals(listOf("exception_log_rotation_move_failed"), failures)
    }

    @Test
    fun singleOversizedExceptionIsTruncatedToLimit() {
        val file = temp.root.resolve("diagnostics/exceptions.log")
        val sink = RotatingFileDiagnosticSink(file, maxBytes = 32L)

        sink.log(DiagnosticSeverity.ERROR, "异常".repeat(100))

        assertTrue(file.length() <= 32L)
        assertTrue(file.readText().endsWith("<truncated>\n"))
    }
}
