package com.example.comicdav

import com.example.comicdav.feature.reader.ReaderLogSink

class CollectingReaderLogSink : ReaderLogSink {
    val lines = mutableListOf<String>()

    override fun log(line: String) {
        lines += line
    }

    override fun logBlocking(line: String) {
        lines += line
    }
}
