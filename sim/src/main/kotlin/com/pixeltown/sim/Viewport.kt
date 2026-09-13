package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Render
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The visible rectangle of the world, in world cells.
 *
 * Lives in `:sim` rather than the UI layer for two reasons: it is pure world-space arithmetic
 * that deserves tests, and the simulation itself needs it — rival civs are simulated in full
 * detail within [GameConfig.Rivals.DETAIL_RADIUS_CELLS] of the player-visible area and
 * approximated beyond it, so "what can the player see" is a simulation input, not a view detail.
 *
 * Immutable: panning and zooming return a new viewport, always clamped so the rectangle stays
 * inside the world. At zoom 1 the whole map is visible.
 */
data class Viewport(
    val zoom: Float,
    /** Centre of the view, in fractional world cells. */
    val centerX: Float,
    val centerY: Float,
    val worldWidth: Int = GameConfig.World.WIDTH,
    val worldHeight: Int = GameConfig.World.HEIGHT,
) {
    /** Width of the visible rectangle in cells. */
    val widthCells: Float get() = worldWidth / zoom

    /** Height of the visible rectangle in cells. */
    val heightCells: Float get() = worldHeight / zoom

    val left: Float get() = centerX - widthCells / 2f
    val top: Float get() = centerY - heightCells / 2f
    val right: Float get() = left + widthCells
    val bottom: Float get() = top + heightCells

    /** Integer source rectangle for the renderer: `[x, y, width, height]`, always within bounds. */
    val srcX: Int get() = left.roundToInt().coerceIn(0, worldWidth - srcWidth)
    val srcY: Int get() = top.roundToInt().coerceIn(0, worldHeight - srcHeight)
    val srcWidth: Int get() = widthCells.roundToInt().coerceIn(1, worldWidth)
    val srcHeight: Int get() = heightCells.roundToInt().coerceIn(1, worldHeight)

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Pans by a delta in world cells. */
    fun panBy(dxCells: Float, dyCells: Float): Viewport =
        copy(centerX = centerX + dxCells, centerY = centerY + dyCells).clamped()

    /**
     * Multiplies zoom by [factor], keeping the world point under ([focusX], [focusY]) — a pinch
     * centroid in world cells — in the same place, so pinch-to-zoom tracks the fingers.
     */
    fun zoomBy(factor: Float, focusX: Float = centerX, focusY: Float = centerY): Viewport {
        val newZoom = (zoom * factor).coerceIn(Render.MIN_ZOOM, Render.MAX_ZOOM)
        if (newZoom == zoom) return this
        // Keep the focus point at the same fractional offset within the view.
        val fx = (focusX - left) / widthCells
        val fy = (focusY - top) / heightCells
        val newWidth = worldWidth / newZoom
        val newHeight = worldHeight / newZoom
        val newLeft = focusX - fx * newWidth
        val newTop = focusY - fy * newHeight
        return copy(
            zoom = newZoom,
            centerX = newLeft + newWidth / 2f,
            centerY = newTop + newHeight / 2f,
        ).clamped()
    }

    /** Keeps the visible rectangle inside the world; centres it on any axis it fully covers. */
    fun clamped(): Viewport {
        val w = widthCells
        val h = heightCells
        val cx = if (w >= worldWidth) worldWidth / 2f else clamp(centerX, w / 2f, worldWidth - w / 2f)
        val cy = if (h >= worldHeight) worldHeight / 2f else clamp(centerY, h / 2f, worldHeight - h / 2f)
        return if (cx == centerX && cy == centerY) this else copy(centerX = cx, centerY = cy)
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    companion object {
        /** The whole world, centred: the state a new run starts in. */
        fun whole(
            worldWidth: Int = GameConfig.World.WIDTH,
            worldHeight: Int = GameConfig.World.HEIGHT,
        ): Viewport = Viewport(
            zoom = Render.MIN_ZOOM,
            centerX = worldWidth / 2f,
            centerY = worldHeight / 2f,
            worldWidth = worldWidth,
            worldHeight = worldHeight,
        )

        /** Centred on a cell index at the given zoom, e.g. to frame the player's settlement. */
        fun focusedOn(
            cellIndex: Int,
            zoom: Float,
            worldWidth: Int = GameConfig.World.WIDTH,
            worldHeight: Int = GameConfig.World.HEIGHT,
        ): Viewport = Viewport(
            zoom = zoom.coerceIn(Render.MIN_ZOOM, Render.MAX_ZOOM),
            centerX = (cellIndex % worldWidth).toFloat() + 0.5f,
            centerY = (cellIndex / worldWidth).toFloat() + 0.5f,
            worldWidth = worldWidth,
            worldHeight = worldHeight,
        ).clamped()
    }
}
