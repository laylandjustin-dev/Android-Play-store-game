package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** A people grows: one trait point per decade, the player's to spend. */
class GenerationGrowthTest {

    private val START = TraitAllocation.of(3, 4, 3, 4, 8)

    /** Enough draws that a weighted preference is unmistakable rather than lucky. */
    private val DRAWS = 4_000

    private fun newRun(seed: Long = 1L, traits: TraitAllocation = START) =
        Simulation.newRun(RunConfig(seed = seed, traits = traits))

    private fun player(sim: Simulation) = sim.civ(WorldConfig.PLAYER_CIV_ID)

    /**
     * Runs [days] as a watched run, answering the tech choices a tier offers along the way and
     * leaving the decade's trait point alone.
     *
     * These tests are about the trait point, and a run that stops at a tech tier never reaches the
     * decade at all. Answering the one and not the other is what keeps the thing under test
     * isolated — `runUnattended` would resolve both and prove nothing. A pending trait point stops
     * the clock, so a call that runs past a decade boundary simply stops there; that is the
     * mechanic, and several tests below assert exactly it.
     */
    private fun runAnswering(sim: Simulation, days: Int) {
        repeat(days) {
            if (sim.endState != null) return
            if (sim.pendingTechChoices(WorldConfig.PLAYER_CIV_ID).isNotEmpty()) {
                sim.chooseTech(WorldConfig.PLAYER_CIV_ID, sim.pendingTechChoices(WorldConfig.PLAYER_CIV_ID).first())
            }
            sim.step()
        }
    }

