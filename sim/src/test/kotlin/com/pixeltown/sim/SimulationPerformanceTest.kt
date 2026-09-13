package com.pixeltown.sim

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * A guard against the tick cost quietly growing. Deliberately lenient — it runs on whatever CI
 * machine is available — but it will catch an accidental O(n^2) sweep over the population.
 *
 * Measured on a desktop JVM at the time of writing: ~0.06ms/tick at 500 citizens, 0.35ms at
 * 3,000, 0.64ms at 6,000. Cost is linear in population, which is what matters.
 */
class SimulationPerformanceTest {

    @Test
    fun `tick cost stays linear in population`() {
        val sim = Simulation.newRun(42L, TraitAllocation.EVEN_SPREAD)

        fun feed() = sim.civs.forEach { it[Resource.FOOD] = 20_000.0 }
        fun msPerTick(samples: Int): Double {
            val start = System.nanoTime()
            repeat(samples) { feed(); sim.step() }
            return (System.nanoTime() - start) / 1_000_000.0 / samples
        }

        while (sim.population < 800) { feed(); sim.step() }
        msPerTick(50) // warm up
        val small = sim.population
        val smallCost = msPerTick(100)

        while (sim.population < 3_000) { feed(); sim.step() }
        val large = sim.population
        val largeCost = msPerTick(100)

        assertTrue(largeCost < 5.0, "a $large-citizen tick took ${largeCost}ms")
        // Linear would be ~3.75x for this population ratio; allow a wide margin for noise, but
        // catch anything quadratic (which would be ~14x).
        val ratio = largeCost / smallCost.coerceAtLeast(0.001)
        val populationRatio = large.toDouble() / small
        assertTrue(
            ratio < populationRatio * 2.5,
            "tick cost grew ${ratio}x for a ${populationRatio}x population — that looks super-linear",
        )
    }

    @Test
    fun `a century of simulation runs in seconds, so offline catch-up is viable`() {
        // Offline catch-up replays real ticks rather than approximating, and the Founders Pass
        // raises the cap to 48 hours: that has to finish while a loading spinner is up.
        val sim = Simulation.newRun(7L, TraitAllocation.EVEN_SPREAD)
        val start = System.nanoTime()
        repeat(100 * GameConfig.Time.DAYS_PER_YEAR) {
            sim.civs.forEach { it[Resource.FOOD] = 20_000.0 }
            sim.step()
        }
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        assertTrue(seconds < 60.0, "100 simulated years took ${seconds}s")
    }
}
