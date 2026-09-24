package com.forgery.app.feature.gallery.impl

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import java.io.File

/**
 * Full-screen image with manual pinch-zoom + pan; double-tap toggles 1x/3x.
 *
 * Gesture arbitration (lives inside a HorizontalPager next to the system
 * back gesture, so every consumer must be polite):
 * - touch starting inside the system-gesture edge zones: consume nothing,
 *   SystemUI keeps the back gesture;
 * - single-finger drag at 1x: consume nothing, the pager pages;
 * - single-finger drag while zoomed: pan (consumed);
 * - two fingers: pinch-zoom + pan (consumed).
 *
 * Reports [onZoomChanged] (true while scale > 1x) so the host pager can
 * additionally lock its swipe while zoomed.
 */
@Composable
internal fun ZoomableImage(
    imagePath: String,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 5f,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // System-gesture edge zones (px): read in composition, used inside pointerInput.
    val edgeLeftPx = WindowInsets.systemGestures.getLeft(density, layoutDirection)
    val edgeRightPx = WindowInsets.systemGestures.getRight(density, layoutDirection)

    fun applyZoom(newScale: Float, newOffset: Offset) {
        scale = newScale.coerceIn(minScale, maxScale)
        offset = clampOffset(newOffset, viewport, scale)
        onZoomChanged(scale > 1f)
    }

    AsyncImage(
        model = File(imagePath),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                viewport = size
                offset = clampOffset(offset, size, scale)
            }
            .pointerInput(edgeLeftPx, edgeRightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Edge zone belongs to the system back gesture: stay out.
                    if (down.position.x < edgeLeftPx ||
                        down.position.x > size.width - edgeRightPx
                    ) {
                        return@awaitEachGesture
                    }
                    var slopPan = Offset.Zero
                    var pastSlop = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all(PointerInputChange::isConsumed)) break
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    applyZoom(scale * zoomChange, offset + panChange)
                                    event.changes.forEach { it.consume() }
                                }
                                if (pressed.all { !it.pressed }) break
                            }
                            pressed.size == 1 && scale > 1f -> {
                                val drag = pressed[0]
                                slopPan += drag.position - drag.previousPosition
                                if (!pastSlop && slopPan.getDistance() > touchSlop) {
                                    pastSlop = true
                                }
                                if (pastSlop) {
                                    applyZoom(
                                        scale,
                                        offset + (drag.position - drag.previousPosition),
                                    )
                                    drag.consume()
                                }
                                if (!drag.pressed) break
                            }
                            // Single finger at 1x: pager territory, consume nothing.
                            else -> break
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        applyZoom(if (scale > 1.5f) 1f else 3f, Offset.Zero)
                    },
                )
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
    )
}

private fun clampOffset(offset: Offset, viewport: IntSize, scale: Float): Offset {
    if (viewport == IntSize.Zero || scale <= 1f) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}
