package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Time
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The M3 milestone gate: a decent Farming allocation stabilises and slowly grows for 200+ years;
 * a bad one collapses.
 *
 * These are long runs and the slowest tests in the suite, deliberately: this is the behaviour the
 * whole economy exists to produce, and it is worth a minute of CI time. The full balance sweep
 * across a grid of allocations belongs to the headless harness at M7 (§12 of the brief); this
 * only pins the two ends of the spectrum so a regression cannot pass unnoticed.
 *
 * Measured at the time of writing, 300-year cap, seeds 1/42/555, population peak in brackets:
 *   even 5/5/5/5/5      300y [102]   195y [92]    300y [1237]
 *   farmer 3/4/3/4/8    300y [860]   300y [678]   277y [83]
 *   farm+elem 3/4/3/6/6 300y [2666]  300y [1097]  300y [708]
 *   hunter 5/4/8/3/3      2y [50]      1y [51]      1y [55]
 *   bad 8/3/3/3/1         0y [50]      0y [50]      0y [50]
 */
class BalanceTest {

    private val farmingAllocation = TraitAllocation.of(3, 4, 3, 4, 8)
    private val badAllocation = TraitAllocation.of(8, 3, 3, 3, 1)

    @Test
    fun `a farming people stabilise and grow for two centuries`() {
        val sim = Simulation.newRun(1L, farmingAllocation)
        val target = 200 * Time.DAYS_PER_YEAR

        var populationAtFifty = 0
        while (sim.endState == null && sim.day < target) {
            sim.step()
            if (sim.day == 50L * Time.DAYS_PER_YEAR) populationAtFifty = sim.populationOf(0)
        }

        assertTrue(sim.endState == null, "the colony collapsed in year ${sim.year}")
        val finalPopulation = sim.populationOf(0)
        assertTrue(
            finalPopulation > GameConfig.World.STARTING_SETTLERS * 3,
            "after 200 years the colony was only $finalPopulation strong",
        )
        assertTrue(
            populationAtFifty > GameConfig.World.STARTING_SETTLERS,
            "the colony had not grown at all by year 50 ($populationAtFifty)",
        )
    }

    @Test
    fun `a colony that cannot farm collapses quickly`() {
        for (seed in longArrayOf(1L, 42L)) {
            val sim = Simulation.newRun(seed, badAllocation)
            sim.runUntilEnd(maxDays = 60 * Time.DAYS_PER_YEAR)
            assertTrue(sim.endState == EndState.COLLAPSE, "seed $seed survived on Farming 1")
            assertTrue(sim.year < 30, "seed $seed took ${sim.year} years to fail")
        }
    }

    @Test
    fun `farming beats a careless allocation on every measure`() {
        val good = Simulation.newRun(42L, farmingAllocation)
        val bad = Simulation.newRun(42L, badAllocation)
        val days = 40 * Time.DAYS_PER_YEAR
        good.run(days)
        bad.run(days)
        assertTrue(
            good.populationOf(0) > bad.populationOf(0),
            "a farming people (${good.populationOf(0)}) did not out-grow a careless one (${bad.populationOf(0)})",
        )
    }

    @Test
    fun `the child share of a colony settles at a sustainable level`() {
        // A 14-year childhood means the birth rate decides the dependency ratio. Too high and the
        // workforce cannot feed the dependants; too low and nothing ever grows.
        val sim = Simulation.newRun(1L, farmingAllocation)
        sim.run(60 * Time.DAYS_PER_YEAR)
        val mine = sim.citizens.filter { it.civId == 0 }
        assertTrue(mine.isNotEmpty(), "the colony died before the measurement")
        val childShare = mine.count { it.isChild }.toDouble() / mine.size
        assertTrue(childShare in 0.15..0.55, "child share was $childShare after 60 years")
    }

    @Test
    fun `over-farming exhausts land that is worked harder than it recovers`() {
        // Drain must beat recovery for a careless people, or exhaustion is not a failure mode.
        val careless = TraitAllocation.of(5, 5, 5, 5, 3)
        assertTrue(
            careless.fertilityRecoveryPerDay < GameConfig.Terrain.FERTILITY_DRAIN_PER_FARM_DAY,
            "a Farming-3 people recover soil faster than they drain it, so land never exhausts",
        )
        val sim = Simulation.newRun(1L, careless)
        sim.run(30 * Time.DAYS_PER_YEAR)
        if (sim.endState != null) return // collapsing is also an acceptable outcome here

        val owned = (0 until sim.world.cellCount).filter { sim.world.ownerCivId[it].toInt() == 0 }
        val worked = owned.filter { GameConfig.Terrain.FERTILITY.getValue(sim.world.terrainAt(it)) > 0.5 }
        if (worked.isEmpty()) return
        val meanFertility = worked.map { sim.world.fertility[it].toDouble() }.average()
        assertTrue(meanFertility < 0.85, "worked land showed no wear after 30 years (mean $meanFertility)")
    }
}
