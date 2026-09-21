package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Terrain as TerrainConfig
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every trait must offer a route past the food gate.
 *
 * The 1,026-run sweep measured a 157x spread between the best and worst single-trait build, with
 * five of six traits effectively dead: soil recovery answered to Farming alone and crossed the
 * drain between Farming 5 and 6, so a people that did not buy Farming died whatever else it bought.
 *
 * These tests assert the *mechanisms* that fixed it rather than the constants that tune them, so a
 * retune moves the numbers and a regression that removes a trait's route fails the build. See AD-79.
 */
class TraitViabilityTest {

    private fun sheet(
        speed: Int = TraitConfig.BASE_VALUE,
        health: Int = TraitConfig.BASE_VALUE,
        hunting: Int = TraitConfig.BASE_VALUE,
        elements: Int = TraitConfig.BASE_VALUE,
        farming: Int = TraitConfig.BASE_VALUE,
        gathering: Int = TraitConfig.BASE_VALUE,
    ) = TraitAllocation(speed, health, hunting, elements, farming, gathering)

    // ---------------------------------------------------------------- Elements

    @Test
    fun `Elements buys sustainable soil without buying yield`() {
        val base = sheet()
        val weathered = sheet(elements = TraitConfig.MAX_PER_TRAIT)

        assertTrue(
            weathered.fertilityRecoveryPerDay > base.fertilityRecoveryPerDay,
            "Elements did not reach soil recovery at all",
        )
        assertTrue(
            weathered.fertilityRecoveryPerDay > TerrainConfig.FERTILITY_DRAIN_PER_FARM_DAY,
            "a maximally weathered people still cannot sustain a worked field: " +
                "${weathered.fertilityRecoveryPerDay} against a drain of " +
                "${TerrainConfig.FERTILITY_DRAIN_PER_FARM_DAY}",
        )
        // And it is a different proposition from Farming, not a copy of it: the land keeps going,
        // but a cell of it produces no more than anyone else's.
        assertEquals(
            base.farmYield,
            weathered.farmYield,
            "Elements should not touch farm yield — that is Farming's half of the bargain",
        )
    }

    @Test
    fun `the baseline people still cannot sustain a worked field`() {
        // The whole risk in giving Elements a soil term is that it quietly makes every build
        // easier, which is a difficulty cut wearing a trait fix's clothes (AD-53). The drain moved
        // with it, so a people who chose nothing is exactly as marginal as before.
        assertTrue(
            TraitAllocation.BASE.fertilityRecoveryPerDay < TerrainConfig.FERTILITY_DRAIN_PER_FARM_DAY,
            "a people with no points spent now sustains worked land, which removes the constraint " +
                "the economy is built on",
        )
    }

    @Test
    fun `Elements keeps more of a harvest it cannot store`() {
        assertTrue(
            sheet(elements = TraitConfig.MAX_PER_TRAIT).spoilageMultiplier <
                TraitAllocation.BASE.spoilageMultiplier,
            "Elements does not reduce spoilage",
        )
        assertEquals(
            1.0,
            TraitAllocation.BASE.spoilageMultiplier,
            1e-9,
            "a base people must spoil food at exactly the documented rate, or every table moves",
        )
    }

    // ----------------------------------------------------------------- Hunting

    @Test
    fun `the food workforce leans to whichever the people are better at`() {
        val agenda = Agenda.balanced()
        fun farmerShare(traits: TraitAllocation): Double {
            val split = CouncilSystem.foodSplitOf(traits)
            return split.farming / (split.farming + split.hunting)
        }

        val hunters = farmerShare(sheet(hunting = TraitConfig.MAX_PER_TRAIT))
        val even = farmerShare(sheet())
        val farmers = farmerShare(sheet(farming = TraitConfig.MAX_PER_TRAIT))

        assertTrue(
            hunters < even && even < farmers,
            "the split did not follow the trait sheet: hunting-heavy $hunters, even $even, " +
                "farming-heavy $farmers",
        )
        // A hunting people must actually hunt most of its food, or the trait is still decoration.
        assertTrue(hunters < 0.5, "a Hunting-max people still farms the majority of its food: $hunters")
    }

