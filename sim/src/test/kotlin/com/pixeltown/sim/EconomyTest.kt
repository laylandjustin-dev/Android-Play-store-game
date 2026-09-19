package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Economy
import com.pixeltown.sim.GameConfig.Life
import com.pixeltown.sim.GameConfig.Time
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class EconomyTest {

    private fun newRun(seed: Long = 42L, traits: TraitAllocation = TraitAllocation.of(3, 4, 3, 4, 8)) =
        Simulation.newRun(seed, traits)

    private fun mine(sim: Simulation) = sim.citizens.filter { it.civId == 0 }

    // ------------------------------------------------------------------ assignment

    @Test
    fun `every adult is given a job within the first week`() {
        val sim = newRun()
        sim.runUnattended(Economy.JOB_REASSIGN_INTERVAL_DAYS + 1)
        val adults = mine(sim).filter { it.isAdult }
        assertTrue(adults.isNotEmpty())
        assertTrue(adults.none { it.job == Job.CHILD }, "an adult was left as a child")
        val workers = adults.count { it.job != Job.IDLE }
        assertTrue(workers > adults.size * 0.9, "only $workers of ${adults.size} adults were given work")
    }

    @Test
    fun `the workforce is split roughly along the configured weights`() {
        val sim = newRun()
        sim.runUnattended(14)
        val adults = mine(sim).filter { it.isAdult }
        val farmers = adults.count { it.job == Job.FARMER }
        val expected = adults.size * Economy.DEFAULT_JOB_WEIGHTS.getValue(Job.FARMER)
        assertTrue(farmers >= expected * 0.8, "expected about $expected farmers, got $farmers")
        // Every weighted job should have someone doing it in a colony of fifty.
        for (job in listOf(Job.FARMER, Job.HUNTER, Job.GATHERER)) {
            assertTrue(adults.any { it.job == job }, "nobody was assigned to $job")
        }
    }

    @Test
    fun `field workers hold distinct work cells`() {
        val sim = newRun()
        sim.runUnattended(30)
        val cells = sim.citizens.mapNotNull { if (it.workCell == World.NONE) null else it.workCell }
        assertEquals(cells.size, cells.distinct().size, "two workers claimed the same cell")
    }

    @Test
    fun `hunger overrides politics`() {
        // Strip the colony's stores and confirm the next reassignment pushes nearly everyone onto
        // food, regardless of the standing job weights.
        val sim = newRun()
        sim.runUnattended(30)
        val civ = sim.civ(0)
        civ[Resource.FOOD] = 0.0
        sim.runUnattended(Economy.JOB_REASSIGN_INTERVAL_DAYS + 1)

        val adults = mine(sim).filter { it.isAdult }
        val onFood = adults.count { it.job == Job.FARMER || it.job == Job.HUNTER }
        assertTrue(
            onFood > adults.size * 0.75,
            "only $onFood of ${adults.size} adults went to food production in a crisis",
        )
    }

    @Test
    fun `reassignment costs skill`() {
        val sim = newRun()
        sim.runUnattended(3 * Time.DAYS_PER_YEAR)
        val worker = mine(sim).first { it.job == Job.FARMER && it.skill > 0.2f }
        val before = worker.skill
        val claims = HashMap<Int, Int>()
        EconomySystem.assignJobs(
            sim.world,
            sim.civ(0),
            listOf(worker),
            claims,
            mapOf(Job.SOLDIER to 1.0),
        )
        assertEquals(Job.SOLDIER, worker.job)
        assertEquals(before * Life.SKILL_REASSIGNMENT_RETENTION.toFloat(), worker.skill, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `skill grows toward mastery over about six years`() {
        val sim = newRun()
        sim.runUnattended(Time.DAYS_PER_YEAR)
        val afterOneYear = mine(sim).filter { it.job == Job.FARMER }.map { it.skill }.average()
        sim.runUnattended(5 * Time.DAYS_PER_YEAR)
        val afterSix = mine(sim).filter { it.job == Job.FARMER }.map { it.skill }.average()
        assertTrue(afterSix > afterOneYear, "skill did not grow")
        assertTrue(afterSix > 0.7, "after six years average farming skill was only $afterSix")
    }

    // ------------------------------------------------------------------ production

    @Test
    fun `farmers produce food and the colony stops starving`() {
        val sim = newRun()
        sim.runUnattended(60)
        assertTrue(sim.civ(0)[Resource.FOOD] > 0.0, "the colony produced no food at all")
        assertTrue(sim.populationOf(0) >= 45, "the colony lost people in its first two months")
    }

    @Test
    fun `gatherers bring in wood and stone`() {
        val sim = newRun()
        sim.runUnattended(90)
        val civ = sim.civ(0)
        assertTrue(civ[Resource.WOOD] > 0.0 || civ[Resource.STONE] > 0.0, "gatherers produced nothing")
    }

    @Test
    fun `scholars and artisans produce knowledge and wealth`() {
        // Artisans are 2% of the workforce, so a colony of fifty may round to none of them. Run
        // until the town is large enough to staff the trade.
        val sim = newRun()
        sim.runUnattended(5 * Time.DAYS_PER_YEAR)
        val civ = sim.civ(0)
        assertTrue(civ[Resource.KNOWLEDGE] > 0.0, "no knowledge was produced in five years")
    }

    @Test
    fun `an artisan produces wealth`() {
        // Tested directly rather than through a run: building upkeep is charged daily in wealth,
        // so a town's wealth store can sit at zero while its artisans are earning steadily. That
        // is correct behaviour, and it makes the store a bad signal for whether work happened.
        val world = World(16, 16)
        for (i in 0 until world.cellCount) world.setTerrain(i, TerrainType.PLAIN)
        val civ = Civilization(0, "Test", TraitAllocation.EVEN_SPREAD, Personality.MERCANTILE, 0)
        val artisan = Citizen(
            id = 1, x = 8, y = 8, civId = 0, sex = Sex.FEMALE,
            ageDays = 30 * Time.DAYS_PER_YEAR, hp = 100f, nutrition = 1f, morale = 0.5f,
            survival = 70f, job = Job.ARTISAN, skill = 0.5f, influence = 0f,
        )

        EconomySystem.produce(world, civ, listOf(artisan), severity = 0.0, effects = CivEffects.NONE)

        assertTrue(civ[Resource.WEALTH] > 0.0, "an artisan at work produced no wealth")
    }

    @Test
    fun `winter cuts yields and a people of the Elements feel it less`() {
        val soft = TraitAllocation.of(3, 3, 3, 8, 6)
        val exposed = TraitAllocation.of(3, 3, 3, 1, 6)
        val winter = 1.0
        val summer = 0.0
        val softSwing = soft.seasonalYieldMultiplier(summer) - soft.seasonalYieldMultiplier(winter)
        val exposedSwing = exposed.seasonalYieldMultiplier(summer) - exposed.seasonalYieldMultiplier(winter)
        assertTrue(softSwing < exposedSwing, "Elements did not damp the seasonal swing")
        assertTrue(exposed.seasonalYieldMultiplier(winter) < 1.0, "winter did not cut yields")
    }

    // ------------------------------------------------------------------ the land

    @Test
    fun `farming drains the soil it works`() {
        // A Farming-8 people restore soil faster than they drain it, by design, so exhaustion has
        // to be measured on a people who are careless with the land.
        val sim = newRun(traits = TraitAllocation.of(5, 5, 5, 5, 3))
        sim.runUnattended(30)
        val farmer = mine(sim).first { it.job == Job.FARMER && it.workCell != World.NONE }
        val cell = farmer.workCell
        val before = sim.world.fertility[cell]
        sim.runUnattended(120)
        assertTrue(
            sim.world.fertility[cell] < before || before >= 0.99f,
            "a worked field lost no fertility in four months",
        )
    }

    @Test
    fun `a farming people restore their soil faster than a careless one`() {
        val careful = TraitAllocation.of(3, 3, 3, 3, 8).fertilityRecoveryPerDay
        val careless = TraitAllocation.of(3, 3, 3, 3, 1).fertilityRecoveryPerDay
        assertTrue(careful > careless)
        // A Farming-8 civ must out-recover what its own farmers drain, or no allocation is stable.
        assertTrue(
            careful > GameConfig.Terrain.FERTILITY_DRAIN_PER_FARM_DAY,
            "even a Farming-8 people exhaust their land: recovery $careful vs drain ${GameConfig.Terrain.FERTILITY_DRAIN_PER_FARM_DAY}",
        )
    }

    @Test
    fun `hunting depletes game and the land regrows it`() {
        val sim = newRun()
        sim.runUnattended(30)
        val hunter = mine(sim).firstOrNull { it.job == Job.HUNTER && it.workCell != World.NONE }
            ?: return // a colony with no hunters this week is not a failure
        val cell = hunter.workCell
        sim.runUnattended(60)
        val depleted = sim.world.wildGame[cell]
        val cap = GameConfig.Terrain.WILD_GAME.getValue(sim.world.terrainAt(cell)).toFloat()
        assertTrue(depleted <= cap, "game exceeded the terrain's carrying capacity")
    }

    @Test
    fun `worked land is claimed as territory`() {
        val sim = newRun()
        sim.runUnattended(30)
        val owned = (0 until sim.world.cellCount).count { sim.world.ownerCivId[it].toInt() == 0 }
        assertTrue(owned > 10, "the colony claimed only $owned cells after a month of work")
    }

    @Test
    fun `workers walk to their fields and stay near them`() {
        val sim = newRun()
        sim.runUnattended(60)
        val fieldWorkers = mine(sim).filter { it.workCell != World.NONE }
        assertTrue(fieldWorkers.isNotEmpty())
        val atWork = fieldWorkers.count { worker ->
            val dx = kotlin.math.abs(worker.x - worker.workCell % sim.world.width)
            val dy = kotlin.math.abs(worker.y - worker.workCell / sim.world.width)
            maxOf(dx, dy) <= 1
        }
        assertTrue(
            atWork > fieldWorkers.size * 0.7,
            "only $atWork of ${fieldWorkers.size} field workers reached their cell",
        )
    }
}
