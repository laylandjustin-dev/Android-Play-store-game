package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import com.pixeltown.sim.GameConfig.World as WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A people is what its founding allocation made it, and every bonus it carries does something. */
class CivArchetypeTest {

    @Test
    fun `every trait makes a people of its own`() {
        // Six traits, six peoples: if two traits mapped to the same archetype, one of them would
        // be invisible on the setup screen.
        val byTrait = CivArchetype.entries.groupBy { it.trait }
        assertEquals(
            Trait.entries.size,
            byTrait.size,
            "traits without a people of their own: ${Trait.entries - byTrait.keys}",
        )
        for (trait in Trait.entries) {
            val sheet = TraitAllocation.BASE.withPointIn(trait)!!
            assertEquals(
                byTrait.getValue(trait).single(),
                CivArchetype.of(sheet),
                "one point in $trait did not produce its own people",
            )
        }
    }

    @Test
    fun `every people fields a unit nobody else does`() {
        val units = CivArchetype.entries.map { it.uniqueUnit }
        assertEquals(units.size, units.toSet().size, "two peoples share a unique unit: $units")
    }

    @Test
    fun `no bonus is decoration`() {
        // A field that is 1.0 for every archetype is a bonus that does nothing, which is worse than
        // not having it: it reads on the setup screen as a promise the simulation never keeps.
        fun varies(name: String, of: (CivArchetype) -> Double) {
            assertTrue(
                CivArchetype.entries.any { of(it) != 1.0 },
                "$name is 1.0 for every people, so it is decoration",
            )
        }
        varies("strengthBonus") { it.strengthBonus }
        varies("marchBonus") { it.marchBonus }
        varies("buildCostBonus") { it.buildCostBonus }
        varies("decayBonus") { it.decayBonus }
        varies("wallBonus") { it.wallBonus }
        varies("diseaseBonus") { it.diseaseBonus }
    }

    @Test
    fun `an allocation with nothing spent still names a people`() {
        assertNotNull(CivArchetype.of(TraitAllocation.BASE))
    }

    @Test
    fun `a people does not change when its traits grow`() {
        // A civ earns a point every decade (AD-50). Deriving identity from current traits would let
        // a people founded as Stalkers become Tillers in year 40, which is a status effect rather
        // than a nation.
        val sim = Simulation.newRun(1L, TraitAllocation(3, 3, 8, 3, 3, 3), civCount = 1)
        val civ = sim.civ(0)
        val founded = civ.archetype
        assertEquals(CivArchetype.STALKERS, founded, "a Hunting-max people should be Stalkers")

        // Pour every growth point into Farming, which would flip a naive derivation.
        repeat(6) { civ.traits = civ.traits.withPointIn(Trait.FARMING) ?: civ.traits }
        assertTrue(
            civ.traits.farming > civ.traits.hunting,
            "the test did not actually make Farming dominant",
        )
        assertEquals(founded, civ.archetype, "the people changed identity as its traits grew")
    }

    @Test
    fun `a people's identity survives a save`() {
        val sim = Simulation.newRun(7L, TraitAllocation(8, 3, 3, 3, 3, 3), civCount = 2)
        sim.runUnattended(200)
        val before = sim.civs.map { it.archetype }

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(before, reloaded.civs.map { it.archetype }, "archetypes changed across a save")
    }

    @Test
    fun `the island holds every civilisation the config asks for`() {
        // Six rivals rather than four is a deliberate change to the brief, and the generator has to
        // actually place them: sites are separated and restricted to the mainland (AD-13), so "it
        // fits" is a claim about geometry that deserves a test rather than an assumption.
        assertEquals(7, WorldConfig.TOTAL_CIV_COUNT, "the civ count changed without this test")
        for (seed in longArrayOf(1L, 1_000L, 8_919L)) {
            val sim = Simulation.newRun(seed, TraitAllocation.EVEN_SPREAD)
            val sites = sim.civs.map { it.homeSite }
            assertEquals(
                WorldConfig.TOTAL_CIV_COUNT,
                sites.toSet().size,
                "seed $seed did not give every civ a distinct home",
            )
            assertTrue(
                sites.all { sim.world.isWalkable(it) },
                "seed $seed founded someone on ground they cannot stand on",
            )
        }
    }

    @Test
    fun `a people's unique unit needs the building for it`() {
        // The unique unit replaces the armoury tier, so a people with no armoury fields none of
        // them — the identity is a reward for building, not a free upgrade.
        val sim = Simulation.newRun(1L, TraitAllocation(3, 3, 8, 3, 3, 3), civCount = 1)
        sim.runUnattended(90)
        val kinds = sim.unitBreakdown(0).keys
        assertTrue(
            UnitKind.BEASTMASTERS !in kinds,
            "a people with no armoury is already fielding its unique unit: $kinds",
        )
    }

    @Test
    fun `the archetype reads off the opening allocation, ties broken predictably`() {
        // Stated on the setup screen in one sentence, so it has to be predictable rather than clever.
        val tied = TraitAllocation.BASE
            .withPointIn(Trait.SPEED)!!
            .withPointIn(Trait.FARMING)!!
        val expected = CivArchetype.entries.first {
            it.trait == Trait.entries.first { t -> tied[t] > TraitConfig.BASE_VALUE }
        }
        assertEquals(expected, CivArchetype.of(tied), "a tie did not break by trait order")
    }
}
