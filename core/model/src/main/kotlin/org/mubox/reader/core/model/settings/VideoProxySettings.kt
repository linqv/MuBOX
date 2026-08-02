package org.mubox.reader.core.model.settings

enum class VideoForwardPrefetchMode {
    OFF,
    STANDARD,
    AGGRESSIVE,
}

data class VideoProxySettings(
    val seekOptimizationEnabled: Boolean = true,
    val forwardPrefetchMode: VideoForwardPrefetchMode = VideoForwardPrefetchMode.STANDARD,
) {
    companion object {
        val DEFAULT = VideoProxySettings()
    }
}
