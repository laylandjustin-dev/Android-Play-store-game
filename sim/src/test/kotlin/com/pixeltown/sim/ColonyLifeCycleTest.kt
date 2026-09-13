package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Life
import com.pixeltown.sim.GameConfig.Time
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ColonyLifeCycleTest {

    private val seeds = longArrayOf(1L, 42L, 555L, 20260913L, -7L)

    private fun newRun(seed: Long) = Simulation.newRun(seed, TraitAllocation.EVEN_SPREAD)

    // ------------------------------------------------------------------ founding

    @Test
    fun `a run founds fifty settlers for every civ`() {
        val sim = newRun(42L)
        assertEquals(GameConfig.World.TOTAL_CIV_COUNT, sim.civs.size)
        for (civ in sim.civs) {
            assertEquals(GameConfig.World.STARTING_SETTLERS, sim.populationOf(civ.id), "civ ${civ.id}")
        }
    }

    @Test
    fun `settlers are placed on walkable ground, one per cell`() {
        val sim = newRun(42L)
        assertOneOccupantPerCell(sim)
        for (citizen in sim.citizens) {
            val cell = sim.world.index(citizen.x, citizen.y)
            assertTrue(sim.world.isWalkable(cell), "citizen ${citizen.id} placed on unwalkable ground")
        }
    }

    @Test
    fun `settlers are founded as adults and some arrive partnered`() {
        val sim = newRun(42L)
        val settlers = sim.citizens.filter { it.civId == 0 }
        assertTrue(settlers.all { it.isAdult }, "a founding settler was a child")
        assertTrue(
            settlers.all { it.ageYears in GameConfig.World.SETTLER_MIN_AGE_YEARS..GameConfig.World.SETTLER_MAX_AGE_YEARS },
            "a settler was founded outside the documented age range",
        )
        assertTrue(settlers.count { it.partnerId != null } > 10, "nobody arrived partnered")
        // Partnerships are symmetric.
        for (citizen in settlers) {
            val partnerId = citizen.partnerId ?: continue
            assertEquals(citizen.id, sim.citizenOrNull(partnerId)?.partnerId, "one-sided partnership")
        }
    }

    // ------------------------------------------------------------------ collapse

    @Test
    fun `a colony that produces no food starves to death in a predictable window`() {
        // M2 has no jobs, so a colony lives on its founding stores and then dies. 50 settlers are
        // founded with 1,000 food and eat 50/day: ~20 days of full rations, ~8 days draining
        // nutrition to zero, then the starvation window. Extinction lands in the 40s and 50s.
        for (seed in seeds) {
            val sim = newRun(seed)
            val days = sim.runUntilEnd(maxDays = 2_000)
            assertEquals(0, sim.populationOf(0), "seed $seed still had people after $days days")
            assertTrue(days in 40..70, "seed $seed wiped out on day $days, expected 40..70")
        }
    }

    @Test
    fun `the same seed always collapses on exactly the same day`() {
        for (seed in seeds) {
            val first = newRun(seed).runUntilEnd(2_000)
            val second = newRun(seed).runUntilEnd(2_000)
            assertEquals(first, second, "seed $seed collapsed on different days across runs")
        }
    }

    @Test
    fun `collapse ends the run and is recorded`() {
        val sim = newRun(42L)
        sim.runUntilEnd(2_000)
        assertEquals(EndState.COLLAPSE, sim.endState)
        assertTrue(sim.chronicle.totalOf(ChronicleEventKind.RUN_ENDED) == 1)
    }

    @Test
    fun `nobody starves before the design allows, and starvation dominates the famine`() {
        val sim = newRun(42L)
        var firstStarvationDay = -1L
        var firstEmptyBellyDay = -1L
        while (sim.endState == null && sim.day < 2_000) {
            val before = sim.chronicle.deathsBy(DeathCause.STARVATION)
            sim.step()
            if (firstEmptyBellyDay < 0 && sim.citizens.any { it.nutrition <= 0f }) {
                firstEmptyBellyDay = sim.day
            }
            if (firstStarvationDay < 0 && sim.chronicle.deathsBy(DeathCause.STARVATION) > before) {
                firstStarvationDay = sim.day
            }
        }
        // The design allows no starvation death until 12 consecutive days at zero nutrition.
        assertTrue(firstEmptyBellyDay > 0, "nobody ever went hungry")
        // The first empty-bellied day counts as day one of the twelve, so the earliest legal
        // starvation death is 11 days after it.
        val daysAtZero = firstStarvationDay - firstEmptyBellyDay + 1
        assertTrue(
            daysAtZero >= Life.STARVATION_DAYS,
            "starvation killed after only $daysAtZero days at zero nutrition",
        )

        val breakdown = sim.chronicle.deathBreakdown()
        val starved = breakdown[DeathCause.STARVATION] ?: 0
        assertTrue(starved > breakdown.values.sum() * 0.8, "starvation was not the dominant cause: $breakdown")
    }

    @Test
    fun `the famine kills over days, not all at once`() {
        // Rationing is proportional, so every citizen is in an identical state — without a daily
        // roll past the starvation threshold, a whole colony died on one tick.
        val sim = newRun(42L)
        val deathsPerDay = HashMap<Long, Int>()
        while (sim.endState == null && sim.day < 2_000) {
            val before = sim.chronicle.totalOf(ChronicleEventKind.DEATH)
            sim.step()
            val died = sim.chronicle.totalOf(ChronicleEventKind.DEATH) - before
            if (died > 0) deathsPerDay[sim.day] = died
        }
        assertTrue(deathsPerDay.size >= 8, "the famine resolved in ${deathsPerDay.size} days, too abrupt")
    }

    // ------------------------------------------------------------------ determinism

    @Test
    fun `the same seed produces an identical history, checked every hundred days`() {
        for (seed in seeds) {
            val a = newRun(seed)
            val b = newRun(seed)
            repeat(20) {
                a.run(100)
                b.run(100)
                assertEquals(a.stateHash(), b.stateHash(), "seed $seed diverged by day ${a.day}")
            }
        }
    }

    @Test
    fun `different seeds produce different histories`() {
        val a = newRun(1L).also { it.run(30) }
        val b = newRun(2L).also { it.run(30) }
        assertTrue(a.stateHash() != b.stateHash())
    }

    // ------------------------------------------------------------------ a fed colony

    @Test
    fun `a fed colony grows, ages, and buries its old`() {
        // Food production arrives in M3; until then, hand-feed the colony to exercise birth,
        // ageing and old age in isolation.
        val sim = newRun(42L)
        val civ = sim.civ(0)
        var maxAgeSeen = 0
        repeat(80 * Time.DAYS_PER_YEAR) {
            civ[Resource.FOOD] = 5_000.0
            sim.step()
            maxAgeSeen = maxOf(maxAgeSeen, sim.citizens.filter { it.civId == 0 }.maxOfOrNull { it.ageDays } ?: 0)
        }

        assertTrue(civ.totalBirths > 0, "a well-fed colony produced no children")
        assertTrue(civ.totalDeaths > 0, "nobody died of old age in 80 years")
        assertTrue(sim.populationOf(0) > 0, "a well-fed colony died out")
        assertTrue(
            sim.chronicle.deathsBy(DeathCause.OLD_AGE) > 0,
            "no one reached old age: ${sim.chronicle.deathBreakdown()}",
        )

        // Nobody may outlive the hard cap of lifespan x 1.3.
        val cap = civ.traits.lifespanDays * Life.MAX_AGE_LIFESPAN_MULTIPLE
        assertTrue(maxAgeSeen <= cap, "someone reached $maxAgeSeen days, cap is $cap")
    }

    @Test
    fun `children are born, counted as children, and grow up`() {
        val sim = newRun(42L)
        val civ = sim.civ(0)
        repeat(Life.GESTATION_DAYS + 5 * Time.DAYS_PER_YEAR) {
            civ[Resource.FOOD] = 5_000.0
            sim.step()
        }
        val children = sim.citizens.filter { it.civId == 0 && it.isChild }
        assertTrue(children.isNotEmpty(), "no children after five well-fed years")
        assertTrue(children.all { it.job == Job.CHILD }, "a child was assigned adult work")
        assertTrue(
            children.all { it.dailyFoodNeed() == GameConfig.Economy.FOOD_PER_CHILD_PER_DAY },
            "a child ate an adult ration",
        )
    }

    @Test
    fun `pregnancy runs for the full gestation period`() {
        val sim = newRun(42L)
        val civ = sim.civ(0)
        var mother: Citizen? = null
        var conceivedOn = 0L
        while (mother == null && sim.day < 3 * Time.DAYS_PER_YEAR) {
            civ[Resource.FOOD] = 5_000.0
            sim.step()
            mother = sim.citizens.firstOrNull { it.isPregnant }
            if (mother != null) conceivedOn = sim.day
        }
        assertNotNull(mother, "nobody conceived in three well-fed years")
        assertEquals(conceivedOn.toInt() + Life.GESTATION_DAYS, mother.pregnantUntilDay)

        val populationBefore = sim.populationOf(0)
        while (sim.day < conceivedOn + Life.GESTATION_DAYS) {
            civ[Resource.FOOD] = 5_000.0
            sim.step()
        }
        assertNull(sim.citizenOrNull(mother.id)?.pregnantUntilDay, "the pregnancy did not resolve")
        assertTrue(sim.populationOf(0) > populationBefore, "the birth produced no child")
    }

    @Test
    fun `no two citizens ever occupy the same cell`() {
        val sim = newRun(42L)
        val civ = sim.civ(0)
        repeat(3 * Time.DAYS_PER_YEAR) {
            civ[Resource.FOOD] = 5_000.0
            sim.step()
            if (sim.day % 60 == 0L) assertOneOccupantPerCell(sim)
        }
    }

    @Test
    fun `the occupancy grid stays in step with the citizen list`() {
        val sim = newRun(42L)
        sim.run(45) // through the famine, so deaths have to clear their cells
        val occupied = (0 until sim.world.cellCount).count { sim.world.occupantId[it] != World.NONE }
        assertEquals(sim.population, occupied, "the grid and the population disagree")
    }

    // ------------------------------------------------------------------ survival score

    @Test
    fun `survival falls as a colony runs out of food`() {
        val sim = newRun(42L)
        sim.step()
        val wellFed = sim.citizens.filter { it.civId == 0 }.map { it.survival }.average()
        sim.run(30)
        val hungry = sim.citizens.filter { it.civId == 0 }.map { it.survival }.average()
        assertTrue(hungry < wellFed, "survival did not fall during a famine: $wellFed -> $hungry")
    }

    @Test
    fun `survival stays inside its documented range`() {
        val sim = newRun(42L)
        repeat(200) {
            sim.step()
            for (citizen in sim.citizens) {
                assertTrue(
                    citizen.survival >= GameConfig.Survival.MIN && citizen.survival <= GameConfig.Survival.MAX,
                    "survival ${citizen.survival} out of range",
                )
            }
        }
    }

    @Test
    fun `a hardier people survive the same famine longer`() {
        // Health raises max HP and Elements blunts the season penalty; both should show up as a
        // later collapse under identical conditions.
        val frail = Simulation.newRun(42L, TraitAllocation.of(6, 1, 6, 1, 6)).runUntilEnd(2_000)
        val hardy = Simulation.newRun(42L, TraitAllocation.of(1, 8, 1, 5, 5)).runUntilEnd(2_000)
        assertTrue(hardy > frail, "the hardy colony ($hardy days) did not outlast the frail one ($frail days)")
    }

    // ------------------------------------------------------------------ pairing

    @Test
    fun `a widow may not re-pair before the mourning period is over`() {
        val sim = newRun(42L)
        val civ = sim.civ(0)
        val widow = sim.citizens.first { it.partnerId != null }
        val partner = sim.citizenOrNull(widow.partnerId!!)!!

        // Age the partner past the hard cap of lifespan x 1.3 so they die of old age on the next
        // tick. Damaging their HP directly would not work: a fed body is healed before deaths are
        // resolved, which is the correct order for the simulation.
        partner.ageDays = (civ.traits.lifespanDays * Life.MAX_AGE_LIFESPAN_MULTIPLE).toInt() + 1
        civ[Resource.FOOD] = 5_000.0
        sim.step()

        assertNull(widow.partnerId, "the partnership survived the partner's death")
        assertEquals(sim.day.toInt(), widow.widowedOnDay)

        repeat(Life.WIDOW_REPAIR_DELAY_DAYS - 2) {
            civ[Resource.FOOD] = 5_000.0
            sim.step()
            assertNull(widow.partnerId, "a widow re-paired after only ${sim.day - widow.widowedOnDay!!} days")
        }
    }

    private fun assertOneOccupantPerCell(sim: Simulation) {
        val seen = HashSet<Int>()
        for (citizen in sim.citizens) {
            val cell = sim.world.index(citizen.x, citizen.y)
            assertTrue(seen.add(cell), "two citizens share cell $cell")
            assertEquals(citizen.id, sim.world.occupantId[cell], "the grid disagrees about cell $cell")
        }
    }
}
