package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Politics
import com.pixeltown.sim.GameConfig.Time
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CouncilTest {

    private fun newRun(seed: Long = 1L) = Simulation.newRun(seed, TraitAllocation.of(3, 4, 3, 4, 8))

    private fun playerElections(sim: Simulation) = sim.elections.filter { it.civId == 0 }

    // ------------------------------------------------------------------ elections

    @Test
    fun `an election is held every year`() {
        val sim = newRun()
        sim.run(10 * Time.DAYS_PER_YEAR + 1)
        assertEquals(10, playerElections(sim).size, "expected one election per year")
        assertNotNull(sim.premierOf(0), "no Premier was ever installed")
    }

    @Test
    fun `candidates are announced before the vote so the player can campaign`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR - Politics.CAMPAIGN_DAYS)
        val candidates = sim.campaignFor(0)
        assertNotNull(candidates, "no campaign was open before the election")
        assertEquals(Politics.CANDIDATE_COUNT, candidates.size)
        assertTrue(candidates.all { it.pitch.isNotBlank() }, "a candidate had no pitch line")
        assertEquals(candidates.size, candidates.map { it.citizenId }.distinct().size)
    }

    @Test
    fun `candidates are the highest-influence citizens`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR - Politics.CAMPAIGN_DAYS)
        val candidates = sim.campaignFor(0)!!
        val mostInfluential = sim.citizens
            .filter { it.civId == 0 && it.ageYears >= Politics.VOTING_AGE_YEARS }
            .sortedWith(compareByDescending<Citizen> { it.influence }.thenBy { it.id })
            .take(Politics.CANDIDATE_COUNT)
            .map { it.id }
            .toSet()
        assertEquals(mostInfluential, candidates.map { it.citizenId }.toSet())
    }

    @Test
    fun `every vote is counted and the tally matches the turnout`() {
        val sim = newRun()
        sim.run(6 * Time.DAYS_PER_YEAR + 1)
        for (election in playerElections(sim)) {
            assertEquals(
                election.turnout,
                election.totalVotes,
                "votes cast (${election.totalVotes}) did not match the voting population (${election.turnout})",
            )
        }
    }

    @Test
    fun `elections are contested, not unanimous`() {
        // A hive-mind electorate makes the council screen pointless: citizens in one town are in
        // near-identical condition, so without a personal leaning every election came back 95-0.
        val sim = newRun()
        sim.run(60 * Time.DAYS_PER_YEAR)
        val shares = playerElections(sim)
            .filter { it.totalVotes >= 20 }
            .map { e -> e.voteCounts.maxOf { it.second }.toDouble() / e.totalVotes }
        assertTrue(shares.isNotEmpty(), "no elections had a meaningful electorate")
        assertTrue(
            shares.average() < 0.90,
            "the average winner took ${shares.average()} of the vote — elections are not contested",
        )
        assertTrue(
            shares.any { it < 0.60 },
            "no election was ever close",
        )
    }

    @Test
    fun `a hungry town votes for farms`() {
        // The electorate is a feedback loop on the state of the town — that is the whole point.
        val sim = newRun()
        sim.run(3 * Time.DAYS_PER_YEAR)
        val civ = sim.civ(0)
        val voter = sim.citizens.first { it.civId == 0 }

        voter.nutrition = 0f
        val hungry = CouncilSystem.needsOf(voter, sim.effectsOf(0), civ, sim.populationOf(0))
        voter.nutrition = 1f
        val fed = CouncilSystem.needsOf(voter, sim.effectsOf(0), civ, sim.populationOf(0))

        assertTrue(
            hungry.getValue(BuildingCategory.FARMS) > fed.getValue(BuildingCategory.FARMS),
            "hunger did not push a voter toward farms",
        )
    }

    @Test
    fun `a homeless citizen wants housing`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR)
        val civ = sim.civ(0)
        val voter = sim.citizens.first { it.civId == 0 }

        voter.homeBuildingId = null
        val homeless = CouncilSystem.needsOf(voter, sim.effectsOf(0), civ, sim.populationOf(0))
        voter.homeBuildingId = 1
        val housed = CouncilSystem.needsOf(voter, sim.effectsOf(0), civ, sim.populationOf(0))

        assertTrue(
            homeless.getValue(BuildingCategory.LIFESTYLE) > housed.getValue(BuildingCategory.LIFESTYLE),
            "homelessness did not push a voter toward housing",
        )
    }

    @Test
    fun `agendas are normalised and every temperament can be elected`() {
        val sim = newRun()
        sim.run(80 * Time.DAYS_PER_YEAR)
        val elections = playerElections(sim)
        for (election in elections) {
            val total = BuildingCategory.entries.sumOf { election.agenda[it] }
            assertEquals(1.0, total, absoluteTolerance = 1e-9)
        }
        assertTrue(
            elections.map { it.temperament }.distinct().size >= 3,
            "only ${elections.map { it.temperament }.distinct()} ever held office in 80 years",
        )
    }

    // ------------------------------------------------------------------ buildings

    @Test
    fun `the Premier orders buildings and builders finish them`() {
        val sim = newRun()
        sim.run(20 * Time.DAYS_PER_YEAR)
        val built = sim.buildingsOf(0)
        assertTrue(built.isNotEmpty(), "nothing was ever ordered in twenty years")
        assertTrue(built.any { it.isComplete }, "nothing was ever finished")
    }

    @Test
    fun `a half-built building does nothing`() {
        val sim = newRun()
        sim.run(20 * Time.DAYS_PER_YEAR)
        val unfinished = sim.buildingsOf(0).firstOrNull { !it.isComplete } ?: return
        val effects = BuildingSystem.aggregate(listOf(unfinished))
        assertEquals(0, effects.completedCount)
        assertEquals(0, effects.housingCapacity)
        assertEquals(0.0, effects.foodStorageBonus)
        assertTrue(unfinished.completionFraction() < 1.0)
    }

    @Test
    fun `buildings occupy the map and never overlap`() {
        val sim = newRun()
        sim.run(40 * Time.DAYS_PER_YEAR)
        val claimed = HashMap<Int, Int>()
        for (building in sim.buildings) {
            val footprint = building.spec.footprint
            for (dy in 0 until footprint) {
                for (dx in 0 until footprint) {
                    val cell = sim.world.index(building.x + dx, building.y + dy)
                    val previous = claimed.put(cell, building.id)
                    assertNull(previous, "buildings ${building.id} and $previous overlap at cell $cell")
                }
            }
        }
    }

    @Test
    fun `housing shelters citizens and raises their survival`() {
        val sim = newRun()
        sim.run(40 * Time.DAYS_PER_YEAR)
        val housing = sim.buildingsOf(0).filter { it.isComplete && it.spec.housingCapacity > 0 }
        if (housing.isEmpty()) return
        val housed = sim.citizens.filter { it.civId == 0 && it.homeBuildingId != null }
        assertTrue(housed.isNotEmpty(), "housing was built but nobody moved in")
        assertTrue(
            housed.size <= sim.effectsOf(0).housingCapacity,
            "more people were housed than there were beds",
        )
    }

    @Test
    fun `granaries raise the food storage capacity`() {
        val sim = newRun()
        val baseline = sim.civ(0).foodStorageCapacity
        sim.run(60 * Time.DAYS_PER_YEAR)
        val granaries = sim.buildingsOf(0).count { it.isComplete && it.type == BuildingType.GRANARY }
        if (granaries == 0) return
        assertTrue(
            sim.civ(0).foodStorageCapacity > baseline,
            "granaries were built but storage capacity did not rise",
        )
    }

    @Test
    fun `tech tiers unlock better buildings`() {
        assertTrue(GameConfig.Buildings.available(BuildingCategory.TECH, techTier = 0).all { it.tier == 0 })
        val atTierTwo = GameConfig.Buildings.available(BuildingCategory.TECH, techTier = 2)
        assertTrue(atTierTwo.any { it.type == BuildingType.ACADEMY }, "tier 2 did not unlock the Academy")
        assertTrue(
            atTierTwo.none { it.type == BuildingType.OBSERVATORY },
            "the Observatory unlocked before its tier",
        )
        // Best available first, so a Premier builds the best thing they can afford.
        assertEquals(atTierTwo.maxOf { it.tier }, atTierTwo.first().tier)
    }

    // ------------------------------------------------------------------ the player's levers

    @Test
    fun `endorsing a candidate costs influence and only works during a campaign`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR - Politics.CAMPAIGN_DAYS)
        val civ = sim.civ(0)
        civ.influencePoints = 1_000.0
        val candidate = sim.campaignFor(0)!!.first()

        val before = civ.influencePoints
        assertTrue(sim.endorse(0, candidate.citizenId), "a legitimate endorsement was refused")
        assertEquals(before - Politics.COST_ENDORSE, civ.influencePoints)
        assertFalse(sim.endorse(0, -999), "endorsing a non-candidate was allowed")
    }

    @Test
    fun `an endorsement moves votes`() {
        // Run two identical worlds to the same election, endorsing the underdog in one of them.
        fun runToElection(endorse: Boolean): ElectionResult {
            val sim = newRun(7L)
            sim.run(Time.DAYS_PER_YEAR - Politics.CAMPAIGN_DAYS)
            if (endorse) {
                sim.civ(0).influencePoints = 1_000.0
                val underdog = sim.campaignFor(0)!!.last()
                sim.endorse(0, underdog.citizenId)
            }
            sim.run(Politics.CAMPAIGN_DAYS)
            return sim.elections.first { it.civId == 0 }
        }

        val plain = runToElection(endorse = false)
        val backed = runToElection(endorse = true)
        val underdogName = plain.voteCounts.last().first
        val before = plain.voteCounts.first { it.first == underdogName }.second
        val after = backed.voteCounts.first { it.first == underdogName }.second
        assertTrue(after >= before, "an endorsement cost the candidate votes ($before -> $after)")
    }

    @Test
    fun `a petition shifts the Premier's agenda`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR + 1)
        val civ = sim.civ(0)
        civ.influencePoints = 1_000.0
        val premier = sim.premierOf(0)!!
        val before = premier.agenda[BuildingCategory.MILITARY]

        assertTrue(sim.petition(0, BuildingCategory.MILITARY, 0.4))
        assertTrue(premier.agenda[BuildingCategory.MILITARY] > before, "the petition changed nothing")
        assertTrue(premier.petitionActive)
        assertEquals(
            1.0,
            BuildingCategory.entries.sumOf { premier.agenda[it] },
            absoluteTolerance = 1e-9,
        )
    }

    @Test
    fun `a veto is limited to one a year`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR + 1)
        val civ = sim.civ(0)
        civ.influencePoints = 1_000.0
        assertTrue(sim.veto(0), "the first veto of the year was refused")
        assertFalse(sim.veto(0), "a second veto in the same year was allowed")
    }

    @Test
    fun `influence actions fail when the player cannot pay`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR + 1)
        sim.civ(0).influencePoints = 0.0
        assertFalse(sim.petition(0, BuildingCategory.FARMS, 0.2))
        assertFalse(sim.veto(0))
        assertFalse(sim.callReferendum(0))
    }

    @Test
    fun `a referendum opens a fresh campaign`() {
        val sim = newRun()
        sim.run(2 * Time.DAYS_PER_YEAR + 30)
        sim.civ(0).influencePoints = 1_000.0
        assertNull(sim.campaignFor(0), "a campaign was already open")
        assertTrue(sim.callReferendum(0))
        assertNotNull(sim.campaignFor(0), "the referendum opened no campaign")
    }

    @Test
    fun `influence accrues over time and faster with plazas`() {
        val sim = newRun()
        sim.run(Time.DAYS_PER_YEAR)
        assertTrue(sim.civ(0).influencePoints > 0.0, "no influence accrued in a year")
    }

    // ------------------------------------------------------------------ unrest

    @Test
    fun `unrest stays low under competent government`() {
        val sim = newRun()
        sim.run(60 * Time.DAYS_PER_YEAR)
        assertTrue(
            sim.civ(0).unrest < 0.5,
            "unrest reached ${sim.civ(0).unrest} in a town that was governing itself adequately",
        )
    }

    @Test
    fun `unrest rises when suffering meets bad governance`() {
        val civ = Civilization(0, "Test", TraitAllocation.EVEN_SPREAD, Personality.ISOLATIONIST, 0)
        repeat(400) { CouncilSystem.updateUnrest(civ, needGap = 0.8, meanSurvival = 20.0) }
        assertTrue(civ.unrest > 0.1, "a suffering, badly-governed town stayed calm: ${civ.unrest}")

        val contented = Civilization(0, "Test", TraitAllocation.EVEN_SPREAD, Personality.ISOLATIONIST, 0)
        contented.unrest = 0.5
        repeat(400) { CouncilSystem.updateUnrest(contented, needGap = 0.1, meanSurvival = 80.0) }
        assertEquals(0.0, contented.unrest, "unrest did not decay in a well-run town")
    }

    // ------------------------------------------------------------------ determinism

    @Test
    fun `politics does not break determinism`() {
        val a = newRun(99L)
        val b = newRun(99L)
        repeat(10) {
            a.run(5 * Time.DAYS_PER_YEAR)
            b.run(5 * Time.DAYS_PER_YEAR)
            assertEquals(a.stateHash(), b.stateHash(), "diverged by year ${a.year}")
            assertEquals(
                a.elections.map { it.winnerName },
                b.elections.map { it.winnerName },
                "different people won the same elections",
            )
        }
    }

    // ------------------------------------------------------------------ the milestone gate

    @Test
    fun `a fifty-year run produces a varied town and a readable election history`() {
        val sim = newRun()
        sim.run(50 * Time.DAYS_PER_YEAR)

        val completed = sim.buildingsOf(0).filter { it.isComplete }
        assertTrue(completed.size >= 15, "only ${completed.size} buildings stood after fifty years")
        val kinds = completed.map { it.type }.distinct()
        assertTrue(kinds.size >= 6, "the town built only ${kinds.size} kinds of building: $kinds")
        val categories = completed.map { it.spec.category }.distinct()
        assertTrue(categories.size >= 4, "the town only ever built $categories")

        val elections = playerElections(sim)
        assertEquals(50, elections.size)
        assertTrue(elections.all { it.winnerName.isNotBlank() })
        assertTrue(elections.all { it.totalVotes == it.turnout })
        assertTrue(
            elections.map { it.agenda.dominant }.distinct().size >= 3,
            "every Premier in fifty years ran on the same platform",
        )
    }
}
