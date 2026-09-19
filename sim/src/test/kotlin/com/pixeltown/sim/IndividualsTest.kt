package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** People are individuals, and what they are makes a difference. */
class IndividualsTest {

    private fun newRun(seed: Long = 1_000L) =
        Simulation.newRun(RunConfig(seed = seed, traits = TraitAllocation.of(3, 4, 3, 4, 8)))

    @Test
    fun `a colony is founded with the documented number of settlers`() {
        val sim = newRun()
        assertEquals(55, WorldConfig.STARTING_SETTLERS)
        assertEquals(WorldConfig.STARTING_SETTLERS, sim.populationOf(WorldConfig.PLAYER_CIV_ID))
    }

    @Test
    fun `settlers vary, and every one of them is inside the range`() {
        val sim = newRun()
        val vigours = sim.citizens.filter { it.civId == 0 }.map { it.vigour }
        assertTrue(vigours.distinct().size > 20, "a town of fifty-five had ${vigours.distinct().size} builds")
        for (v in vigours) {
            assertTrue(
                v >= TraitConfig.VIGOUR_MIN.toFloat() && v <= TraitConfig.VIGOUR_MAX.toFloat(),
                "vigour $v is outside the documented range",
            )
        }
    }

    @Test
    fun `vigour moves work output and fighting strength together`() {
        val traits = TraitAllocation.of(5, 5, 5, 5, 5)
        fun person(vigour: Float) = Citizen(
            id = 1, x = 0, y = 0, civId = 0, sex = Sex.MALE,
            ageDays = 25 * Time.DAYS_PER_YEAR, hp = traits.maxHp, nutrition = 1f, morale = 0.7f,
            survival = 80f, job = Job.FARMER, skill = 0.5f, influence = 0f,
        ).also { it.vigour = vigour }

        val weak = person(TraitConfig.VIGOUR_MIN.toFloat())
        val strong = person(TraitConfig.VIGOUR_MAX.toFloat())

        assertTrue(strong.effectiveness() > weak.effectiveness(), "vigour did not reach work output")
        assertTrue(strong.strength(traits) > weak.strength(traits), "vigour did not reach fighting strength")
        // One constitution, not two unrelated numbers: the ratio is the same in both.
        val workRatio = strong.effectiveness() / weak.effectiveness()
        val fightRatio = strong.strength(traits) / weak.strength(traits)
        assertEquals(workRatio, fightRatio, 0.001)
    }

    @Test
    fun `a hungry or wounded person does less, and a child less again`() {
        val traits = TraitAllocation.of(5, 5, 5, 5, 5)
        fun person(nutrition: Float, hp: Float, age: Int, job: Job = Job.FARMER) = Citizen(
            id = 1, x = 0, y = 0, civId = 0, sex = Sex.MALE, ageDays = age * Time.DAYS_PER_YEAR,
            hp = hp, nutrition = nutrition, morale = 0.7f, survival = 80f, job = job,
            skill = 0.5f, influence = 0f,
        )

        val well = person(1f, traits.maxHp, 25)
        val hungry = person(0.2f, traits.maxHp, 25)
        val wounded = person(1f, traits.maxHp * 0.3f, 25)
        val child = person(1f, traits.maxHp, 8)

        assertTrue(hungry.effectiveness() < well.effectiveness(), "hunger did not slow anybody down")
        assertTrue(wounded.strength(traits) < well.strength(traits), "a wound did not weaken anybody")
        assertTrue(child.effectiveness() < well.effectiveness(), "a child worked like an adult")
    }

    @Test
    fun `the old and the very young are not soldiers`() {
        val traits = TraitAllocation.of(5, 5, 5, 5, 5)
        fun at(age: Int) = Citizen(
            id = 1, x = 0, y = 0, civId = 0, sex = Sex.MALE, ageDays = age * Time.DAYS_PER_YEAR,
            hp = traits.maxHp, nutrition = 1f, morale = 0.7f, survival = 80f, job = Job.SOLDIER,
            skill = 1f, influence = 0f,
        ).strength(traits)

        assertTrue(at(30) > at(8), "a child fought as well as an adult")
        assertTrue(at(30) > at(70), "a seventy-year-old fought as well as someone in their prime")
        assertTrue(at(30) >= at(20), "someone in their prime was out-fought by an adolescent")
    }

    @Test
    fun `children inherit their parents' constitution, pulled back toward the average`() {
        val sim = newRun()
        sim.runUnattended(40 * Time.DAYS_PER_YEAR)
        val born = sim.citizens.filter { it.civId == 0 && it.ageYears < 30 }
        if (born.size < 10) return

        // Regression to the mean: a town's children cluster nearer 1.0 than a uniform draw would.
        val mean = born.map { it.vigour }.average()
        assertTrue(mean > 0.9 && mean < 1.1, "the generations drifted to a mean of $mean")
        for (c in born) {
            assertTrue(c.vigour >= TraitConfig.VIGOUR_MIN.toFloat() && c.vigour <= TraitConfig.VIGOUR_MAX.toFloat())
        }
    }

