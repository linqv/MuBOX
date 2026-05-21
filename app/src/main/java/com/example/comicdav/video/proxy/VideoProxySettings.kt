package com.example.comicdav.video.proxy

enum class VideoForwardPrefetchMode(val segmentCount: Int) {
    OFF(0),
    STANDARD(1),
    AGGRESSIVE(2),
}

enum class VideoProxyDiagnosticsMode {
    OFF,
    SUMMARY,
    DETAIL,
}

data class VideoProxySettings(
    val seekOptimizationEnabled: Boolean = true,
    val forwardPrefetchMode: VideoForwardPrefetchMode = VideoForwardPrefetchMode.STANDARD,
    val diagnosticsMode: VideoProxyDiagnosticsMode = VideoProxyDiagnosticsMode.OFF,
) {
    companion object {
        val DEFAULT = VideoProxySettings()
    }
}
