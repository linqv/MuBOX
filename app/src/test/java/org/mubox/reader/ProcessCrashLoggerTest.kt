package org.mubox.reader

import org.mubox.reader.core.diagnostics.ExceptionDiagnostics
import org.mubox.reader.core.diagnostics.DiagnosticSeverity
import org.mubox.reader.core.diagnostics.DiagnosticSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessCrashLoggerTest {
    @Test
    fun fatalCrashIsFlushedBeforeDelegatingToSystemHandler() {
        val calls = mutableListOf<String>()
        val sink = object : DiagnosticSink {
            override fun log(severity: DiagnosticSeverity, line: String) {
                calls += "async:$severity"
            }

            override fun logBlocking(severity: DiagnosticSeverity, line: String) {
                calls += "blocking:$severity:$line"
            }
        }
        var delegatedThread: Thread? = null
        var delegatedError: Throwable? = null
        val delegate = Thread.UncaughtExceptionHandler { thread, error ->
            calls += "delegate"
            delegatedThread = thread
            delegatedError = error
        }
        val logger = ProcessCrashLogger(ExceptionDiagnostics(sink), delegate)
        val thread = Thread.currentThread()
        val crash = IllegalStateException("fatal state")

        logger.uncaughtException(thread, crash)

        assertTrue(calls.first().startsWith("blocking:FATAL:"))
        assertEquals("delegate", calls.last())
        assertSame(thread, delegatedThread)
        assertSame(crash, delegatedError)
    }
}
