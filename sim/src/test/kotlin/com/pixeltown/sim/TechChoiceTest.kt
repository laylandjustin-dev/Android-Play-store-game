package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** Reaching a tier is a decision, and the world waits for it. */
class TechChoiceTest {

    private fun newRun(seed: Long = 1_000L) =
        Simulation.newRun(RunConfig(seed = seed, traits = TraitAllocation.of(3, 4, 3, 4, 8)))

    /**
     * Runs until the player is offered a *tech* choice, or gives up.
     *
     * A decade's trait point also stops the clock (AD-59), and it arrives long before the first
     * tier, so this spends those as it goes. Without that the helper returned at year ten with an
     * empty option list and four tests failed on the wrong thing entirely.
     */
    private fun runToChoice(sim: Simulation, maxYears: Int = 200): Boolean {
        repeat(maxYears * Time.DAYS_PER_YEAR) {
            if (sim.endState != null) return false
            if (sim.civ(WorldConfig.PLAYER_CIV_ID).pendingTechTier != null) return true
            while (sim.pendingTraitPoints(WorldConfig.PLAYER_CIV_ID) > 0) {
                val trait = sim.needBasedGrowth(sim.civ(WorldConfig.PLAYER_CIV_ID)) ?: break
                if (!sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, trait)) break
            }
            if (!sim.step()) return sim.civ(WorldConfig.PLAYER_CIV_ID).pendingTechTier != null
        }
        return sim.civ(WorldConfig.PLAYER_CIV_ID).pendingTechTier != null
    }

    @Test
    fun `every tier offers exactly three options`() {
        for (tier in 1..GameConfig.Tech.MAX_TIER) {
            val options = TechOption.forTier(tier)
            assertEquals(3, options.size, "tier $tier offers ${options.size}")
            assertEquals(options.size, options.map { it.label }.distinct().size)
            // Each option has to actually do something, or it is not a choice.
            for (option in options) assertNotEquals(TechEffects(), option.effects, "${option.label} does nothing")
        }
    }

    @Test
    fun `reaching a tier stops the world until the player chooses`() {
        val sim = newRun()
        assertTrue(runToChoice(sim), "no tier was reached in two centuries")

        val day = sim.day
        val hash = sim.stateHash()
        sim.run(5 * Time.DAYS_PER_YEAR)
        assertEquals(day, sim.day, "the clock advanced while a decision was open")
        assertEquals(hash, sim.stateHash(), "the world moved while a decision was open")
    }

    @Test
    fun `choosing releases the world and the tech takes effect`() {
        val sim = newRun()
        assertTrue(runToChoice(sim))

        val options = sim.pendingTechChoices(WorldConfig.PLAYER_CIV_ID)
        assertEquals(3, options.size)
        val farm = options.first { it.effects.farmYield > 0.0 }
        assertTrue(sim.chooseTech(WorldConfig.PLAYER_CIV_ID, farm))

        assertFalse(sim.awaitingPlayer)
        assertTrue(farm in sim.civ(0).techChoices)
        // The effect is folded into the same struct buildings produce, so every system sees it.
        assertTrue(
            sim.effectsOf(0).farmYieldBonus > 1.0,
            "the chosen tech did not reach the civ's effects",
        )

        val day = sim.day
        sim.run(30)
        assertTrue(sim.day > day, "the world did not resume")
    }

    @Test
    fun `an option that was not offered is refused`() {
        val sim = newRun()
        assertTrue(runToChoice(sim))
        val tier = sim.civ(0).pendingTechTier!!
        val wrongTier = TechOption.entries.first { it.tier != tier }
        assertFalse(sim.chooseTech(WorldConfig.PLAYER_CIV_ID, wrongTier), "took a tech from another tier")
        assertTrue(sim.awaitingPlayer, "a refused choice released the world anyway")
    }

    @Test
    fun `rivals never hold the world open`() {
        val sim = newRun()
        sim.run(120 * Time.DAYS_PER_YEAR)
        for (id in 1 until sim.civs.size) {
            assertEquals(null, sim.civ(id).pendingTechTier, "rival $id is waiting for somebody to choose")
        }
        // And the ones that got there did pick something.
        val advanced = (1 until sim.civs.size).filter { sim.civ(it).techTier > 0 }
        for (id in advanced) {
            assertTrue(sim.civ(id).techChoices.isNotEmpty(), "rival $id reached a tier and learned nothing")
        }
    }

    @Test
    fun `an unwatched run chooses for itself and never blocks`() {
        // The headless harness has no player, so it must not be possible to stall it.
        val sim = newRun()
        sim.runUnattendedUntilEnd(200 * Time.DAYS_PER_YEAR)
        assertFalse(sim.awaitingPlayer, "an unattended run stopped waiting for a player")
        if (sim.civ(0).techTier > 0) {
            assertEquals(sim.civ(0).techTier, sim.civ(0).techChoices.size)
        }
    }

    @Test
    fun `a pending choice and the techs taken survive a save`() {
        val sim = newRun()
        assertTrue(runToChoice(sim))
        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))

        assertTrue(reloaded.awaitingPlayer, "a reloaded run forgot it was waiting on a decision")
        assertEquals(
            sim.pendingTechChoices(0).map { it.name },
            reloaded.pendingTechChoices(0).map { it.name },
        )

        // Choose the same thing in both and they stay in step.
        val pick = sim.pendingTechChoices(0).first()
        assertTrue(sim.chooseTech(0, pick))
        assertTrue(reloaded.chooseTech(0, pick))
        sim.run(500)
        reloaded.run(500)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `a save naming a tech this build does not have still loads`() {
        val sim = newRun()
        assertTrue(runToChoice(sim))
        sim.chooseTech(0, sim.pendingTechChoices(0).first())
        sim.run(100)

        val text = SaveFormat.encode(sim.snapshot())
        val renamed = text.replace("\"CROP_ROTATION\"", "\"A_TECH_FROM_THE_FUTURE\"")
        // Unknown names are dropped rather than throwing: the run loads without that tech.
        val reloaded = Simulation.restore(SaveFormat.decode(renamed))
        assertTrue(reloaded.civ(0).techChoices.none { it.name == "A_TECH_FROM_THE_FUTURE" })
    }
}
