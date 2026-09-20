package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Economy
import com.pixeltown.sim.GameConfig.Politics as PoliticsConfig
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** The resource ledger behind the end-of-run breakdown. */
class ResourceLedgerTest {

    private fun newRun() = Simulation.newRun(
        RunConfig(seed = 1_000L, traits = TraitAllocation.of(3, 4, 3, 4, 8), civCount = 1),
    )

    @Test
    fun `the founding stores are not counted as something the town produced`() {
        // They were carried ashore. A ledger that calls them production would tell the player they
        // farmed 1,870 food on their first morning.
        val sim = newRun()
        val civ = sim.civ(0)
        assertEquals(
            WorldConfig.STARTING_SETTLERS * Economy.STARTING_FOOD_PER_SETTLER,
            civ[Resource.FOOD],
            0.001,
        )
        assertEquals(0.0, civ.produced[Resource.FOOD.ordinal], 0.001, "the landing was booked as a harvest")
    }

    @Test
    fun `producing and spending are both counted`() {
        val sim = newRun()
        sim.runUnattended(2 * Time.DAYS_PER_YEAR)
        val civ = sim.civ(0)

        assertTrue(civ.produced[Resource.FOOD.ordinal] > 0.0, "two years of farming produced no food")
        assertTrue(civ.consumed[Resource.FOOD.ordinal] > 0.0, "nobody ate anything in two years")
        // Wood is gathered and spent on building, so both sides of its ledger should move.
        assertTrue(civ.produced[Resource.WOOD.ordinal] > 0.0, "nothing was ever gathered")
    }

    @Test
    fun `spoilage is booked separately from consumption`() {
        // Food that rots is not food anybody got the good of, and a breakdown that conflated them
        // would tell the player their town ate its way through a surplus it actually lost.
        val sim = newRun()
        val civ = sim.civ(0)
        civ[Resource.FOOD] = sim.foodCapacityOf(0) * 3
        val consumedBefore = civ.consumed[Resource.FOOD.ordinal]
        sim.runUnattended(20)

        assertTrue(civ.spoiled > 0.0, "three times capacity did not rot at all")
        val eaten = civ.consumed[Resource.FOOD.ordinal] - consumedBefore
        assertTrue(
            eaten < civ.spoiled * 20,
            "spoilage looks like it was counted as eating",
        )
    }

    @Test
    fun `the ledger reaches the run summary and survives a save`() {
        val sim = newRun()
        sim.runUnattended(5 * Time.DAYS_PER_YEAR)
        val produced = sim.civ(0).produced.toList()

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(produced, reloaded.civ(0).produced.toList())
        assertEquals(sim.civ(0).spoiled, reloaded.civ(0).spoiled, 0.001)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `a save from before the ledger existed still loads`() {
        val sim = newRun()
        sim.runUnattended(200)
        val text = SaveFormat.encode(sim.snapshot())
        val stripped = Regex(",\"produced\":\\[[^]]*]").replace(text, "")
        assertTrue(stripped.length < text.length, "there was no ledger to remove")
        val reloaded = Simulation.restore(SaveFormat.decode(stripped))
        assertTrue(reloaded.civ(0).produced.all { it == 0.0 }, "an old save invented a ledger")
    }
}

/** What the land is worth, told before landing rather than after. */
class SiteSurveyTest {

    private fun world(): Pair<World, BooleanArray> {
        val generated = WorldGenerator.generate(SimRandom(1_000L))
        return generated.world to WorldGenerator.legalStartSites(generated.world)
    }

    @Test
    fun `a survey agrees with the generator's own scoring`() {
        // The whole point is to show the player what the generator can already see. If the two
        // disagree, the screen is lying about the run that follows it.
        val (w, legal) = world()
        val best = SiteSurvey.bestScore(w, legal)
        assertTrue(best > 0.0)

        val cells = (0 until w.cellCount).filter { legal[it] }
        val richest = cells.maxBy { SiteSurvey.of(w, it, true, best).score }
        assertEquals(1.0, SiteSurvey.of(w, richest, true, best).shareOfBest, 0.001)
    }

