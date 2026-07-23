package com.example.comicdav.video.proxy

import com.example.comicdav.core.model.settings.VideoForwardPrefetchMode

internal val VideoForwardPrefetchMode.segmentCount: Int
    get() = when (this) {
        VideoForwardPrefetchMode.OFF -> 0
        VideoForwardPrefetchMode.STANDARD -> 1
        VideoForwardPrefetchMode.AGGRESSIVE -> 2
    }
