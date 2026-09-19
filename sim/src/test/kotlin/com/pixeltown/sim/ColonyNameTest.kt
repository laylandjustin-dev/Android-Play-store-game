package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.World as WorldConfig

class ColonyNameTest {

    @Test
    fun `a typed name is kept as typed`() {
        assertEquals("Greenhollow", ColonyName.sanitise("Greenhollow"))
        assertEquals("Port of Ash", ColonyName.sanitise("Port of Ash"))
    }

    @Test
    fun `nothing usable falls back to the default`() {
        for (empty in listOf(null, "", "   ", "\t\n", "\u200B\u0000")) {
            assertEquals(ColonyName.DEFAULT, ColonyName.sanitise(empty), "for [$empty]")
        }
    }

    @Test
    fun `whitespace is trimmed and collapsed`() {
        assertEquals("New Harbour", ColonyName.sanitise("   New    Harbour  "))
        assertEquals("One Two", ColonyName.sanitise("One\tTwo"))
    }

    @Test
    fun `control and formatting characters are dropped, not rejected`() {
        // A right-to-left override would reorder the whole HUD line it is drawn on.
        assertEquals("Calder", ColonyName.sanitise("Cal\u202Eder"))
        assertEquals("Ashfen", ColonyName.sanitise("Ash\u0007fen"))
    }

    @Test
    fun `a long name is capped and never ends mid-space`() {
        val long = ColonyName.sanitise("a".repeat(200))
        assertEquals(WorldConfig.MAX_COLONY_NAME_LENGTH, long.length)

        val spaced = ColonyName.sanitise(List(40) { "word" }.joinToString(" "))
        assertTrue(spaced.length <= WorldConfig.MAX_COLONY_NAME_LENGTH, spaced)
        assertEquals(spaced.trim(), spaced, "a capped name must not end in a space: [$spaced]")
    }

    @Test
    fun `sanitising is idempotent`() {
        for (typed in listOf("  Ash  fen ", "a".repeat(200), "", "Cal\u202Eder")) {
            val once = ColonyName.sanitise(typed)
            assertEquals(once, ColonyName.sanitise(once), "for [$typed]")
        }
    }

    @Test
    fun `rivals never share the player's name`() {
        for (name in ColonyName.POOL + listOf("Greenhollow", ColonyName.DEFAULT, "veyra")) {
            val rivals = ColonyName.rivalNames(name, WorldConfig.RIVAL_CIV_COUNT)
            assertEquals(WorldConfig.RIVAL_CIV_COUNT, rivals.size)
            assertEquals(rivals.size, rivals.distinct().size, "duplicate rival names: $rivals")
            for (rival in rivals) assertNotEquals(name.lowercase(), rival.lowercase())
        }
    }

    @Test
    fun `the run carries the name the player chose, and the rivals work around it`() {
        val sim = Simulation.newRun(
            RunConfig(seed = 7L, traits = TraitAllocation.of(3, 4, 3, 4, 8), colonyName = "  Kressen  "),
        )
        assertEquals("Kressen", sim.civ(WorldConfig.PLAYER_CIV_ID).name)
        val rivalNames = (1 until sim.civs.size).map { sim.civ(it).name }
        assertTrue("Kressen" !in rivalNames, rivalNames.toString())
        assertEquals(rivalNames.size, rivalNames.distinct().size, rivalNames.toString())
    }

    @Test
    fun `an unnamed run still has a name`() {
        val sim = Simulation.newRun(RunConfig(seed = 7L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        assertEquals(ColonyName.DEFAULT, sim.civ(WorldConfig.PLAYER_CIV_ID).name)
    }

    @Test
    fun `the name survives a save and does not touch determinism`() {
        val traits = TraitAllocation.of(3, 4, 3, 4, 8)
        val named = Simulation.newRun(RunConfig(seed = 11L, traits = traits, colonyName = "Greenhollow"))
        val unnamed = Simulation.newRun(RunConfig(seed = 11L, traits = traits))
        repeat(400) { named.runUnattended(1); unnamed.runUnattended(1) }

        // The name is presentation only: an identically seeded run must not diverge because of it.
        assertEquals(unnamed.rng.snapshot(), named.rng.snapshot())
        assertEquals(unnamed.civ(0).population, named.civ(0).population)

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(named.snapshot())))
        assertEquals("Greenhollow", reloaded.civ(0).name)
        assertEquals("Greenhollow", reloaded.config.colony)
    }

    @Test
    fun `a save written before colonies could be named still loads`() {
        val sim = Simulation.newRun(RunConfig(seed = 5L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        repeat(30) { sim.runUnattended(1) }
        // The field is defaulted, so an old file simply has no "colonyName" key in its config.
        val text = SaveFormat.encode(sim.snapshot())
        val stripped = text.replace("\"colonyName\":\"${ColonyName.DEFAULT}\",", "")
        assertTrue(stripped.length < text.length, "the key should have been present to remove")
        assertEquals(ColonyName.DEFAULT, Simulation.restore(SaveFormat.decode(stripped)).config.colony)
    }
}
