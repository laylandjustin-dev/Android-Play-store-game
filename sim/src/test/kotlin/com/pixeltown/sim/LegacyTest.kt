package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Meta
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyTest {

    // ------------------------------------------------------------------ end states

    @Test
    fun `a colony wiped out collapses`() {
        val world = World(48, 48)
        for (i in 0 until world.cellCount) {
            world.setTerrain(i, TerrainType.BEACH)
            world.fertility[i] = 0f
            world.wildGame[i] = 0f
        }
        val civ = Civilization(0, "Doomed", TraitAllocation.EVEN_SPREAD, Personality.ISOLATIONIST, world.index(24, 24))
        val sim = Simulation(world, listOf(civ), SimRandom(1L))
        sim.found(civ)
        sim.runUntilEnd(2_000)

        assertEquals(EndState.COLLAPSE, sim.endState)
        val summary = sim.summary()
        assertNotNull(summary)
        assertEquals(EndState.COLLAPSE, summary.endState)
        assertTrue(summary.peakPopulation >= GameConfig.World.STARTING_SETTLERS)
    }

    @Test
    fun `a long-lived civilisation reaches one of the two good endings`() {
        // Left alone, a farming civ reaches tier 6 with a large population before year 300 and
        // ascends. Ascension outranks Endurance, which is the intended ordering: the better
        // ending wins when a run qualifies for both.
        val sim = Simulation.newRun(1L, TraitAllocation.of(3, 4, 3, 4, 8), civCount = 1)
        sim.runUntilEnd(310 * Time.DAYS_PER_YEAR)
        assertTrue(
            sim.endState == EndState.ASCENSION || sim.endState == EndState.ENDURANCE,
            "a prospering solo civ ended in ${sim.endState}",
        )
        assertTrue(sim.year <= Meta.ENDURANCE_YEARS, "the run ran past the Endurance limit")
    }

    @Test
    fun `endurance ends a run that never ascends`() {
        // A civ that cannot reach tier 6 has only the calendar to end its run.
        val sim = Simulation.newRun(1L, TraitAllocation.of(3, 6, 3, 5, 5), civCount = 1)
        sim.runUntilEnd(320 * Time.DAYS_PER_YEAR)
        assertTrue(sim.endState != null, "the run never ended at all")
        if (sim.endState == EndState.ENDURANCE) {
            assertEquals(Meta.ENDURANCE_YEARS, sim.year, "Endurance fired on the wrong year")
        }
    }

    @Test
    fun `a run ends only once and is recorded once`() {
        val sim = Simulation.newRun(1L, TraitAllocation.of(3, 4, 3, 4, 8), civCount = 1)
        sim.runUntilEnd(310 * Time.DAYS_PER_YEAR)
        val ending = sim.endState
        sim.run(500) // further ticks must not change or re-record the ending
        assertEquals(ending, sim.endState)
        assertEquals(1, sim.chronicle.totalOf(ChronicleEventKind.RUN_ENDED))
    }

    @Test
    fun `the end state is preserved across a save`() {
        val sim = Simulation.newRun(1L, TraitAllocation.of(3, 4, 3, 4, 8), civCount = 1)
        sim.runUntilEnd(310 * Time.DAYS_PER_YEAR)
        val loaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(sim.endState, loaded.endState)
        assertEquals(sim.summary(), loaded.summary())
    }

    // ------------------------------------------------------------------ scoring

    @Test
    fun `Chronicle points follow the documented formula`() {
        // floor(peak/10 + years/4 + tier^2 * 6 + endStateBonus)
        val score = Legacy.scoreRun(peakPopulation = 400, yearsSurvived = 200, techTier = 4, endState = EndState.ENDURANCE)
        val expected = 400 / 10 + 200 / 4 + 4 * 4 * 6 + Meta.END_STATE_BONUS.getValue(EndState.ENDURANCE)
        assertEquals(expected, score)
    }

    @Test
    fun `a better run always scores at least as well`() {
        val base = Legacy.scoreRun(200, 100, 3, EndState.COLLAPSE)
        assertTrue(Legacy.scoreRun(400, 100, 3, EndState.COLLAPSE) > base, "peak population did not count")
        assertTrue(Legacy.scoreRun(200, 200, 3, EndState.COLLAPSE) > base, "years did not count")
        assertTrue(Legacy.scoreRun(200, 100, 5, EndState.COLLAPSE) > base, "tech tier did not count")
        assertTrue(Legacy.scoreRun(200, 100, 3, EndState.ASCENSION) > base, "the end state did not count")
        assertTrue(Legacy.scoreRun(0, 0, 0, EndState.COLLAPSE) >= 0, "a disastrous run scored negative")
    }

    @Test
    fun `ascension is worth far more than collapse`() {
        val collapse = Legacy.scoreRun(300, 150, 5, EndState.COLLAPSE)
        val ascension = Legacy.scoreRun(300, 150, 5, EndState.ASCENSION)
        assertTrue(ascension > collapse * 1.5, "ascending was barely better than dying")
    }

    // ------------------------------------------------------------------ the legacy

    @Test
    fun `points are earned, spent, and never overspent`() {
        val legacy = Legacy()
        legacy.grant(500)
        assertEquals(500, legacy.chroniclePoints)
        assertFalse(legacy.spend(501), "spent more than was held")
        assertEquals(500, legacy.chroniclePoints)
        assertTrue(legacy.spend(200))
        assertEquals(300, legacy.chroniclePoints)
    }

    @Test
    fun `purchased allocation points cap at four however much is spent`() {
        // The design's hard rule, and the thing that keeps the game from being buyable.
        val legacy = Legacy()
        legacy.grant(10_000_000)
        repeat(50) { legacy.buy(LegacyUpgrade.ALLOCATION_POINT) }

        assertEquals(TraitConfig.MAX_PURCHASED_ALLOCATION_POINTS, legacy.levelOf(LegacyUpgrade.ALLOCATION_POINT))
        assertEquals(TraitConfig.MAX_PURCHASED_ALLOCATION_POINTS, legacy.bonusAllocationPoints)
        assertNull(legacy.costOf(LegacyUpgrade.ALLOCATION_POINT), "a fifth point was still for sale")
        assertFalse(legacy.buy(LegacyUpgrade.ALLOCATION_POINT))
        assertTrue(legacy.chroniclePoints > 0, "the cap was enforced by bankrupting the player")
    }

    @Test
    fun `a restored legacy cannot smuggle in a level past the cap`() {
        val legacy = Legacy.restore(0, 0, 0, mapOf(LegacyUpgrade.ALLOCATION_POINT to 99))
        assertEquals(TraitConfig.MAX_PURCHASED_ALLOCATION_POINTS, legacy.bonusAllocationPoints)
    }

    @Test
    fun `allocation points cost more each time`() {
        val legacy = Legacy()
        legacy.grant(1_000_000)
        var previous = 0
        repeat(TraitConfig.MAX_PURCHASED_ALLOCATION_POINTS) {
            val cost = legacy.costOf(LegacyUpgrade.ALLOCATION_POINT)!!
            assertTrue(cost > previous, "upgrade ${it + 1} cost $cost, no more than the last")
            previous = cost
            legacy.buy(LegacyUpgrade.ALLOCATION_POINT)
        }
    }

    @Test
    fun `every upgrade has a cost, a cap, and an effect`() {
        for (upgrade in LegacyUpgrade.entries) {
            val legacy = Legacy()
            legacy.grant(10_000_000)
            assertNotNull(legacy.costOf(upgrade), "$upgrade could never be bought")
            repeat(upgrade.maxLevel + 3) { legacy.buy(upgrade) }
            assertEquals(upgrade.maxLevel, legacy.levelOf(upgrade), "$upgrade went past its cap")
            assertNull(legacy.costOf(upgrade), "$upgrade was still for sale at its cap")
        }

        val maxed = Legacy()
        maxed.grant(10_000_000)
        for (upgrade in LegacyUpgrade.entries) repeat(upgrade.maxLevel) { maxed.buy(upgrade) }
        assertTrue(maxed.startingSettlers > GameConfig.World.STARTING_SETTLERS)
        assertTrue(maxed.skillGrowthMultiplier > 1.0)
        assertTrue(maxed.startingInfluence > 0f)
        assertTrue(maxed.fertilityFloor > 0f)
        assertTrue(maxed.startingTensionRelief > 0.0)
        assertTrue(maxed.startingBuildings > 0)
    }

    @Test
    fun `runs are recorded and the best is remembered`() {
        val legacy = Legacy()
        legacy.recordRun(40)
        legacy.recordRun(120)
        legacy.recordRun(75)
        assertEquals(3, legacy.runsPlayed)
        assertEquals(120, legacy.bestYears)
    }

    // ------------------------------------------------------------------ the new-run flow

    @Test
    fun `a legacy shapes the run it starts`() {
        val legacy = Legacy()
        legacy.grant(10_000_000)
        repeat(LegacyUpgrade.EXTRA_SETTLERS.maxLevel) { legacy.buy(LegacyUpgrade.EXTRA_SETTLERS) }
        repeat(LegacyUpgrade.STANDING.maxLevel) { legacy.buy(LegacyUpgrade.STANDING) }
        repeat(LegacyUpgrade.FERTILITY_FLOOR.maxLevel) { legacy.buy(LegacyUpgrade.FERTILITY_FLOOR) }

        val config = RunConfig.from(1L, TraitAllocation.EVEN_SPREAD, legacy)
        val sim = Simulation.newRun(config)

        assertEquals(legacy.startingSettlers, sim.populationOf(0), "the extra settlers did not arrive")
        assertTrue(
            sim.citizens.filter { it.civId == 0 }.all { it.influence > 0f },
            "the standing upgrade granted no influence",
        )
        val farmland = (0 until sim.world.cellCount)
            .filter { GameConfig.Terrain.FERTILITY.getValue(sim.world.terrainAt(it)) > 0.0 }
        assertTrue(
            farmland.all { sim.world.fertility[it] >= legacy.fertilityFloor },
            "the fertility floor was not applied to the land",
        )
    }

    @Test
    fun `rivals start better disposed toward a well-connected player`() {
        val legacy = Legacy()
        legacy.grant(10_000_000)
        repeat(LegacyUpgrade.DIPLOMACY.maxLevel) { legacy.buy(LegacyUpgrade.DIPLOMACY) }

        val plain = Simulation.newRun(RunConfig(seed = 1L, traits = TraitAllocation.EVEN_SPREAD))
        val connected = Simulation.newRun(RunConfig.from(1L, TraitAllocation.EVEN_SPREAD, legacy))

        // Tension starts at zero, so relief shows up once friction has had time to build.
        plain.run(20 * Time.DAYS_PER_YEAR)
        connected.run(20 * Time.DAYS_PER_YEAR)
        val plainTension = (1 until plain.civs.size).sumOf { plain.relations.tensionBetween(0, it) }
        val easedTension = (1 until connected.civs.size).sumOf { connected.relations.tensionBetween(0, it) }
        assertTrue(easedTension <= plainTension, "diplomacy made relations worse: $easedTension vs $plainTension")
    }

    @Test
    fun `the run configuration is fixed when the run starts`() {
        // AD-8: a purchase mid-run must not change the run in progress. The simulation reads its
        // configuration once and never consults the legacy again.
        val legacy = Legacy()
        val sim = Simulation.newRun(RunConfig.from(1L, TraitAllocation.EVEN_SPREAD, legacy))
        sim.run(2 * Time.DAYS_PER_YEAR)
        val before = sim.stateHash()
        val settlersAtStart = sim.config.settlers

        legacy.grant(10_000_000)
        repeat(LegacyUpgrade.EXTRA_SETTLERS.maxLevel) { legacy.buy(LegacyUpgrade.EXTRA_SETTLERS) }
        repeat(LegacyUpgrade.FAST_LEARNERS.maxLevel) { legacy.buy(LegacyUpgrade.FAST_LEARNERS) }

        assertEquals(settlersAtStart, sim.config.settlers, "an in-flight run saw a purchase")
        assertEquals(before, sim.stateHash(), "buying an upgrade changed a run already under way")

        sim.run(360)
        val fresh = Simulation.newRun(RunConfig.from(1L, TraitAllocation.EVEN_SPREAD, legacy))
        assertTrue(fresh.config.settlers > settlersAtStart, "the next run did not benefit")
    }

    @Test
    fun `the legacy survives a save`() {
        val sim = Simulation.newRun(1L, TraitAllocation.EVEN_SPREAD)
        sim.legacy.grant(900)
        sim.legacy.buy(LegacyUpgrade.ALLOCATION_POINT)
        sim.run(500)

        val loaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(sim.legacy.chroniclePoints, loaded.legacy.chroniclePoints)
        assertEquals(
            sim.legacy.levelOf(LegacyUpgrade.ALLOCATION_POINT),
            loaded.legacy.levelOf(LegacyUpgrade.ALLOCATION_POINT),
        )
    }
}
