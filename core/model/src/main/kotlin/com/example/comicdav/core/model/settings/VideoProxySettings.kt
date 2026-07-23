package com.example.comicdav.core.model.settings

enum class VideoForwardPrefetchMode {
    OFF,
    STANDARD,
    AGGRESSIVE,
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
