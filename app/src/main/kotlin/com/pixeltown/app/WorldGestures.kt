package com.pixeltown.app

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.pixeltown.sim.Viewport

/**
 * Pinch-to-zoom and drag-to-pan over the pixel map.
 *
 * Gesture deltas arrive in screen pixels and must be converted to world cells before they touch
 * the [Viewport], which is pure world-space arithmetic (and is unit-tested as such in `:sim`).
 * The conversion needs the on-screen size of one world cell, which is what [cellSizePx] provides.
 */
fun Modifier.worldGestures(
    viewport: () -> Viewport,
    canvasSize: () -> Size,
    onViewportChange: (Viewport) -> Unit,
): Modifier = composed {
    pointerInput(Unit) {
        detectTransformGestures { centroid, pan, zoom, _ ->
            val current = viewport()
            val cellPx = cellSizePx(current, canvasSize())
            if (cellPx <= 0f) return@detectTransformGestures

            // Dragging right moves the view left, so the map follows the finger.
            val panned = current.panBy(-pan.x / cellPx, -pan.y / cellPx)

            val next = if (zoom != 1f) {
                // The pinch centroid, in world cells, is the point that must stay put.
                val focusX = panned.left + centroid.x / cellPx
                val focusY = panned.top + centroid.y / cellPx
                panned.zoomBy(zoom, focusX, focusY)
            } else {
                panned
            }
            if (next != current) onViewportChange(next)
        }
    }
}

/** On-screen size of one world cell, matching the letterboxed scale used when drawing. */
internal fun cellSizePx(viewport: Viewport, canvasSize: Size): Float {
    if (canvasSize.width <= 0f || canvasSize.height <= 0f) return 0f
    return minOf(canvasSize.width / viewport.srcWidth, canvasSize.height / viewport.srcHeight)
}
