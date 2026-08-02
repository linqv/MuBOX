package org.mubox.reader.video.proxy

import org.mubox.reader.core.model.settings.VideoForwardPrefetchMode

internal val VideoForwardPrefetchMode.segmentCount: Int
    get() = when (this) {
        VideoForwardPrefetchMode.OFF -> 0
        VideoForwardPrefetchMode.STANDARD -> 1
        VideoForwardPrefetchMode.AGGRESSIVE -> 2
    }