    @Test
    fun `neither food source is ever abandoned entirely`() {
        // Game depletes where soil recovers, so a town that only hunts strips its range and has no
        // fallback. The lean is bounded at both ends for that reason.
        for (traits in listOf(
            sheet(hunting = TraitConfig.MAX_PER_TRAIT, farming = TraitConfig.MIN_PER_TRAIT),
            sheet(farming = TraitConfig.MAX_PER_TRAIT, hunting = TraitConfig.MIN_PER_TRAIT),
        )) {
            val split = CouncilSystem.foodSplitOf(traits)
            assertTrue(split.farming > 0.0, "no farmers at all for $traits")
            assertTrue(split.hunting > 0.0, "no hunters at all for $traits")
        }
    }

    @Test
    fun `an unspecialised people splits its food work exactly as it always did`() {
        val w = CouncilSystem.jobWeightsFor(Agenda.balanced(), buildingInProgress = false)
        val farmers = w[Job.FARMER] ?: 0.0
        val hunters = w[Job.HUNTER] ?: 0.0
        assertEquals(
            0.70,
            farmers / (farmers + hunters),
            1e-9,
            "the baseline farm share moved, which invalidates every balance table in CLAUDE.md",
        )
    }

    // --------------------------------------------------------------- Gathering

    @Test
    fun `a gathering people staffs the woods, not just works them well`() {
        // Foraging talent is useless if six percent of the town does it. The share of the food
        // workforce that forages has to rise with the trait, or the route is theoretical.
        val base = CouncilSystem.foodSplitOf(sheet())
        val gatherers = CouncilSystem.foodSplitOf(sheet(gathering = TraitConfig.MAX_PER_TRAIT))
        assertEquals(0.0, base.foraging, 1e-9, "an unspecialised people should not forage at all")
        assertTrue(
            gatherers.foraging > 0.25,
            "a Gathering-max people sends only ${gatherers.foraging} of its food workers foraging",
        )
        for (split in listOf(base, gatherers)) {
            assertEquals(
                1.0,
                split.farming + split.hunting + split.foraging,
                1e-9,
                "the food split does not account for every food worker: $split",
            )
        }
    }

    @Test
    fun `gatherers bring back food as well as materials`() {
        // Gathering drove timber, stone and build rate and touched no food at all, so a people
        // built for it starved holding the materials for a granary.
        val sim = Simulation.newRun(1L, sheet(gathering = TraitConfig.MAX_PER_TRAIT), civCount = 1)
        sim.runUnattended(3 * Time.DAYS_PER_YEAR)
        val civ = sim.civ(0)
        assertTrue(
            civ.produced[Resource.FOOD.ordinal] > 0.0,
            "a gathering people produced no food in three years",
        )
        assertTrue(
            sim.populationOf(0) > 0,
            "a Gathering-max people is still extinct within three years",
        )
    }

    // ------------------------------------------------------------------ Health

    @Test
    fun `Health is a real cut in what a people eats`() {
        val hardy = sheet(health = TraitConfig.MAX_PER_TRAIT).rationMultiplier
        assertTrue(
            hardy <= 0.75,
            "a maximally healthy people still eats $hardy of a standard ration, which is too close " +
                "to the baseline to be a strategy",
        )
        assertEquals(
            1.0,
            TraitAllocation.BASE.rationMultiplier,
            1e-9,
            "a base people must eat exactly the standard ration",
        )
    }

    // ------------------------------------------------------------- the spread

    @Test
    fun `no trait is the only door to a sustainable field`() {
        // The headline failure: recovery answered to one trait, so five of six were decoration.
        // Whatever the tuning, more than one trait must be able to carry a people over the drain.
        val carriers = Trait.entries.filter { trait ->
            val maxed = TraitAllocation.BASE.let {
                var a = it
                while (a[trait] < TraitConfig.MAX_PER_TRAIT) a = a.withPointIn(trait)!!
                a
            }
            maxed.fertilityRecoveryPerDay > TerrainConfig.FERTILITY_DRAIN_PER_FARM_DAY
        }
        assertTrue(
            carriers.size >= 2,
            "only $carriers can sustain worked land, so every other trait is still a decoration",
        )
    }
}
