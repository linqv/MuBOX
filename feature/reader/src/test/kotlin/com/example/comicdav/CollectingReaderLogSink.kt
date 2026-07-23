package com.example.comicdav

import com.example.comicdav.core.diagnostics.DiagnosticSink

class CollectingReaderLogSink : DiagnosticSink {
    val lines = mutableListOf<String>()

    override fun log(line: String) {
        lines += line
    }

    override fun logBlocking(line: String) {
        lines += line
    }
}
