package com.pixeltown.sim

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorldRendererTest {

    private fun world(seed: Long = 42L) = WorldGenerator.generate(SimRandom(seed)).world

    @Test
    fun `every pixel is painted opaque`() {
        val w = world()
        val pixels = IntArray(w.cellCount)
        WorldRenderer.renderTerrain(w, pixels)
        for (i in 0 until w.cellCount) {
            assertEquals(0xFF, pixels[i] ushr 24 and 0xFF, "pixel $i is not opaque")
        }
    }

    @Test
    fun `a short buffer is rejected rather than silently clipped`() {
        val w = world()
        assertThrows<IllegalArgumentException> {
            WorldRenderer.renderTerrain(w, IntArray(w.cellCount - 1))
        }
    }

    @Test
    fun `terrain types are visually distinguishable`() {
        val w = world()
        val pixels = IntArray(w.cellCount)
        WorldRenderer.renderTerrain(w, pixels)
        // Average the painted colour per terrain type; ocean must not look like plains.
        val sums = LongArray(TerrainType.entries.size)
        val counts = IntArray(TerrainType.entries.size)
        for (i in 0 until w.cellCount) {
            val t = w.terrain[i].toInt()
            sums[t] += (pixels[i] and 0xFFFFFF).toLong()
            counts[t]++
        }
        val oceanAvg = sums[TerrainType.OCEAN.ordinal] / counts[TerrainType.OCEAN.ordinal]
        val plainAvg = sums[TerrainType.PLAIN.ordinal] / counts[TerrainType.PLAIN.ordinal]
        assertNotEquals(oceanAvg, plainAvg)
    }

    @Test
    fun `rendering is deterministic and depends only on world state`() {
        val w = world()
        val a = IntArray(w.cellCount)
        val b = IntArray(w.cellCount)
        WorldRenderer.renderTerrain(w, a)
        WorldRenderer.renderTerrain(w, b)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `citizens dim as survival falls`() {
        val pixels = IntArray(4)
        WorldRenderer.drawCitizen(pixels, 0, civId = 0, survival = 100f)
        WorldRenderer.drawCitizen(pixels, 1, civId = 0, survival = 10f)
        fun luminance(argb: Int) = (argb ushr 16 and 0xFF) + (argb ushr 8 and 0xFF) + (argb and 0xFF)
        assertTrue(luminance(pixels[0]) > luminance(pixels[1]), "a starving citizen should be dimmer")
    }

    @Test
    fun `each civ is a distinct colour`() {
        val colours = (0 until GameConfig.World.TOTAL_CIV_COUNT).map { Palette.civColor(it) }
        assertEquals(colours.size, colours.distinct().size)
    }

    @Test
    fun `buildings are drawn as blocks and clipped at the world edge`() {
        val w = World(8, 8)
        val pixels = IntArray(w.cellCount)
        WorldRenderer.drawBuilding(w, pixels, x = 6, y = 6, footprint = 3, category = BuildingCategory.FARMS)
        val expected = Palette.BUILDING[BuildingCategory.FARMS.ordinal]
        assertEquals(expected, pixels[w.index(7, 7)])
        assertEquals(expected, pixels[w.index(6, 6)])
        // Nothing wrapped around to the opposite edge.
        assertEquals(0, pixels[w.index(0, 0)])
    }

    @Test
    fun `ownership tinting only touches claimed cells`() {
        val w = World(4, 4)
        val pixels = IntArray(w.cellCount) { 0xFF000000.toInt() }
        w.ownerCivId[w.index(1, 1)] = 0
        WorldRenderer.tintOwnership(w, pixels, strength = 0.5f)
        assertNotEquals(0xFF000000.toInt(), pixels[w.index(1, 1)])
        assertEquals(0xFF000000.toInt(), pixels[w.index(0, 0)])
    }

    @Test
    fun `a full frame repaint is fast enough for 60fps`() {
        val w = world()
        val pixels = IntArray(w.cellCount)
        repeat(20) { WorldRenderer.renderTerrain(w, pixels) } // warm up
        val start = System.nanoTime()
        repeat(200) { WorldRenderer.renderTerrain(w, pixels) }
        val millisPerFrame = (System.nanoTime() - start) / 1_000_000.0 / 200
        assertTrue(millisPerFrame < 4.0, "terrain repaint took ${millisPerFrame}ms, budget is 16ms")
    }
}
