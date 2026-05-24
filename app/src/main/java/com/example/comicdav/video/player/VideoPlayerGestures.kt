package com.example.comicdav.video.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PlayerGestureOverlay(
    onVolumeDelta: (Int) -> Unit,
    onBrightnessDelta: (Int) -> Unit,
    onDoubleTapSeek: (Boolean) -> Unit,
    onHorizontalSeekStarted: () -> Unit,
    onHorizontalSeekFraction: (Float) -> Unit,
    onHorizontalSeekEnded: () -> Unit,
    onZoomDelta: (Float) -> Unit,
    onTemporarySpeedStarted: () -> Unit,
    onTemporarySpeedDelta: (Double) -> Unit,
    onTemporarySpeedEnded: () -> Unit,
    onClearHud: () -> Unit,
    onOverlayTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var temporarySpeedActive by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .padding(
                start = PLAYER_GESTURE_HORIZONTAL_PADDING_DP.dp,
                top = PLAYER_GESTURE_TOP_PADDING_DP.dp,
                end = PLAYER_GESTURE_END_PADDING_DP.dp,
                bottom = PLAYER_GESTURE_BOTTOM_PADDING_DP.dp,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        onDoubleTapSeek(offset.x >= size.width / 2f)
                    },
                    onTap = {
                        onClearHud()
                        onOverlayTap()
                    },
                )
            }
            .pointerInput(temporarySpeedActive) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPosition = down.position
                    var previousPosition = startPosition
                    var dragMode: PlayerGestureDragMode? = null

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (temporarySpeedActive || pressed.size != 1) {
                            if (dragMode == PlayerGestureDragMode.HORIZONTAL_SEEK) {
                                onHorizontalSeekEnded()
                            }
                            break
                        }

                        val change = pressed.first()
                        val currentPosition = change.position
                        val totalPan = currentPosition - startPosition

                        if (dragMode == null) {
                            dragMode = playerGestureDragModeForPan(totalPan.x, totalPan.y)
                            if (dragMode == PlayerGestureDragMode.HORIZONTAL_SEEK) {
                                onHorizontalSeekStarted()
                            }
                        }

                        when (dragMode) {
                            PlayerGestureDragMode.HORIZONTAL_SEEK -> {
                                val framePanX = currentPosition.x - previousPosition.x
                                val seekFraction = horizontalSeekFractionForPan(framePanX, size.width)
                                if (seekFraction != 0f) {
                                    change.consume()
                                    onHorizontalSeekFraction(seekFraction)
                                }
                            }
                            PlayerGestureDragMode.VERTICAL_ADJUST -> {
                                val framePanY = currentPosition.y - previousPosition.y
                                dispatchVerticalGesture(
                                    centroid = currentPosition,
                                    containerWidth = size.width.toFloat(),
                                    panY = framePanY,
                                    onBrightnessDelta = onBrightnessDelta,
                                    onVolumeDelta = onVolumeDelta,
                                )
                                change.consume()
                            }
                            null -> Unit
                        }

                        previousPosition = currentPosition
                    } while (event.changes.any { it.pressed })

                    if (dragMode == PlayerGestureDragMode.HORIZONTAL_SEEK) {
                        onHorizontalSeekEnded()
                    }
                }
            }
            .pointerInput(temporarySpeedActive) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var previousPinchDistance: Float? = null

                    do {
                        val event = awaitPointerEvent()
                        if (temporarySpeedActive) break
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size == 2) {
                            val distance = (pressed[1].position - pressed[0].position).getDistance()
                            val previousDistance = previousPinchDistance
                            if (previousDistance != null && previousDistance > 0f && distance > 0f) {
                                val zoom = distance / previousDistance
                                if (zoom != 1f) {
                                    onZoomDelta((zoom - 1f) * PINCH_ZOOM_STEP_SCALE)
                                    pressed.forEach { it.consume() }
                                }
                            }
                            previousPinchDistance = distance
                        } else if (previousPinchDistance != null) {
                            break
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                var pendingVerticalDragPx = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        pendingVerticalDragPx = 0f
                        temporarySpeedActive = true
                        onTemporarySpeedStarted()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        pendingVerticalDragPx += dragAmount.y
                        val steps = (pendingVerticalDragPx / TEMPORARY_SPEED_PIXELS_PER_STEP).toInt()
                        if (steps != 0) {
                            pendingVerticalDragPx -= steps * TEMPORARY_SPEED_PIXELS_PER_STEP
                            onTemporarySpeedDelta(-steps * TEMPORARY_SPEED_STEP)
                        }
                    },
                    onDragEnd = {
                        temporarySpeedActive = false
                        onTemporarySpeedEnded()
                    },
                    onDragCancel = {
                        temporarySpeedActive = false
                        onTemporarySpeedEnded()
                    },
                )
            },
    )
}

@Composable
internal fun LockedPlayerGestureOverlay(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { onTap() })
        },
    )
}

internal fun horizontalSeekFractionForPan(panX: Float, widthPx: Int): Float {
    if (widthPx <= 0 || abs(panX) < HORIZONTAL_GESTURE_MIN_PAN_PX) return 0f
    return panX / widthPx
}

internal fun shouldDispatchVerticalPlayerPan(panX: Float, panY: Float): Boolean =
    playerGestureDragModeForPan(panX, panY) == PlayerGestureDragMode.VERTICAL_ADJUST

internal enum class PlayerGestureDragMode {
    HORIZONTAL_SEEK,
    VERTICAL_ADJUST,
}

internal fun playerGestureDragModeForPan(panX: Float, panY: Float): PlayerGestureDragMode? {
    val absX = abs(panX)
    val absY = abs(panY)
    if (absX < PLAYER_GESTURE_DRAG_DIRECTION_THRESHOLD_PX &&
        absY < PLAYER_GESTURE_DRAG_DIRECTION_THRESHOLD_PX
    ) {
        return null
    }
    return when {
        absX > absY -> PlayerGestureDragMode.HORIZONTAL_SEEK
        absY > absX -> PlayerGestureDragMode.VERTICAL_ADJUST
        else -> null
    }
}

internal fun dispatchVerticalGesture(
    centroid: Offset,
    containerWidth: Float,
    panY: Float,
    onBrightnessDelta: (Int) -> Unit,
    onVolumeDelta: (Int) -> Unit,
) {
    val deltaPercent = (-panY / VERTICAL_GESTURE_PIXELS_PER_PERCENT).roundToInt()
    if (deltaPercent == 0) return
    if (centroid.x < containerWidth / 2f) {
        onBrightnessDelta(deltaPercent)
    } else {
        onVolumeDelta(deltaPercent)
    }
}

private const val VERTICAL_GESTURE_PIXELS_PER_PERCENT = 8f
private const val HORIZONTAL_GESTURE_MIN_PAN_PX = 1f
private const val PLAYER_GESTURE_DRAG_DIRECTION_THRESHOLD_PX = 12f
private const val TEMPORARY_SPEED_PIXELS_PER_STEP = 48f
private const val TEMPORARY_SPEED_STEP = 0.25
private const val PINCH_ZOOM_STEP_SCALE = 1.2f
