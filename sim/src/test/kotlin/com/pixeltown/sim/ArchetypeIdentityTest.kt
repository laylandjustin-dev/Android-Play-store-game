package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import com.pixeltown.sim.GameConfig.World as WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A people is what its founding allocation made it, and every bonus it carries does something.
 *
 * These pin AD-80. `Archetype` already existed as a label and a marker shape; what is new is that it
 * now carries the bonuses and the unique unit, and that a civ's identity is *fixed at founding*.
 */
class ArchetypeIdentityTest {

    /** An allocation dominated by [trait], by enough to clear the margin that names a people. */
    private fun dominatedBy(trait: Trait): TraitAllocation {
        var sheet = TraitAllocation.BASE
        repeat(Archetype.DOMINANCE_MARGIN) { sheet = sheet.withPointIn(trait) ?: sheet }
        return sheet
    }

    @Test
    fun `every trait makes a people of its own`() {
        // Six traits, six specialised peoples. If two traits produced the same archetype, one of
        // them would be invisible on the setup screen.
        val byTrait = Trait.entries.associateWith { Archetype.of(dominatedBy(it)) }
        assertEquals(
            Trait.entries.size,
            byTrait.values.toSet().size,
            "two traits share a people: $byTrait",
        )
        assertTrue(
            Archetype.BALANCED !in byTrait.values,
            "a dominated allocation came out BALANCED: $byTrait",
        )
    }

    @Test
    fun `every specialised people fields a unit nobody else does`() {
        val specialists = Archetype.entries.filter { it != Archetype.BALANCED }
        val units = specialists.map { it.uniqueUnit }
        assertEquals(units.size, units.toSet().size, "two peoples share a unique unit: $units")
        assertTrue(
            UnitKind.MEN_AT_ARMS !in units,
            "a specialised people fields the generic unit, so its identity is invisible in the field",
        )
        // And the honest reading of "best at nothing": no speciality unit.
        assertEquals(UnitKind.MEN_AT_ARMS, Archetype.BALANCED.uniqueUnit)
    }

    @Test
    fun `no bonus is decoration`() {
        // A field that reads 1.0 for every people is a promise on the setup screen the simulation
        // never keeps, which is worse than not offering it at all.
        fun varies(name: String, of: (Archetype) -> Double) {
            assertTrue(
                Archetype.entries.any { of(it) != 1.0 },
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
    fun `a people with no speciality claims no bonus`() {
        val balanced = Archetype.BALANCED
        for ((name, value) in listOf(
            "strength" to balanced.strengthBonus,
            "march" to balanced.marchBonus,
            "buildCost" to balanced.buildCostBonus,
            "decay" to balanced.decayBonus,
            "wall" to balanced.wallBonus,
            "disease" to balanced.diseaseBonus,
        )) {
            assertEquals(1.0, value, 1e-9, "BALANCED has a $name bonus, so it is not balanced")
        }
    }

    @Test
    fun `an allocation with nothing spent still names a people`() {
        assertEquals(Archetype.BALANCED, Archetype.of(TraitAllocation.BASE))
        assertNotNull(Archetype.of(TraitAllocation.EVEN_SPREAD))
    }

    @Test
    fun `a people does not change when its traits grow`() {
        // A civ earns a point every decade (AD-50). Deriving identity from current traits would let
        // a people founded as Wild become Rooted in year 40, which is a status effect, not a nation.
        val sim = Simulation.newRun(1L, dominatedBy(Trait.HUNTING), civCount = 1)
        val civ = sim.civ(0)
        val founded = civ.archetype
        assertEquals(Archetype.WILD, founded, "a Hunting-dominated people should be Wild")

        repeat(8) { civ.traits = civ.traits.withPointIn(Trait.FARMING) ?: civ.traits }
        assertTrue(
            civ.traits.farming > civ.traits.hunting,
            "the test did not actually make Farming dominant",
        )
        assertEquals(Archetype.ROOTED, Archetype.of(civ.traits), "the premise of this test is wrong")
        assertEquals(founded, civ.archetype, "the people changed identity as its traits grew")
    }

    @Test
    fun `a people's identity survives a save`() {
        val sim = Simulation.newRun(7L, dominatedBy(Trait.SPEED), civCount = 2)
        sim.runUnattended(200)
        val before = sim.civs.map { it.archetype }

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(before, reloaded.civs.map { it.archetype }, "archetypes changed across a save")
    }

    @Test
    fun `the island holds every civilisation the config asks for`() {
        // Six rivals rather than four is a deliberate change to the brief (AD-80), and the generator
        // has to actually place them: sites are separated and mainland-only (AD-13), so "seven fit"
        // is a claim about geometry that deserves a test rather than an assumption.
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
        // The unique unit replaces the armoury tier, so it is earned by building rather than given.
        val sim = Simulation.newRun(1L, dominatedBy(Trait.HUNTING), civCount = 1)
        sim.runUnattended(90)
        val kinds = sim.unitBreakdown(0).keys
        assertTrue(
            UnitKind.BEASTMASTERS !in kinds,
            "a people with no armoury is already fielding its unique unit: $kinds",
        )
    }

    @Test
    fun `one stray point does not rename a people`() {
        // DOMINANCE_MARGIN exists so 5/5/5/5/6 is a balanced town that farms slightly better, not a
        // farming civilisation. Worth pinning, because the bonuses now ride on this decision.
        val nearlyEven = TraitAllocation.BASE.withPointIn(Trait.FARMING)!!
        assertEquals(Archetype.BALANCED, Archetype.of(nearlyEven))
    }
}
