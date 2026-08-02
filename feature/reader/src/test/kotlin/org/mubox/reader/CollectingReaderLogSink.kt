package org.mubox.reader

import org.mubox.reader.core.diagnostics.DiagnosticSink
import org.mubox.reader.core.diagnostics.DiagnosticSeverity

class CollectingReaderLogSink : DiagnosticSink {
    val lines = mutableListOf<String>()

    override fun log(severity: DiagnosticSeverity, line: String) {
        lines += line
    }

    override fun logBlocking(severity: DiagnosticSeverity, line: String) {
        lines += line
    }
}
