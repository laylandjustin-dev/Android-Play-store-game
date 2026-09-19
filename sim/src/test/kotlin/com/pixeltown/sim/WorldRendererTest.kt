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
    fun `a building fills its footprint and is clipped at the world edge`() {
        val w = World(8, 8)
        val pixels = IntArray(w.cellCount)
        WorldRenderer.drawBuilding(w, pixels, x = 6, y = 6, footprint = 3, category = BuildingCategory.FARMS)

        // Every cell of the footprint that is on the map is painted: a building is a solid object,
        // and only its *tone* says which cells are the figure.
        for (y in 6..7) {
            for (x in 6..7) assertNotEquals(0, pixels[w.index(x, y)], "footprint cell $x,$y unpainted")
        }
        // The base of a pentagon is the figure itself, in the category's accent.
        assertEquals(Palette.BUILDING[BuildingCategory.FARMS.ordinal], pixels[w.index(7, 7)])
        // Nothing wrapped around to the opposite edge.
        assertEquals(0, pixels[w.index(0, 0)])
    }

    @Test
    fun `each category is drawn as its own silhouette`() {
        // Colour alone cannot carry what a building is at this scale, so shape is the second
        // channel — which is only true if the shapes actually differ.
        val seen = mutableSetOf<List<Boolean>>()
        for (category in BuildingCategory.entries) {
            val w = World(4, 4)
            val pixels = IntArray(w.cellCount)
            WorldRenderer.drawBuilding(w, pixels, x = 0, y = 0, footprint = 3, category = category)

            val accent = Palette.BUILDING[category.ordinal]
            val figure = (0 until 3).flatMap { y -> (0 until 3).map { x -> pixels[w.index(x, y)] == accent } }
            assertTrue(figure.any { it }, "$category drew no figure at all")
            assertTrue(figure.any { !it }, "$category filled its whole footprint, so it has no shape")
            assertTrue(seen.add(figure), "$category has the same silhouette as another category")
        }
        assertEquals(BuildingCategory.entries.size, seen.size)
    }

    @Test
    fun `a half-built structure is drawn dimmer than a finished one`() {
        // An unfinished building does nothing for the town and should not look as though it does.
        val w = World(4, 4)
        val done = IntArray(w.cellCount)
        val building = IntArray(w.cellCount)
        WorldRenderer.drawBuilding(w, done, 0, 0, 3, BuildingCategory.TECH, complete = true)
        WorldRenderer.drawBuilding(w, building, 0, 0, 3, BuildingCategory.TECH, complete = false)

        fun luminance(argb: Int) = (argb ushr 16 and 0xFF) + (argb ushr 8 and 0xFF) + (argb and 0xFF)
        val finished = (0 until w.cellCount).sumOf { luminance(done[it]) }
        val unfinished = (0 until w.cellCount).sumOf { luminance(building[it]) }
        assertTrue(unfinished < finished, "a half-built structure was not drawn dimmer")
    }

    @Test
    fun `citizens are shaded by their job`() {
        // A citizen is one pixel, so what they are doing has nowhere to go but their colour.
        val w = World(4, 4)
        val shades = Job.entries.map { job ->
            val pixels = IntArray(w.cellCount)
            WorldRenderer.drawCitizen(
                pixels, w.index(1, 1), civId = 0, survival = GameConfig.Survival.MAX.toFloat(),
                isPlayer = true, citizenId = 7, job = job,
            )
            pixels[w.index(1, 1)]
        }
        // A farmer and a soldier must not be the same pixel, or the shading says nothing.
        assertNotEquals(shades[Job.FARMER.ordinal], shades[Job.SOLDIER.ordinal])
        assertNotEquals(shades[Job.CHILD.ordinal], shades[Job.FARMER.ordinal])
        assertTrue(shades.distinct().size >= Job.entries.size - 2, "too many jobs share a shade: $shades")
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

/** The player has to be able to find their own people on a 128x128 map. */
class PlayerLegibilityTest {

    @Test
    fun `the player's people are brighter than a rival's in the same condition`() {
        fun luminance(argb: Int) = (argb ushr 16 and 0xFF) + (argb ushr 8 and 0xFF) + (argb and 0xFF)
        val pixels = IntArray(4)

        // A struggling colony of the player's still reads clearly; a healthy rival is drawn back.
        WorldRenderer.drawCitizen(pixels, 0, civId = 0, survival = 25f, isPlayer = true)
        WorldRenderer.drawCitizen(pixels, 1, civId = 0, survival = 25f, isPlayer = false)
        assertTrue(luminance(pixels[0]) > luminance(pixels[1]), "the player's pixels were no brighter")

        // Focus pushes everyone else further back again.
        WorldRenderer.drawCitizen(pixels, 2, civId = 1, survival = 90f, isPlayer = false, focus = false)
        WorldRenderer.drawCitizen(pixels, 3, civId = 1, survival = 90f, isPlayer = false, focus = true)
        assertTrue(luminance(pixels[3]) < luminance(pixels[2]), "focus did not dim rivals")
    }

    @Test
    fun `a starving town still looks worse than a thriving one`() {
        // Raising the floor must not flatten the signal: dimming by survival is information.
        fun luminance(argb: Int) = (argb ushr 16 and 0xFF) + (argb ushr 8 and 0xFF) + (argb and 0xFF)
        val pixels = IntArray(2)
        WorldRenderer.drawCitizen(pixels, 0, civId = 0, survival = 95f, isPlayer = true)
        WorldRenderer.drawCitizen(pixels, 1, civId = 0, survival = 10f, isPlayer = true)
        assertTrue(luminance(pixels[0]) > luminance(pixels[1]), "hardship stopped showing on the map")
    }

    @Test
    fun `the founding site is ringed so it can be found`() {
        val world = World(48, 48)
        for (i in 0 until world.cellCount) world.setTerrain(i, TerrainType.PLAIN)
        val pixels = IntArray(world.cellCount)
        WorldRenderer.renderTerrain(world, pixels)
        val before = pixels.copyOf()

        val home = world.index(24, 24)
        WorldRenderer.drawHomeMarker(world, pixels, home, civId = 0, radius = 4)

        val changed = (0 until world.cellCount).count { pixels[it] != before[it] }
        assertTrue(changed > 8, "the home marker drew almost nothing ($changed cells)")
        assertEquals(before[home], pixels[home], "the marker covered the home cell itself")
    }

    @Test
    fun `the marker stays inside the map at the edge`() {
        val world = World(32, 32)
        val pixels = IntArray(world.cellCount)
        WorldRenderer.drawHomeMarker(world, pixels, world.index(1, 1), civId = 0, radius = 4)
        WorldRenderer.drawHomeMarker(world, pixels, world.index(30, 30), civId = 0, radius = 4)
        // Reaching this line without an exception is the assertion; nothing wrapped around.
        assertTrue(pixels.any { it != 0 })
    }

    @Test
    fun `the player's territory is tinted harder than a rival's`() {
        val world = World(8, 8)
        for (i in 0 until world.cellCount) world.setTerrain(i, TerrainType.PLAIN)
        val pixels = IntArray(world.cellCount)
        WorldRenderer.renderTerrain(world, pixels)
        val terrain = pixels[world.index(0, 0)]

        world.ownerCivId[world.index(1, 1)] = 0
        world.ownerCivId[world.index(2, 2)] = 1
        WorldRenderer.tintOwnership(world, pixels, strength = 0.15f, playerCivId = 0)

        fun distance(a: Int, b: Int) = kotlin.math.abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)) +
            kotlin.math.abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)) +
            kotlin.math.abs((a and 0xFF) - (b and 0xFF))

        assertTrue(
            distance(pixels[world.index(1, 1)], terrain) > distance(pixels[world.index(2, 2)], terrain),
            "the player's ground was no more distinct than a rival's",
        )
    }
}
