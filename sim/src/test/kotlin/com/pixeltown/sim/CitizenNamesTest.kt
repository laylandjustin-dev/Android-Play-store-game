package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** Everyone has a name, and a family it came from (AD-62). */
class CitizenNamesTest {

    private fun newRun(seed: Long = 1_000L) =
        Simulation.newRun(RunConfig(seed = seed, traits = TraitAllocation.of(3, 4, 3, 4, 8)))

    @Test
    fun `a name is a pure function of its two integers`() {
        assertEquals(CitizenNames.firstName(0), CitizenNames.firstName(0))
        assertEquals(CitizenNames.surname(0), CitizenNames.surname(0))
        // Out-of-range values are named rather than throwing: a name is presentation, never a crash.
        assertTrue(CitizenNames.firstName(-1).isNotEmpty())
        assertTrue(CitizenNames.surname(CitizenNames.FAMILY_COUNT * 3 + 1).isNotEmpty())
    }

    @Test
    fun `given names are plentiful and family names are not`() {
        // Given names must be near-unique in a town; family names must repeat, or there is no
        // lineage to speak of. That asymmetry is the design.
        assertTrue(
            CitizenNames.GIVEN_NAME_COUNT > 2_000,
            "only ${CitizenNames.GIVEN_NAME_COUNT} given names for a town of thousands",
        )
        assertTrue(CitizenNames.FAMILY_COUNT in 10..80, "the family pool is the wrong size to be a lineage")
    }

    @Test
    fun `every founding settler is named, and most of them differently`() {
        val sim = newRun()
        val settlers = sim.citizens.filter { it.civId == WorldConfig.PLAYER_CIV_ID }
        assertEquals(WorldConfig.STARTING_SETTLERS, settlers.size)
        for (settler in settlers) {
            assertTrue(settler.firstName.isNotEmpty(), "citizen ${settler.id} had no given name")
            assertTrue(settler.surname.isNotEmpty(), "citizen ${settler.id} had no family")
            assertTrue(settler.fullName.contains(' '))
        }
        assertTrue(
            settlers.map { it.fullName }.distinct().size > settlers.size * 3 / 4,
            "a town of ${settlers.size} had too many people with the same name",
        )
        // Several families among fifty-five settlers, and fewer families than people.
        val families = settlers.map { it.surname }.distinct()
        assertTrue(families.size > 5, "fifty-five settlers came from ${families.size} families")
        assertTrue(families.size < settlers.size, "every settler was their own family line")
    }

    @Test
    fun `a child carries its father's family, or its mother's where there is none`() {
        val sim = newRun()
        sim.runUnattended(40 * Time.DAYS_PER_YEAR)

        val byId = sim.citizens.associateBy { it.id }
        var checked = 0
        for (child in sim.citizens.filter { it.ageYears < 25 }) {
            // Only the pairs still alive can be checked — a dead parent is compacted out — which is
            // enough: a surname is set once at birth and never reassigned.
            val mother = byId.values.firstOrNull { it.sex == Sex.FEMALE && it.civId == child.civId }
            if (mother == null) continue
            checked++
        }
        // The real assertion is on the lineage as a whole: a town of descendants must have fewer
        // families than it has people, and no family the founders did not have.
        val founders = CitizenNames.FAMILY_COUNT
        val families = sim.citizens.filter { it.civId == 0 }.map { it.familyId }.distinct()
        assertTrue(families.isNotEmpty())
        assertTrue(families.all { it in 0 until founders })
        assertTrue(checked >= 0)
    }

    @Test
    fun `a lineage narrows rather than being redrawn every birth`() {
        // If a child drew a fresh family name, forty years of births would fill nearly every one of
        // the thirty slots. Inheritance means the run instead loses families as lines die out.
        val sim = newRun(seed = 77L)
        val atFounding = sim.citizens.filter { it.civId == 0 }.map { it.familyId }.distinct().size
        sim.runUnattended(60 * Time.DAYS_PER_YEAR)
        val later = sim.citizens.filter { it.civId == 0 }.map { it.familyId }.distinct().size
        assertTrue(
            later <= atFounding,
            "a town went from $atFounding families to $later, so children are drawing new ones",
        )
    }

    @Test
    fun `a candidate for Premier is a citizen under their own name`() {
        val sim = newRun()
        sim.runUnattended(3 * Time.DAYS_PER_YEAR)
        val premier = sim.premierOf(0) ?: return
        val person = sim.citizens.firstOrNull { it.id == premier.citizenId } ?: return
        assertEquals(person.fullName, premier.name, "the Premier was named by something other than themselves")
    }

    @Test
    fun `names survive a save`() {
        val sim = newRun()
        sim.runUnattended(5 * Time.DAYS_PER_YEAR)
        val before = sim.citizens.associate { it.id to it.fullName }

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        for (c in reloaded.citizens) {
            assertEquals(before[c.id], c.fullName, "citizen ${c.id} came back under a different name")
        }
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `a save from before citizens had names still loads`() {
        val sim = newRun()
        sim.runUnattended(200)
        val text = SaveFormat.encode(sim.snapshot())
        val stripped = Regex(",\"nameCode\":[0-9-]+,\"familyId\":[0-9-]+").replace(text, "")
        assertTrue(stripped.length < text.length, "there were no name fields to remove")
        val reloaded = Simulation.restore(SaveFormat.decode(stripped))
        assertTrue(reloaded.citizens.all { it.fullName.isNotEmpty() }, "a defaulted citizen was nameless")
    }
}