    @Test
    fun `rich and poor ground read differently`() {
        val (w, legal) = world()
        val best = SiteSurvey.bestScore(w, legal)
        val cells = (0 until w.cellCount).filter { legal[it] }
        val surveys = cells.map { SiteSurvey.of(w, it, true, best) }

        val worst = surveys.minBy { it.score }
        val richest = surveys.maxBy { it.score }
        assertTrue(richest.farmland > worst.farmland, "the best site had no more farmland than the worst")
        assertTrue(richest.rating != worst.rating, "both ends of the island read the same")
        assertEquals("prime", richest.rating)

        // And the figure a player actually compares against fifty-five settlers.
        val traits = TraitAllocation.of(3, 4, 3, 4, 8)
        assertTrue(
            richest.feedsAbout(traits) > worst.feedsAbout(traits),
            "richer land did not feed more people",
        )
    }

    @Test
    fun `an illegal cell is reported as unbuildable rather than merely poor`() {
        val (w, legal) = world()
        val ocean = (0 until w.cellCount).first { !legal[it] }
        assertEquals("unbuildable", SiteSurvey.of(w, ocean, false, 1.0).rating)
    }

    @Test
    fun `a survey has no randomness in it`() {
        val (w, legal) = world()
        val best = SiteSurvey.bestScore(w, legal)
        val cell = (0 until w.cellCount).first { legal[it] }
        assertEquals(SiteSurvey.of(w, cell, true, best), SiteSurvey.of(w, cell, true, best))
    }
}

/** What a point actually buys, read off the simulation rather than described in prose. */
class TraitEffectsTest {

    @Test
    fun `every trait has something to show, and it changes`() {
        val traits = TraitAllocation.of(4, 4, 4, 4, 4, 4)
        for (trait in Trait.entries) {
            val rows = TraitEffects.of(traits, trait)
            assertTrue(rows.isNotEmpty(), "$trait had nothing to say for itself")
            assertTrue(rows.any { it.changes }, "$trait's next point changed nothing at all")
        }
    }

    @Test
    fun `the numbers are the simulation's, not a description of it`() {
        val traits = TraitAllocation.of(4, 4, 4, 4, 4, 4)
        val grown = traits.withPointIn(Trait.FARMING)!!
        val row = TraitEffects.of(traits, Trait.FARMING).first { it.label == "Farm yield" }
        assertEquals(traits.farmYield, row.from, 1e-9)
        assertEquals(grown.farmYield, row.to, 1e-9)
    }

    @Test
    fun `a trait that lowers a cost is marked as such`() {
        val traits = TraitAllocation.of(4, 4, 4, 4, 4, 4)
        val ration = TraitEffects.of(traits, Trait.HEALTH).first { it.label == "Food eaten" }
        assertTrue(ration.lowerIsBetter, "eating less was presented as a loss")
        assertTrue(ration.to < ration.from)
        assertTrue(ration.percentChange < 0)
    }

    @Test
    fun `a maxed trait offers no rows rather than misleading ones`() {
        val top = GameConfig.Traits.MAX_PER_TRAIT
        val maxed = TraitAllocation.of(top, top, top, top, top, top)
        for (trait in Trait.entries) {
            assertTrue(TraitEffects.of(maxed, trait).isEmpty(), "$trait promised a point it cannot take")
        }
    }
}

/** An election is a decision, so the world waits for it (item 5). */
class ElectionPauseTest {

    private fun newRun() =
        Simulation.newRun(RunConfig(seed = 1_000L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))

