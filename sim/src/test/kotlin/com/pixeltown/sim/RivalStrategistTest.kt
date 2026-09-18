package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Rivals as RivalConfig
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RivalStrategistTest {

    private fun sample(personality: Personality, count: Int = 60, seed: Long = 5L) =
        SimRandom(seed).let { rng -> (1..count).map { RivalStrategist.allocate(personality, rng) } }

    @Test
    fun `every allocation is legal and spends the whole budget`() {
        for (personality in Personality.entries) {
            for (build in sample(personality)) {
                assertEquals(TraitConfig.ALLOCATION_POINTS, build.pointsSpent, "$personality spent the wrong budget")
                for (value in build.values) {
                    assertTrue(
                        value in TraitConfig.MIN_PER_TRAIT..TraitConfig.MAX_PER_TRAIT,
                        "$personality produced an out-of-range trait: ${build.values.toList()}",
                    )
                }
            }
        }
    }

    @Test
    fun `no rival founds itself unable to eat`() {
        // The measured line: Farming 3 dies inside two years, Farming 1 never reaches year one.
        for (personality in Personality.entries) {
            for (build in sample(personality)) {
                assertTrue(
                    build.farming >= RivalConfig.MIN_VIABLE_FARMING,
                    "$personality founded on Farming ${build.farming}, below the viability floor",
                )
            }
        }
    }

    @Test
    fun `each personality builds in character`() {
        fun mean(builds: List<TraitAllocation>, of: (TraitAllocation) -> Int) = builds.map(of).average()

        val militant = sample(Personality.MILITANT)
        val mercantile = sample(Personality.MERCANTILE)
        val expansionist = sample(Personality.EXPANSIONIST)
        val isolationist = sample(Personality.ISOLATIONIST)

        // Hunting doubles as military effectiveness, so it is the militant's trait.
        assertTrue(
            mean(militant) { it.hunting } > mean(mercantile) { it.hunting },
            "militants were no more warlike than merchants",
        )
        assertTrue(
            mean(militant) { it.hunting } > mean(isolationist) { it.hunting },
            "militants were no more warlike than isolationists",
        )
        // A merchant's whole strategy is surplus worth trading.
        assertTrue(
            mean(mercantile) { it.farming } > mean(militant) { it.farming },
            "merchants farmed no harder than militants",
        )
        // Isolationists answer to the weather, not the neighbours.
        assertTrue(
            mean(isolationist) { it.elements } > mean(militant) { it.elements },
            "isolationists were no better prepared for winter",
        )
        // Expansionists grow: bodies and lifespan.
        assertTrue(
            mean(expansionist) { it.health } > mean(mercantile) { it.health },
            "expansionists were no hardier than merchants",
        )
    }

    @Test
    fun `builds vary within a personality rather than repeating a template`() {
        for (personality in Personality.entries) {
            val distinct = sample(personality).map { it.values.toList() }.distinct().size
            assertTrue(distinct >= 10, "$personality produced only $distinct distinct builds in 60 draws")
        }
    }

    @Test
    fun `a set of rivals contains no duplicates`() {
        val rng = SimRandom(3L)
        repeat(40) {
            val personalities = List(GameConfig.World.RIVAL_CIV_COUNT) {
                Personality.entries[rng.nextInt(Personality.entries.size)]
            }
            val builds = RivalStrategist.allocateAll(personalities, rng).map { it.values.toList() }
            assertEquals(builds.size, builds.distinct().size, "two rivals were founded with identical traits")
        }
    }

    @Test
    fun `allocation is deterministic for a given seed`() {
        val a = SimRandom(77L).let { rng -> Personality.entries.map { RivalStrategist.allocate(it, rng).values.toList() } }
        val b = SimRandom(77L).let { rng -> Personality.entries.map { RivalStrategist.allocate(it, rng).values.toList() } }
        assertEquals(a, b)
    }

    @Test
    fun `the player's allocation is their own and nothing chooses it for them`() {
        val chosen = TraitAllocation.of(8, 1, 2, 4, 5)
        val sim = Simulation.newRun(1L, chosen)
        assertEquals(chosen, sim.civ(GameConfig.World.PLAYER_CIV_ID).traits)
        // ...and the rivals did not copy it.
        assertTrue(
            sim.civs.drop(1).none { it.traits == chosen },
            "a rival was founded with the player's exact allocation",
        )
    }

    @Test
    fun `rivals still tend to be alive a century and a half in`() {
        // An opponent that starves in year two is an opponent deleted before the player meets it.
        var alive = 0
        var total = 0
        for (seed in longArrayOf(1L, 42L, 555L)) {
            val sim = Simulation.newRun(seed, TraitAllocation.of(3, 4, 3, 4, 8))
            sim.run(150 * GameConfig.Time.DAYS_PER_YEAR)
            alive += sim.civs.drop(1).count { !it.isExtinct }
            total += sim.civs.size - 1
        }
        assertTrue(alive >= total / 3, "only $alive of $total rivals survived 150 years")
    }
}
