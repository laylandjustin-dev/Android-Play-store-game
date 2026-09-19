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

    /**
     * These are tests of the *economy*, so they run a civ alone on the map.
     *
     * Once rivals landed (M5) the same allocation was ground down to 51 people in 200 years by
     * raids and invasion — true, and interesting, but it meant this test could fail for two quite
     * different reasons and no longer said which. The rival pressure is measured separately, in
     * `a colony fares worse with rivals on the map`.
     */
    @Test
    fun `a farming people stabilise and grow for two centuries`() {
        val sim = Simulation.newRun(1L, farmingAllocation, civCount = 1)
        val target = 200 * Time.DAYS_PER_YEAR

        var populationAtFifty = 0
        // Unattended: this is a headless economy check with no player in it, so the tech choices
        // a tier offers are taken automatically rather than stopping the world forever.
        while (sim.endState == null && sim.day < target) {
            sim.runUnattended(1)
            if (sim.day == 50L * Time.DAYS_PER_YEAR) populationAtFifty = sim.populationOf(0)
        }

        // Since M6 a thriving run *ends* — by ascending, or by outlasting three centuries. Only
        // being wiped out is a failure of the economy.
        assertTrue(
            sim.endState == null || sim.endState == EndState.ASCENSION || sim.endState == EndState.ENDURANCE,
            "the colony ended in ${sim.endState} in year ${sim.year}",
        )
        assertTrue(
            populationAtFifty > GameConfig.World.STARTING_SETTLERS,
            "the colony had not grown at all by year 50 ($populationAtFifty)",
        )
        assertTrue(
            sim.civ(0).peakPopulation > GameConfig.World.STARTING_SETTLERS * 3,
            "the colony never grew beyond ${sim.civ(0).peakPopulation}",
        )
    }

    @Test
    fun `a colony that cannot farm collapses quickly`() {
        for (seed in longArrayOf(1L, 42L)) {
            val sim = Simulation.newRun(seed, badAllocation, civCount = 1)
            sim.runUnattendedUntilEnd(maxDays = 60 * Time.DAYS_PER_YEAR)
            assertTrue(sim.endState == EndState.COLLAPSE, "seed $seed survived on Farming 1")
            assertTrue(sim.year < 30, "seed $seed took ${sim.year} years to fail")
        }
    }

    @Test
    fun `farming beats a careless allocation on every measure`() {
        val good = Simulation.newRun(42L, farmingAllocation, civCount = 1)
        val bad = Simulation.newRun(42L, badAllocation, civCount = 1)
        val days = 40 * Time.DAYS_PER_YEAR
        good.runUnattended(days)
        bad.runUnattended(days)
        assertTrue(
            good.populationOf(0) > bad.populationOf(0),
            "a farming people (${good.populationOf(0)}) did not out-grow a careless one (${bad.populationOf(0)})",
        )
    }

    @Test
    fun `a colony fares worse with rivals on the map`() {
        // The same seed and the same allocation, alone and then sharing the island with four
        // other civilisations. Rivals must cost something real, or none of M5 matters.
        // Across several seeds, not one. What M5 claims is that rivals cost something *in
        // general*, and that is a statistical property: measured over three maps the crowded run
        // is a fraction of the lone one (2,292 down to 255 on one), but on a map where the player
        // has a good site and the rivals spend themselves on each other it can come out slightly
        // ahead. Asserting the aggregate says what is actually true; asserting one seed said
        // something that happened to be true and then stopped being.
        val days = 150 * Time.DAYS_PER_YEAR
        var aloneTotal = 0
        var crowdedTotal = 0
        for (seed in longArrayOf(1L, 1_000L, 8_919L)) {
            val alone = Simulation.newRun(seed, farmingAllocation, civCount = 1)
            val crowded = Simulation.newRun(seed, farmingAllocation)
            alone.runUnattended(days)
            crowded.runUnattended(days)
            aloneTotal += alone.populationOf(0)
            crowdedTotal += crowded.populationOf(0)
        }
        assertTrue(
            crowdedTotal < aloneTotal,
            "sharing the map with four rivals cost nothing: $crowdedTotal vs $aloneTotal alone",
        )

        val crowded = Simulation.newRun(1L, farmingAllocation)
        crowded.runUnattended(days)
        assertTrue(
            crowded.chronicle.deathsBy(DeathCause.COMBAT) > 0,
            "150 years beside four rivals produced no fighting",
        )
    }

    @Test
    fun `the child share of a colony settles at a sustainable level`() {
        // A 14-year childhood means the birth rate decides the dependency ratio. Too high and the
        // workforce cannot feed the dependants; too low and nothing ever grows.
        val sim = Simulation.newRun(1L, farmingAllocation, civCount = 1)
        sim.runUnattended(60 * Time.DAYS_PER_YEAR)
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
        val sim = Simulation.newRun(1L, careless, civCount = 1)
        sim.runUnattended(30 * Time.DAYS_PER_YEAR)
        if (sim.endState != null) return // collapsing is also an acceptable outcome here

        val owned = (0 until sim.world.cellCount).filter { sim.world.ownerCivId[it].toInt() == 0 }
        val worked = owned.filter { GameConfig.Terrain.FERTILITY.getValue(sim.world.terrainAt(it)) > 0.5 }
        if (worked.isEmpty()) return
        val meanFertility = worked.map { sim.world.fertility[it].toDouble() }.average()
        assertTrue(meanFertility < 0.85, "worked land showed no wear after 30 years (mean $meanFertility)")
    }
}