    @Test
    fun `an individual survives a save`() {
        val sim = newRun()
        sim.runUnattended(5 * Time.DAYS_PER_YEAR)
        val before = sim.citizens.associate { it.id to it.vigour }

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        for (c in reloaded.citizens) {
            assertEquals(before[c.id], c.vigour, "citizen ${c.id} came back a different person")
        }
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `a save from before people were individuals still loads`() {
        val sim = newRun()
        sim.runUnattended(200)
        val text = SaveFormat.encode(sim.snapshot())
        val stripped = Regex("\"vigour\":[0-9.E-]+,").replace(text, "")
        assertTrue(stripped.length < text.length, "there was no vigour field to remove")
        val reloaded = Simulation.restore(SaveFormat.decode(stripped))
        assertTrue(reloaded.citizens.all { it.vigour == 1f }, "a defaulted citizen was not average")
    }
}

/** The standing instruction a player leaves their town. */
class CharterTest {

    private fun newRun() =
        Simulation.newRun(RunConfig(seed = 1_000L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))

    private fun withInfluence(sim: Simulation): Simulation {
        sim.civ(0).influencePoints = 5_000.0
        return sim
    }

    @Test
    fun `a charter costs influence and shows up on the sitting Premier`() {
        val sim = withInfluence(newRun())
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)

        val before = sim.premierOf(0)?.agenda?.get(BuildingCategory.TECH) ?: return
        val influenceBefore = sim.civ(0).influencePoints
        assertTrue(sim.setCharter(0, BuildingCategory.TECH))

        assertEquals(BuildingCategory.TECH, sim.civ(0).charter)
        assertTrue(sim.civ(0).influencePoints < influenceBefore, "a charter was free")
        assertTrue(
            (sim.premierOf(0)?.agenda?.get(BuildingCategory.TECH) ?: 0.0) > before,
            "the sitting Premier ignored the charter",
        )
    }

    @Test
    fun `it outlives the Premier who was in office`() {
        val sim = withInfluence(newRun())
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        assertTrue(sim.setCharter(0, BuildingCategory.TECH))

        val firstPremier = sim.premierOf(0)?.name
        sim.runUnattended(12 * Time.DAYS_PER_YEAR)
        assertTrue(sim.premierOf(0)?.name != firstPremier, "nobody new was ever elected")

        assertEquals(BuildingCategory.TECH, sim.civ(0).charter, "the charter died with its Premier")
        // A charter is a nudge, not a command: it has to be visible in the platform, not total.
        val weight = sim.premierOf(0)?.agenda?.get(BuildingCategory.TECH) ?: 0.0
        assertTrue(weight > 0.0, "a chartered category carried no weight at all")
        assertTrue(weight < 1.0, "the charter replaced the Premier's platform entirely")
    }

    @Test
    fun `changing it does not compound`() {
        val sim = withInfluence(newRun())
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        if (sim.premierOf(0) == null) return

        assertTrue(sim.setCharter(0, BuildingCategory.TECH))
        val chartered = sim.premierOf(0)!!.agenda[BuildingCategory.TECH]
        assertTrue(sim.setCharter(0, BuildingCategory.HEALTH))
        val afterSwap = sim.premierOf(0)!!.agenda[BuildingCategory.TECH]

        assertTrue(afterSwap < chartered, "the old charter's weight was never taken back out")
        assertEquals(BuildingCategory.HEALTH, sim.civ(0).charter)
    }

    @Test
    fun `it can be revoked, and revoking is free`() {
        val sim = withInfluence(newRun())
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        assertTrue(sim.setCharter(0, BuildingCategory.MILITARY))

        val influence = sim.civ(0).influencePoints
        assertTrue(sim.setCharter(0, null))
        assertEquals(null, sim.civ(0).charter)
        assertEquals(influence, sim.civ(0).influencePoints, "revoking a charter cost something")
    }

    @Test
    fun `setting the charter it already has is refused`() {
        val sim = withInfluence(newRun())
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        assertTrue(sim.setCharter(0, BuildingCategory.FARMS))
        assertFalse(sim.setCharter(0, BuildingCategory.FARMS), "paid twice for the same charter")
    }

    @Test
    fun `a charter survives a save`() {
        val sim = withInfluence(newRun())
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        assertTrue(sim.setCharter(0, BuildingCategory.LIFESTYLE))

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(BuildingCategory.LIFESTYLE, reloaded.civ(0).charter)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `every category can be petitioned, not just two of them`() {
        val sim = withInfluence(newRun())
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        if (sim.premierOf(0) == null) return
        for (category in BuildingCategory.entries) {
            assertTrue(sim.petition(0, category, 0.35), "$category could not be petitioned")
        }
    }
}
