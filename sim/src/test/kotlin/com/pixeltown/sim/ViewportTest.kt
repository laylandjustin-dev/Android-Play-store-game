package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Render
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ViewportTest {

    @Test
    fun `zoom 1 shows the whole world`() {
        val v = Viewport.whole()
        assertEquals(0, v.srcX)
        assertEquals(0, v.srcY)
        assertEquals(GameConfig.World.WIDTH, v.srcWidth)
        assertEquals(GameConfig.World.HEIGHT, v.srcHeight)
    }

    @Test
    fun `zoom is clamped to the configured range`() {
        val zoomedOut = Viewport.whole().zoomBy(0.01f)
        assertEquals(Render.MIN_ZOOM, zoomedOut.zoom)
        val zoomedIn = Viewport.whole().zoomBy(1000f)
        assertEquals(Render.MAX_ZOOM, zoomedIn.zoom)
    }

    @Test
    fun `panning never shows anything outside the world`() {
        var v = Viewport.whole().zoomBy(4f)
        for (step in listOf(-999f, -30f, -1f, 1f, 30f, 999f)) {
            v = v.panBy(step, step)
            assertTrue(v.srcX >= 0 && v.srcY >= 0, "src origin went negative: $v")
            assertTrue(v.srcX + v.srcWidth <= GameConfig.World.WIDTH, "src ran off the right: $v")
            assertTrue(v.srcY + v.srcHeight <= GameConfig.World.HEIGHT, "src ran off the bottom: $v")
        }
    }

    @Test
    fun `panning at zoom 1 stays centred`() {
        val v = Viewport.whole().panBy(40f, -25f)
        assertEquals(GameConfig.World.WIDTH / 2f, v.centerX)
        assertEquals(GameConfig.World.HEIGHT / 2f, v.centerY)
    }

    @Test
    fun `pinch zoom keeps the focus point under the fingers`() {
        val start = Viewport.whole().zoomBy(2f)
        val focusX = 40f
        val focusY = 90f
        val zoomed = start.zoomBy(2f, focusX, focusY)
        // The focus point must sit at the same fractional position in the view as before.
        val before = (focusX - start.left) / start.widthCells to (focusY - start.top) / start.heightCells
        val after = (focusX - zoomed.left) / zoomed.widthCells to (focusY - zoomed.top) / zoomed.heightCells
        assertEquals(before.first, after.first, absoluteTolerance = 1e-3f)
        assertEquals(before.second, after.second, absoluteTolerance = 1e-3f)
    }

    @Test
    fun `focusing on a cell frames it`() {
        val world = World()
        val cell = world.index(10, 120)
        val v = Viewport.focusedOn(cell, zoom = 8f)
        assertTrue(v.contains(10, 120), "focused cell is not visible in $v")
        assertTrue(v.srcX >= 0 && v.srcY + v.srcHeight <= world.height)
    }

    @Test
    fun `cell size letterboxes rather than stretches`() {
        val v = Viewport.whole()
        // A wide canvas: the scale is set by the limiting axis, so cells stay square.
        val cell = v.cellSizePx(1200f, 600f)
        assertEquals(600f / GameConfig.World.HEIGHT, cell)
        assertEquals(0f, v.cellSizePx(0f, 600f), "a zero-width canvas must not divide by zero")
        assertEquals(0f, v.cellSizePx(1200f, -1f))
    }

    @Test
    fun `cells grow on screen as the view zooms in`() {
        val out = Viewport.whole()
        val inn = Viewport.whole().zoomBy(4f)
        assertTrue(inn.cellSizePx(1000f, 1000f) > out.cellSizePx(1000f, 1000f))
    }

    @Test
    fun `screen coordinates map back to the world`() {
        val v = Viewport.whole().zoomBy(4f).panBy(10f, 10f)
        val cell = v.cellSizePx(800f, 800f)
        // The top-left of the canvas is the top-left of the visible rectangle.
        assertEquals(v.left, v.screenToWorldX(0f, cell), absoluteTolerance = 1e-3f)
        assertEquals(v.top, v.screenToWorldY(0f, cell), absoluteTolerance = 1e-3f)
        // One cell across on screen is one cell across in the world.
        assertEquals(v.left + 1f, v.screenToWorldX(cell, cell), absoluteTolerance = 1e-3f)
    }

    @Test
    fun `the visible rectangle shrinks as zoom rises`() {
        var previous = Int.MAX_VALUE
        for (zoom in listOf(1f, 2f, 4f, 8f)) {
            val v = Viewport.whole().zoomBy(zoom)
            val area = v.srcWidth * v.srcHeight
            assertTrue(area < previous, "zoom $zoom did not shrink the view")
            previous = area
        }
    }
}
