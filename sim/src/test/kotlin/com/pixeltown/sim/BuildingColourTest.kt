package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A building's shape says what it is; its colour says whose it is. */
class BuildingColourTest {

    private fun world() = WorldGenerator.generate(SimRandom(1L)).world

    private fun paint(civId: Int, colors: CivColors, category: BuildingCategory): IntArray {
        val w = world()
        val out = IntArray(w.cellCount)
        WorldRenderer.drawBuilding(
            w, out, x = 10, y = 10, footprint = 5, category = category,
            complete = true, civId = civId, colors = colors,
        )
        return out
    }

    @Test
    fun `two civs' buildings of the same kind are drawn differently`() {
        // The bug this fixes: every civ's farms were the same fixed yellow, so five towns'
        // buildings were indistinguishable on a shared island — the one thing a player most needs
        // to read at a glance.
        val colors = CivColors.DEFAULT
        val mine = paint(0, colors, BuildingCategory.FARMS)
        val theirs = paint(1, colors, BuildingCategory.FARMS)
        assertTrue(
            mine.indices.any { mine[it] != theirs[it] },
            "two different civs' farms were painted identically",
        )
    }

    @Test
    fun `a building answers to the colour the player actually chose`() {
        // Not to the stock table: rivals are recoloured around the player's pick (AD-52), so a
        // renderer reading Palette.CIV would draw the colour they used to be.
        val gold = paint(0, CivColors.forPlayerChoice(0), BuildingCategory.TECH)
        val other = paint(0, CivColors.forPlayerChoice(2), BuildingCategory.TECH)
        assertTrue(
            gold.indices.any { gold[it] != other[it] },
            "changing the player's colour did not change their buildings",
        )
    }

    @Test
    fun `the category still shows through, within one town's palette`() {
        // Colour carries the owner, but not so completely that a barracks and a granary in the same
        // town become the same block of paint. CATEGORY_TINT is what keeps them apart.
        val colors = CivColors.DEFAULT
        val farm = paint(0, colors, BuildingCategory.FARMS)
        val military = paint(0, colors, BuildingCategory.MILITARY)
        assertTrue(
            farm.indices.any { farm[it] != military[it] },
            "one civ's farm and barracks were painted identically",
        )
        assertTrue(WorldRenderer.CATEGORY_TINT > 0f, "the category accent was discarded entirely")
        assertTrue(
            WorldRenderer.CATEGORY_TINT < 0.5f,
            "the category outvotes the owner, which is the bug this replaced",
        )
    }

    @Test
    fun `the whole footprint is painted, and an unfinished building is dimmer`() {
        val w = world()
        val done = IntArray(w.cellCount)
        val half = IntArray(w.cellCount)
        WorldRenderer.drawBuilding(w, done, 10, 10, 5, BuildingCategory.LIFESTYLE, complete = true)
        WorldRenderer.drawBuilding(w, half, 10, 10, 5, BuildingCategory.LIFESTYLE, complete = false)

        var painted = 0
        for (dy in 0 until 5) for (dx in 0 until 5) {
            if (done[w.index(10 + dx, 10 + dy)] != 0) painted++
        }
        assertEquals(25, painted, "a building left holes in its own footprint")

        fun brightness(c: Int) = (c ushr 16 and 0xFF) + (c ushr 8 and 0xFF) + (c and 0xFF)
        val centre = w.index(12, 12)
        assertTrue(
            brightness(half[centre]) < brightness(done[centre]),
            "a half-built structure looked as finished as a completed one",
        )
    }

    @Test
    fun `every category has a shape of its own`() {
        // Since colour now carries the owner, the shape is the *only* thing left saying what a
        // building is — so two categories sharing one silhouette would be unreadable.
        val shapes = BuildingCategory.entries.map { BuildingShape.of(it) }
        assertEquals(shapes.size, shapes.distinct().size, "two categories share a silhouette: $shapes")
    }
}
