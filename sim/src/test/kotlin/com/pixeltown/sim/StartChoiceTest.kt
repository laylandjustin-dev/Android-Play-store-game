package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** Where the player lands, and what colour they land in. */
class StartChoiceTest {

    private val traits = TraitAllocation.of(3, 4, 3, 4, 8)

    private fun generated(seed: Long) = WorldGenerator.generate(SimRandom(seed))

    // ------------------------------------------------------------------ the landing site

    @Test
    fun `a legal choice is where the colony is founded`() {
        val preview = generated(42L)
        val legal = WorldGenerator.legalStartSites(preview.world)
        // Somewhere the generator did not pick, so the assertion means something.
        val chosen = legal.indices.first { legal[it] && it !in preview.civStartSites.toSet() }

        val sim = Simulation.newRun(RunConfig(seed = 42L, traits = traits, startCell = chosen))
        assertEquals(chosen, sim.civ(WorldConfig.PLAYER_CIV_ID).homeSite)
        assertTrue(sim.citizens.any { it.civId == WorldConfig.PLAYER_CIV_ID })
    }

    @Test
    fun `settlers really are placed around the chosen site`() {
        val preview = generated(42L)
        val legal = WorldGenerator.legalStartSites(preview.world)
        val chosen = legal.indices.last { legal[it] }

        val sim = Simulation.newRun(RunConfig(seed = 42L, traits = traits, startCell = chosen))
        val world = sim.world
        val cx = chosen % world.width
        val cy = chosen / world.width
        val settlers = sim.citizens.filter { it.civId == WorldConfig.PLAYER_CIV_ID }
        assertTrue(settlers.isNotEmpty())
        for (settler in settlers) {
            val distance = maxOf(kotlin.math.abs(settler.x - cx), kotlin.math.abs(settler.y - cy))
            assertTrue(
                distance <= WorldConfig.SETTLEMENT_SPAWN_RADIUS,
                "a settler landed $distance cells from the chosen site",
            )
        }
    }

    @Test
    fun `an illegal choice is ignored, not obeyed`() {
        val preview = generated(42L)
        val legal = WorldGenerator.legalStartSites(preview.world)
        val ocean = legal.indices.first { !legal[it] }

        for (bad in listOf(ocean, -1, preview.world.cellCount, Int.MAX_VALUE)) {
            val sim = Simulation.newRun(RunConfig(seed = 42L, traits = traits, startCell = bad))
            val home = sim.civ(WorldConfig.PLAYER_CIV_ID).homeSite
            assertNotEquals(bad, home, "the run was founded on an illegal cell")
            assertTrue(legal[home], "the fallback site is itself unusable")
        }
    }

    @Test
    fun `every legal site is buildable land on the main landmass`() {
        for (seed in listOf(1L, 42L, 555L)) {
            val world = generated(seed).world
            val legal = WorldGenerator.legalStartSites(world)
            assertTrue(legal.count { it } > 100, "seed $seed offered almost nowhere to land")
            for (i in legal.indices) {
                if (legal[i]) assertTrue(world.isBuildable(i), "cell $i on seed $seed is not buildable")
            }
            // The generator's own picks must be legal choices too, or the UI would contradict it.
            for (site in generated(seed).civStartSites) assertTrue(legal[site])
        }
    }

    @Test
    fun `rivals are still placed, and never on top of the player`() {
        val preview = generated(7L)
        val legal = WorldGenerator.legalStartSites(preview.world)
        val chosen = legal.indices.first { legal[it] }

        val sim = Simulation.newRun(RunConfig(seed = 7L, traits = traits, startCell = chosen))
        val sites = sim.civs.map { it.homeSite }
        assertEquals(WorldConfig.TOTAL_CIV_COUNT, sites.size)
        assertEquals(sites.size, sites.distinct().size, "two civs were founded on the same cell")
        assertEquals(chosen, sites.first())
    }

