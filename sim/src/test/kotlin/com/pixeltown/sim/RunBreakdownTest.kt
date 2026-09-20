package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** What the run actually decided, once it is over (AD-63). */
class RunBreakdownTest {

    /** A build that dies quickly, so a finished run is reachable without simulating three centuries. */
    private fun doomedRun() = Simulation.newRun(
        RunConfig(seed = 5L, traits = TraitAllocation.of(8, 3, 3, 3, 1), civCount = 1),
    )

    private fun finished(): Simulation {
        val sim = doomedRun()
        sim.runUnattendedUntilEnd(60 * Time.DAYS_PER_YEAR)
        return sim
    }

    @Test
    fun `there is no summary until the run has ended`() {
        val sim = doomedRun()
        assertEquals(null, sim.summary(), "a running game reported its own obituary")
    }

    @Test
    fun `the opening allocation is recovered exactly from the final sheet`() {
        val opening = TraitAllocation.of(3, 4, 3, 4, 8)
        val sim = Simulation.newRun(RunConfig(seed = 12L, traits = opening))
        sim.runUnattendedUntilEnd(45 * Time.DAYS_PER_YEAR)
        val summary = sim.summary() ?: return

        assertEquals(
            opening.values.toList(),
            summary.openingTraits.values.toList(),
            "the breakdown misremembered what the run opened on",
        )
        // And the two sheets differ by exactly the growth history, which is what makes deriving
        // one from the other safe.
        assertEquals(
            summary.finalTraits.pointsSpent - summary.openingTraits.pointsSpent,
            summary.traitGrowth.values.sum(),
            "the trait sheet and the growth record disagree",
        )
    }

    @Test
    fun `an unattended run reports that it grew up without anyone watching`() {
        val sim = Simulation.newRun(RunConfig(seed = 12L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattendedUntilEnd(45 * Time.DAYS_PER_YEAR)
        val summary = sim.summary() ?: return
        if (summary.traitGrowth.values.sum() == 0) return

        assertEquals(
            summary.traitGrowth.values.sum(),
            summary.traitPointsAutoSpent,
            "an unattended run claimed the player had chosen something",
        )
        assertEquals(0, summary.attentionShare, "nobody was watching, so none of it was theirs")
    }

    @Test
    fun `a watched run's points are credited to the player`() {
        val decade = TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR
        val sim = Simulation.newRun(
            RunConfig(seed = 12L, traits = TraitAllocation.of(3, 4, 3, 4, 8), civCount = 1),
        )
        // Play it: answer both decisions as they come, exactly as the shell does.
        repeat(decade * 3) {
            if (sim.endState != null) return@repeat
            sim.pendingTechChoices(WorldConfig.PLAYER_CIV_ID).firstOrNull()?.let {
                sim.chooseTech(WorldConfig.PLAYER_CIV_ID, it)
            }
            if (sim.pendingTraitPoints(WorldConfig.PLAYER_CIV_ID) > 0) {
                sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.GATHERING)
            }
            sim.acknowledgeElection()
            sim.step()
        }
        val player = sim.civ(WorldConfig.PLAYER_CIV_ID)
        assertTrue(player.traitGrowthHistory.isNotEmpty(), "three decades produced no growth at all")
        assertTrue(
            player.traitGrowthHistory.all { it == Trait.GATHERING },
            "a point went somewhere the player did not put it",
        )
        assertEquals(0, player.autoSpentTraitPoints, "the game spent a point during live play")
    }

    @Test
    fun `every share adds up to a hundred, or to nothing at all`() {
        val summary = finished().summary() ?: return
        for ((name, rows) in listOf(
            "growth" to summary.traitGrowthShare(),
            "buildings" to summary.buildingShare(),
            "deaths" to summary.deathShare(),
            "platforms" to summary.platformShare(),
        )) {
            if (rows.isEmpty()) continue
            val total = rows.sumOf { it.second }
            assertTrue(total in 97..103, "$name shares summed to $total%")
            // Largest first, so the UI can read the list straight down.
            assertEquals(rows.sortedByDescending { it.second }.map { it.second }, rows.map { it.second })
        }
    }

    @Test
    fun `the record survives a save`() {
        val sim = Simulation.newRun(RunConfig(seed = 12L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattended(25 * Time.DAYS_PER_YEAR)
        val player = sim.civ(0)
        val history = player.traitGrowthHistory.toList()
        val deaths = player.deathsByCause.toList()

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(history, reloaded.civ(0).traitGrowthHistory.toList())
        assertEquals(deaths, reloaded.civ(0).deathsByCause.toList())
        assertEquals(player.autoSpentTraitPoints, reloaded.civ(0).autoSpentTraitPoints)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `deaths are counted per civ, not across the whole map`() {
        val sim = Simulation.newRun(RunConfig(seed = 12L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattended(30 * Time.DAYS_PER_YEAR)
        for (civ in sim.civs) {
            assertEquals(
                civ.totalDeaths,
                civ.deathsByCause.sum(),
                "${civ.name}'s causes of death do not add up to its dead",
            )
        }
        // And the per-civ totals are genuinely different numbers, or the split proves nothing.
        assertTrue(
            sim.civs.map { it.totalDeaths }.distinct().size > 1,
            "every civ on the map buried exactly the same number of people",
        )
    }

    @Test
    fun `a save from before the breakdown existed still loads`() {
        val sim = Simulation.newRun(RunConfig(seed = 12L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattended(15 * Time.DAYS_PER_YEAR)
        val text = SaveFormat.encode(sim.snapshot())
        val stripped = Regex(",\"traitGrowthHistory\":\\[[^]]*]").replace(text, "")
        assertTrue(stripped.length < text.length, "there was no growth history to remove")
        val reloaded = Simulation.restore(SaveFormat.decode(stripped))
        assertTrue(reloaded.civ(0).traitGrowthHistory.isEmpty(), "an old save invented a history")
    }
}
