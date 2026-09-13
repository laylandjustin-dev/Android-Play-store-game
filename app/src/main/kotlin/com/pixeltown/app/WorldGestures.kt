package com.pixeltown.app

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.pixeltown.sim.Viewport

/**
 * Pinch-to-zoom and drag-to-pan over the pixel map.
 *
 * Gesture deltas arrive in screen pixels and must be converted to world cells before they touch
 * the [Viewport]. Both the conversion and the viewport arithmetic live in `:sim`, where they are
 * unit-tested; this file is only the Compose plumbing that feeds them.
 */
fun Modifier.worldGestures(
    viewport: () -> Viewport,
    canvasSize: () -> Size,
    onViewportChange: (Viewport) -> Unit,
): Modifier = pointerInput(Unit) {
    // No Modifier.composed wrapper: it is deprecated, and nothing here needs composition —
    // state is read through the lambdas so the gesture handler never goes stale.
    detectTransformGestures { centroid, pan, zoom, _ ->
        val current = viewport()
        val size = canvasSize()
        val cellPx = current.cellSizePx(size.width, size.height)
        if (cellPx <= 0f) return@detectTransformGestures

        // Dragging right moves the view left, so the map follows the finger.
        val panned = current.panBy(-pan.x / cellPx, -pan.y / cellPx)

        val next = if (zoom != 1f) {
            // The pinch centroid, in world cells, is the point that must stay put.
            val focusX = panned.screenToWorldX(centroid.x, cellPx)
            val focusY = panned.screenToWorldY(centroid.y, cellPx)
            panned.zoomBy(zoom, focusX, focusY)
        } else {
            panned
        }
        if (next != current) onViewportChange(next)
    }
}
