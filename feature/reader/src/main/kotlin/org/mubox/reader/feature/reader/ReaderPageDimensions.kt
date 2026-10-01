package org.mubox.reader.feature.reader

import android.graphics.BitmapFactory
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ReaderPageDimensions(
    val fileVersion: ReaderPageFileVersion,
    val size: IntSize?,
)

internal data class ReaderPageFileVersion(
    val path: String,
    val length: Long,
    val modifiedAt: Long,
) {
    val memoryCacheKey: String
        get() = "reader:$path:$length:$modifiedAt"
}

internal fun readerPageFileVersion(pageFile: File): ReaderPageFileVersion = ReaderPageFileVersion(
    path = pageFile.absolutePath,
    length = pageFile.length(),
    modifiedAt = pageFile.lastModified(),
)

internal fun readerImageAspectRatioDiffers(first: IntSize, second: IntSize): Boolean {
    if (first.width <= 0 || first.height <= 0 || second.width <= 0 || second.height <= 0) return false
    val firstRatio = first.width.toDouble() / first.height
    val secondRatio = second.width.toDouble() / second.height
    return abs(firstRatio / secondRatio - 1.0) > 0.01
}

/** Reads image bounds without allocating a bitmap. Call from an IO dispatcher. */
internal fun readReaderPageDimensions(pageFile: File): IntSize? {
    if (!pageFile.isFile) return null
    return runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(pageFile.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            IntSize(options.outWidth, options.outHeight)
        } else {
            null
        }
    }.getOrNull()
}

internal fun readerContinuousPageHeightPx(
    viewportSize: IntSize,
    imageSize: IntSize?,
    landscapeScaleMode: ReaderLandscapeScaleMode,
): Int? {
    val image = imageSize ?: return null
    if (viewportSize.width <= 0 || image.width <= 0 || image.height <= 0) return null
    return when (
        readerPageScalePolicy(
            fillWidth = true,
            viewportSize = viewportSize,
            imageSize = image,
            landscapeScaleMode = landscapeScaleMode,
        )
    ) {
        ReaderPageScalePolicy.FitViewport -> viewportSize.height.takeIf { it > 0 }
        ReaderPageScalePolicy.FillWidth ->
            (viewportSize.width.toDouble() * image.height / image.width)
                .roundToInt()
                .coerceAtLeast(1)
    }
}