    @Test
    fun `the chosen site survives a save`() {
        val preview = generated(42L)
        val legal = WorldGenerator.legalStartSites(preview.world)
        val chosen = legal.indices.first { legal[it] && it !in preview.civStartSites.toSet() }

        val sim = Simulation.newRun(RunConfig(seed = 42L, traits = traits, startCell = chosen))
        sim.run(200)
        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(chosen, reloaded.civ(0).homeSite)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `choosing a site does not disturb determinism`() {
        val preview = generated(42L)
        val legal = WorldGenerator.legalStartSites(preview.world)
        val chosen = legal.indices.first { legal[it] }
        val config = RunConfig(seed = 42L, traits = traits, startCell = chosen)

        val a = Simulation.newRun(config)
        val b = Simulation.newRun(config)
        a.run(500)
        b.run(500)
        assertEquals(a.stateHash(), b.stateHash())
    }

    // ------------------------------------------------------------------ the colour

    @Test
    fun `the player is drawn in the colour they picked`() {
        for (index in Palette.PLAYER_CHOICES.indices) {
            val colors = CivColors.forPlayerChoice(index)
            assertEquals(Palette.PLAYER_CHOICES[index], colors[WorldConfig.PLAYER_CIV_ID])
        }
    }

    @Test
    fun `no rival wears the player's colour`() {
        for (index in Palette.PLAYER_CHOICES.indices) {
            val colors = CivColors.forPlayerChoice(index)
            val all = (0 until WorldConfig.TOTAL_CIV_COUNT).map { colors[it] }
            assertEquals(all.size, all.distinct().size, "two civs share a colour for choice $index: $all")
        }
    }

    @Test
    fun `an out-of-range colour falls back instead of throwing`() {
        for (index in listOf(-5, Palette.PLAYER_CHOICES.size, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val colors = CivColors.forPlayerChoice(index)
            assertTrue(colors[WorldConfig.PLAYER_CIV_ID] in Palette.PLAYER_CHOICES.toList())
        }
    }

    @Test
    fun `the colour reaches the rendered frame`() {
        val ember = Palette.PLAYER_CHOICES.indexOfFirst { it == 0xFFEF6F4A.toInt() }
        val sim = Simulation.newRun(RunConfig(seed = 1L, traits = traits, colorIndex = ember))
        val buffer = IntArray(sim.world.cellCount)
        FrameRenderer(sim.world).render(sim, buffer)

        val settler = sim.citizens.first { it.civId == WorldConfig.PLAYER_CIV_ID }
        val pixel = buffer[sim.world.index(settler.x, settler.y)]
        // Drawn dimmed by survival, so compare hue rather than the exact value: a warm ember
        // citizen has far more red than blue, which the default gold also has — so check it is
        // *not* what gold would have produced.
        val gold = Simulation.newRun(RunConfig(seed = 1L, traits = traits))
        val goldBuffer = IntArray(gold.world.cellCount)
        FrameRenderer(gold.world).render(gold, goldBuffer)
        assertNotEquals(goldBuffer[gold.world.index(settler.x, settler.y)], pixel)
    }

    @Test
    fun `the colour survives a save and does not touch determinism`() {
        val plain = Simulation.newRun(RunConfig(seed = 9L, traits = traits))
        val coloured = Simulation.newRun(RunConfig(seed = 9L, traits = traits, colorIndex = 3))
        plain.run(300)
        coloured.run(300)
        assertEquals(plain.stateHash(), coloured.stateHash(), "a colour changed the course of a run")

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(coloured.snapshot())))
        assertEquals(3, reloaded.config.colorIndex)
        assertEquals(Palette.PLAYER_CHOICES[3], reloaded.colors[WorldConfig.PLAYER_CIV_ID])
    }

    @Test
    fun `a save from before these choices still loads`() {
        val sim = Simulation.newRun(RunConfig(seed = 5L, traits = traits))
        sim.run(30)
        val text = SaveFormat.encode(sim.snapshot())
        val stripped = text.replace("\"startCell\":null,", "").replace("\"colorIndex\":0,", "")
        assertTrue(stripped.length < text.length, "the keys should have been present to remove")

        val reloaded = Simulation.restore(SaveFormat.decode(stripped))
        assertFalse(reloaded.config.startCell != null)
        assertEquals(0, reloaded.config.colorIndex)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }
}
