package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Traits as TraitConfig

/** The sixth trait: timber, stone, and how fast a people raise a building. */
class LoggingTraitTest {

    @Test
    fun `a base-three people gather and build at exactly the old rate`() {
        // The whole point of the scaling: every balance table in CLAUDE.md was measured before
        // Logging existed, and they all still mean what they say.
        val base = TraitAllocation.of(3, 4, 3, 4, 8)
        assertEquals(TraitConfig.BASE_VALUE, base[Trait.LOGGING])
        assertEquals(1.0, base.gatherYield, 0.0001, "a base-three people did not gather the old amount")
        assertEquals(1.0, base.buildRate, 0.0001, "a base-three people did not build at the old rate")
    }

    @Test
    fun `logging moves gathering and building in both directions`() {
        val low = TraitAllocation.of(3, 4, 3, 4, 5, 1)
        val high = TraitAllocation.of(3, 4, 3, 4, 5, 8)
        assertTrue(low.gatherYield < 1.0 && low.buildRate < 1.0, "a Logging-1 people were not worse off")
        assertTrue(high.gatherYield > 1.0 && high.buildRate > 1.0, "a Logging-8 people were not better off")
    }

    @Test
    fun `a logging people really does gather more wood`() {
        fun woodAt(logging: Int): Double {
            val sim = Simulation.newRun(
                RunConfig(seed = 909L, traits = TraitAllocation.of(3, 4, 3, 4, 5, logging), civCount = 1),
            )
            sim.runUnattended(6 * Time.DAYS_PER_YEAR)
            return sim.civ(0)[Resource.WOOD] + sim.civ(0)[Resource.STONE]
        }

        val plain = woodAt(TraitConfig.BASE_VALUE)
        val loggers = woodAt(8)
        assertTrue(
            loggers > plain,
            "a Logging-8 people gathered $loggers against a base people's $plain",
        )
    }

    @Test
    fun `five values still build a legal allocation, with logging at base`() {
        val five = TraitAllocation.of(5, 5, 5, 5, 5)
        assertEquals(TraitConfig.BASE_VALUE, five[Trait.LOGGING])
        assertEquals(10, five.pointsSpent, "the five-value form stopped spending the opening budget")
    }

    @Test
    fun `the sixth trait survives a save`() {
        val sim = Simulation.newRun(RunConfig(seed = 11L, traits = TraitAllocation.of(3, 4, 3, 4, 5, 8)))
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(8, reloaded.civ(0).traits[Trait.LOGGING])
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `a rival's opening allocation respects the opening cap, not the lifetime ceiling`() {
        // Two different numbers on purpose (AD-58). Passing one for both was how a rival could be
        // born with a trait no player could open with.
        val rng = SimRandom(4242L)
        repeat(200) {
            for (personality in Personality.entries) {
                val traits = RivalStrategist.allocate(personality, rng)
                for (trait in Trait.entries) {
                    assertTrue(
                        traits[trait] <= TraitConfig.ALLOCATION_MAX_PER_TRAIT,
                        "$personality opened with $trait at ${traits[trait]}",
                    )
                }
                assertEquals(TraitConfig.ALLOCATION_POINTS, traits.pointsSpent)
            }
        }
    }

    @Test
    fun `every personality has an appetite for the sixth trait`() {
        // A missing sixth weight would not be a compile error — the array would simply be short and
        // Logging would never be drawn. This is the test that would notice.
        val rng = SimRandom(5L)
        for (personality in Personality.entries) {
            val start = TraitAllocation.of(4, 4, 4, 4, 4, 4)
            val drew = (0 until 3_000).any {
                RivalStrategist.chooseGrowth(personality, start, rng) == Trait.LOGGING
            }
            assertTrue(drew, "$personality never once wanted to build anything")
        }
    }
}