    /**
     * Runs to the first campaign, answering the other two pauses on the way.
     *
     * The window is two terms, not a fixed number of years: the town votes every
     * [PoliticsConfig.TERM_YEARS] years, so a three-year search found no election at all once the
     * term stopped being annual.
     */
    private fun runToCampaign(sim: Simulation): Boolean {
        repeat((PoliticsConfig.FIRST_ELECTION_YEAR + PoliticsConfig.TERM_YEARS) * Time.DAYS_PER_YEAR) {
            if (sim.endState != null) return false
            if (sim.electionPending) return true
            val player = WorldConfig.PLAYER_CIV_ID
            sim.pendingTechChoices(player).firstOrNull()?.let { sim.chooseTech(player, it) }
            while (sim.pendingTraitPoints(player) > 0) {
                val t = sim.civ(player).traits.improvable.firstOrNull() ?: break
                if (!sim.spendTraitPoint(player, t)) break
            }
            sim.step()
        }
        return sim.electionPending
    }

    @Test
    fun `the campaign stops the clock until the player has seen the slate`() {
        // At 10x the thirty-day window is three seconds: the slate appeared and the vote was counted
        // before a player could read one candidate's pitch.
        val sim = newRun()
        assertTrue(runToCampaign(sim), "no election opened in three years")
        assertTrue(sim.awaitingPlayer, "the campaign did not stop the clock")

        val day = sim.day
        sim.run(30)
        assertEquals(day, sim.day, "the world moved on while the election was waiting")
        assertTrue(sim.campaignFor(0)?.isNotEmpty() == true, "there was no slate to look at")
    }

    @Test
    fun `skipping is free, and the world resumes`() {
        val sim = newRun()
        assertTrue(runToCampaign(sim))
        val influence = sim.civ(0).influencePoints

        assertTrue(sim.acknowledgeElection(), "there was nothing to acknowledge")
        assertEquals(influence, sim.civ(0).influencePoints, 0.001, "staying out of it cost influence")
        assertFalse(sim.electionPending)

        val day = sim.day
        sim.run(5)
        assertTrue(sim.day > day, "the world did not resume")
    }

    @Test
    fun `the pause does not come back for the same campaign`() {
        val sim = newRun()
        assertTrue(runToCampaign(sim))
        assertTrue(sim.acknowledgeElection())
        // Run out the rest of the campaign window and through the vote.
        sim.run(PoliticsConfig.CAMPAIGN_DAYS + 2)
        assertFalse(sim.electionPending, "the same election asked twice")
    }

    @Test
    fun `an unattended run never waits for an election`() {
        val sim = newRun()
        sim.runUnattended(3 * Time.DAYS_PER_YEAR)
        assertEquals(3 * Time.DAYS_PER_YEAR, sim.day.toInt(), "an unattended run stopped for an election")
    }

    @Test
    fun `a pending election survives a save`() {
        val sim = newRun()
        assertTrue(runToCampaign(sim))
        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertTrue(reloaded.electionPending, "the reloaded run forgot the election was waiting")
        assertTrue(reloaded.awaitingPlayer)
    }
}

/** The rival outcomes the finish screen needs as a yardstick. */
class RivalOutcomeTest {

    @Test
    fun `a finished run reports every rival and what they were built for`() {
        val sim = Simulation.newRun(
            RunConfig(seed = 5L, traits = TraitAllocation.of(8, 3, 3, 3, 1)),
        )
        sim.runUnattendedUntilEnd(80 * Time.DAYS_PER_YEAR)
        val summary = sim.summary() ?: return

        assertEquals(WorldConfig.TOTAL_CIV_COUNT - 1, summary.rivals.size)
        for (rival in summary.rivals) {
            assertTrue(rival.name.isNotEmpty())
            assertEquals(GameConfig.Traits.ALLOCATION_POINTS, rival.traits.pointsSpent - rival.growthSpent())
            assertTrue(rival.peakPopulation >= rival.population)
        }
        assertEquals(summary.rivals.count { it.extinct }, summary.rivalsExtinct)
        assertEquals(summary.rivals.count { !it.extinct }, summary.rivalsSurviving)
    }

    /** Points a rival earned after founding, which is everything above the opening budget. */
    private fun RivalOutcome.growthSpent(): Int =
        (traits.pointsSpent - GameConfig.Traits.ALLOCATION_POINTS).coerceAtLeast(0)
}