    /** As [runAnswering], but spending each decade point on the weakest trait so the run continues. */
    private fun runSpending(sim: Simulation, days: Int) {
        repeat(days) {
            if (sim.endState != null) return
            if (sim.pendingTechChoices(WorldConfig.PLAYER_CIV_ID).isNotEmpty()) {
                sim.chooseTech(WorldConfig.PLAYER_CIV_ID, sim.pendingTechChoices(WorldConfig.PLAYER_CIV_ID).first())
            }
            while (sim.pendingTraitPoints(WorldConfig.PLAYER_CIV_ID) > 0) {
                val trait = sim.needBasedGrowth(player(sim)) ?: break
                if (!sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, trait)) break
            }
            sim.step()
        }
    }

    @Test
    fun `nothing is earned before the first decade`() {
        val sim = newRun()
        runAnswering(sim, TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR - 1)
        assertEquals(0, player(sim).unspentTraitPoints)
        assertEquals(START.pointsSpent, player(sim).traits.pointsSpent)
    }

    @Test
    fun `a point arrives on the decade and waits for the player`() {
        val sim = newRun()
        runAnswering(sim, TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)
        assertEquals(TraitConfig.GENERATION_POINTS_PER_AWARD, player(sim).unspentTraitPoints)
        // Unspent means unspent: the allocation has not changed yet.
        assertEquals(START.pointsSpent, player(sim).traits.pointsSpent)
    }

    @Test
    fun `the player spends a point where they choose`() {
        val sim = newRun(traits = TraitAllocation.of(3, 4, 3, 4, 8))
        runAnswering(sim, TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)

        val before = player(sim).traits[Trait.SPEED]
        assertTrue(sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.SPEED))
        assertEquals(before + 1, player(sim).traits[Trait.SPEED])
        assertEquals(0, player(sim).unspentTraitPoints)

        // And the derived stats really move — that is the whole point of the mechanic.
        assertTrue(player(sim).traits.workMultiplier > START.workMultiplier)
    }

    @Test
    fun `spending is refused when there is nothing to spend or no room to spend it`() {
        val sim = newRun(traits = TraitAllocation.of(3, 4, 3, 4, 8))
        assertFalse(sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.SPEED), "spent a point it had not earned")

        runAnswering(sim, TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)
        // Farming opened at 8, which is the *opening* cap — a decade point may still climb it, so
        // this now checks the lifetime ceiling instead.
        repeat(TraitConfig.MAX_PER_TRAIT) {
            player(sim).unspentTraitPoints = 1
            sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.FARMING)
        }
        assertEquals(TraitConfig.MAX_PER_TRAIT, player(sim).traits[Trait.FARMING])
        player(sim).unspentTraitPoints = 1
        assertFalse(sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.FARMING), "pushed a trait past its cap")
        assertEquals(1, player(sim).unspentTraitPoints, "a refused spend must not consume the point")
    }

    @Test
    fun `a watched run stops the clock rather than spending the point for the player`() {
        // The decision is the mechanic, and stopping the clock is what makes it symmetric with the
        // rivals: a rival spends its point the day it earns it, so a player whose point merely
        // banked was a point behind four neighbours for up to a game year — thirty-six seconds at
        // 10x. Nothing advances until the player has spent.
        val decade = TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR
        val sim = newRun()
        runAnswering(sim, decade * 2)

        assertEquals(1, player(sim).unspentTraitPoints, "the game spent the player's point for them")
        assertEquals(START.pointsSpent, player(sim).traits.pointsSpent)
        assertEquals(decade.toLong(), sim.day, "the clock ran on past a decision only the player can make")
        assertTrue(sim.awaitingPlayer, "an unspent point did not stop the clock")
        assertFalse(sim.step(), "a step advanced the world while a decision was open")

        // And spending it starts the world again.
        assertTrue(sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.SPEED))
        assertFalse(sim.awaitingPlayer)
        assertTrue(sim.step())
    }

    @Test
    fun `an unwatched run spends it the day it is earned, exactly as a rival does`() {
        val sim = newRun()
        sim.runUnattended(TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)

        assertEquals(0, player(sim).unspentTraitPoints, "the point was never spent")
        assertEquals(
            START.pointsSpent + 1,
            player(sim).traits.pointsSpent,
            "the automatic spend did not land anywhere",
        )
        // The whole decade ran: nothing stopped, because there was nobody to stop for.
        assertEquals(
            (TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR).toLong(),
            sim.day,
        )
    }

    @Test
    fun `the automatic choice rounds a well-fed people out`() {
        val sim = newRun()
        val civ = Civilization(0, "Test", TraitAllocation.of(1, 4, 3, 5, 7), Personality.ISOLATIONIST, 0)
        civ.population = 50
        civ[Resource.FOOD] = 5_000.0 // a full granary: nothing is urgent
        assertEquals(Trait.SPEED, sim.needBasedGrowth(civ), "the weakest trait should have been shored up")
    }

    @Test
    fun `the automatic choice feeds a hungry people first`() {
        val sim = newRun()
        val hungry = Civilization(0, "Test", TraitAllocation.of(1, 4, 3, 5, 7), Personality.ISOLATIONIST, 0)
        hungry.population = 50
        hungry[Resource.FOOD] = 10.0 // less than a day's rations
        assertEquals(Trait.FARMING, sim.needBasedGrowth(hungry))

        // And a people who cannot feed itself at all takes food before anything else.
        val starving = Civilization(0, "Test", TraitAllocation.of(6, 6, 6, 6, 2), Personality.ISOLATIONIST, 0)
        starving.population = 50
        starving[Resource.FOOD] = 50_000.0
        assertEquals(Trait.FARMING, sim.needBasedGrowth(starving))
    }

    @Test
    fun `an unwatched point always lands somewhere`() {
        val sim = newRun(traits = TraitAllocation.of(1, 4, 3, 5, 7))
        val before = sim.civ(0).traits
        sim.runUnattended(TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)
        assertEquals(before.pointsSpent + 1, player(sim).traits.pointsSpent)
    }

    @Test
    fun `rivals spend their own points immediately and stay in character`() {
        val sim = newRun()
        runAnswering(sim, TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)

        for (id in 1 until sim.civs.size) {
            val rival = sim.civ(id)
            if (rival.population == 0) continue
            assertEquals(0, rival.unspentTraitPoints, "${rival.name} banked a point instead of using it")
            assertTrue(
                rival.traits.pointsSpent > TraitConfig.ALLOCATION_POINTS,
                "${rival.name} earned a point and did not grow",
            )
        }
    }

    @Test
    fun `growth stays in character`() {
        // A single decade is a coin toss — what must hold is the tendency, so this measures the
        // distribution rather than one sequence.
        val rng = SimRandom(7L)
        val start = TraitAllocation.of(3, 3, 5, 3, 4)
        val counts = Personality.entries.associateWith { personality ->
            val tally = IntArray(TraitConfig.COUNT)
            repeat(DRAWS) {
                RivalStrategist.chooseGrowth(personality, start, rng)?.let { tally[it.ordinal]++ }
            }
            tally
        }

        fun favourite(personality: Personality) =
            Trait.entries[counts.getValue(personality).withIndex().maxByOrNull { it.value }!!.index]

        assertEquals(Trait.HUNTING, favourite(Personality.MILITANT), "militants did not grow into their arms")
        assertEquals(Trait.FARMING, favourite(Personality.MERCANTILE), "mercantiles did not grow their surplus")
        assertEquals(Trait.ELEMENTS, favourite(Personality.ISOLATIONIST), "isolationists did not harden")

        // Hunting is a militant's most-taken trait and an isolationist's least.
        val isolationist = counts.getValue(Personality.ISOLATIONIST)
        assertEquals(
            Trait.HUNTING.ordinal,
            isolationist.withIndex().minByOrNull { it.value }!!.index,
            "isolationists should want arms least of all",
        )
    }

    @Test
    fun `growth stops at the ceiling rather than overflowing`() {
        // The lifetime ceiling, not the opening cap: two different numbers on purpose (AD-58).
        val top = TraitConfig.MAX_PER_TRAIT
        val maxed = TraitAllocation.of(top, top, top, top, top, top)
        assertTrue(maxed.improvable.isEmpty())
        assertNull(maxed.withPointIn(Trait.SPEED))
        assertNull(RivalStrategist.chooseGrowth(Personality.MILITANT, maxed, SimRandom(1L)))
    }

    @Test
    fun `a decade a civ was extinct for pays nothing when it is gone`() {
        // Hunting-8 with Farming-1 is the known non-viable build: it dies in its first years.
        val sim = Simulation.newRun(
            RunConfig(seed = 3L, traits = TraitAllocation.of(5, 4, 8, 3, 1), civCount = 1),
        )
        runAnswering(sim, TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)
        val civ = player(sim)
        if (civ.population == 0) {
            assertEquals(0, civ.unspentTraitPoints, "an extinct people earned a growth point")
        }
    }

    @Test
    fun `growth survives a save, including a point still waiting to be spent`() {
        val sim = newRun()
        runAnswering(sim, TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR)
        assertEquals(1, player(sim).unspentTraitPoints)

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(1, reloaded.civ(0).unspentTraitPoints)
        assertEquals(player(sim).generationsAwarded, reloaded.civ(0).generationsAwarded)
        assertTrue(reloaded.awaitingPlayer, "the reloaded run forgot it was waiting on a decision")

        // Spend the same point the same way on both sides, and they stay the same run.
        assertTrue(sim.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.GATHERING))
        assertTrue(reloaded.spendTraitPoint(WorldConfig.PLAYER_CIV_ID, Trait.GATHERING))
        runSpending(sim, 5 * Time.DAYS_PER_YEAR)
        runSpending(reloaded, 5 * Time.DAYS_PER_YEAR)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `growth is deterministic`() {
        val a = newRun(77L)
        val b = newRun(77L)
        repeat(6) {
            runSpending(a, 10 * Time.DAYS_PER_YEAR)
            runSpending(b, 10 * Time.DAYS_PER_YEAR)
            assertEquals(a.stateHash(), b.stateHash(), "diverged by year ${a.year}")
        }
        // Sanity: everybody really did grow over sixty years, so the hashes above mean something.
        assertTrue(
            (1 until a.civs.size).any { a.civ(it).traits.pointsSpent > TraitConfig.ALLOCATION_POINTS },
            "no rival grew in sixty years",
        )
        assertTrue(
            a.civ(0).traits.pointsSpent > START.pointsSpent,
            "the player grew nothing in sixty years",
        )
    }
}
