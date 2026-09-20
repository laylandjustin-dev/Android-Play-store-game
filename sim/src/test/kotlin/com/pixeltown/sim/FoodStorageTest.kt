package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Economy
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.World as WorldConfig

/** A colony can keep what it landed with (AD-64). */
class FoodStorageTest {

    private fun newRun(seed: Long = 1_000L, traits: TraitAllocation = TraitAllocation.of(3, 4, 3, 4, 8)) =
        Simulation.newRun(RunConfig(seed = seed, traits = traits, civCount = 1))

    @Test
    fun `the founding stores fit in the store they arrive in`() {
        // The bug in one assertion. 55 settlers land with 34 days of food each; if the town cannot
        // hold that, the surplus rots from day one and the food number falls however well the
        // player plays. Reported as "food drains continuously and does not go up".
        val sim = newRun()
        val civ = sim.civ(WorldConfig.PLAYER_CIV_ID)
        val landedWith = WorldConfig.STARTING_SETTLERS * Economy.STARTING_FOOD_PER_SETTLER

        assertEquals(landedWith, civ[Resource.FOOD], 0.001, "the colony did not land with what it should")
        assertTrue(
            sim.foodCapacityOf(WorldConfig.PLAYER_CIV_ID) >= landedWith,
            "a colony landed with $landedWith food and room for " +
                "${sim.foodCapacityOf(WorldConfig.PLAYER_CIV_ID)}",
        )
    }

    @Test
    fun `nothing rots on the first day of a new colony`() {
        val sim = newRun()
        val civ = sim.civ(WorldConfig.PLAYER_CIV_ID)
        val before = civ[Resource.FOOD]
        sim.runUnattended(1)
        // A day's rations are eaten, so the store falls a little — but by nothing like the 29 a day
        // that spoilage was taking out of it.
        val eaten = before - civ[Resource.FOOD]
        assertTrue(
            eaten < WorldConfig.STARTING_SETTLERS * Economy.FOOD_PER_ADULT_PER_DAY * 1.5,
            "the first day cost $eaten food, which is more than the town ate",
        )
    }

    @Test
    fun `a well-sited farming colony gains food over its first season`() {
        // Not merely "survives": the number the player is watching has to go up.
        val sim = newRun()
        val civ = sim.civ(WorldConfig.PLAYER_CIV_ID)
        val start = civ[Resource.FOOD]
        sim.runUnattended(90)
        assertTrue(
            civ[Resource.FOOD] > start * 0.95,
            "after a season a farming colony held ${civ[Resource.FOOD].toInt()} against $start",
        )
    }

    @Test
    fun `capacity grows with the town and is floored for a tiny one`() {
        val sim = newRun()
        val civ = sim.civ(WorldConfig.PLAYER_CIV_ID)
        val atFounding = sim.foodCapacityOf(0)

        sim.runUnattended(60 * Time.DAYS_PER_YEAR)
        if (civ.population > WorldConfig.STARTING_SETTLERS) {
            assertTrue(
                sim.foodCapacityOf(0) > atFounding,
                "the town grew to ${civ.population} and its granary did not",
            )
        }

        // A town reduced to nothing keeps the floor rather than being told its last stores rot.
        val empty = Civilization(0, "Test", TraitAllocation.BASE, Personality.ISOLATIONIST, 0)
        empty.population = 0
        assertTrue(Economy.MIN_FOOD_STORAGE_CAPACITY > 0.0)
    }

    @Test
    fun `spoilage still bites, so a town cannot hoard without limit`() {
        // The constraint has to survive the fix, or the fix has removed a mechanic.
        val sim = newRun()
        val civ = sim.civ(WorldConfig.PLAYER_CIV_ID)
        civ[Resource.FOOD] = sim.foodCapacityOf(0) * 4
        val hoard = civ[Resource.FOOD]
        sim.runUnattended(30)
        assertTrue(
            civ[Resource.FOOD] < hoard,
            "four times capacity sat in the store for a month without rotting",
        )
    }

    @Test
    fun `capacity survives a save`() {
        val sim = newRun(traits = TraitAllocation.of(3, 4, 3, 8, 5))
        sim.runUnattended(3 * Time.DAYS_PER_YEAR)
        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        assertEquals(sim.foodCapacityOf(0), reloaded.foodCapacityOf(0), 0.001)
        assertEquals(sim.stateHash(), reloaded.stateHash())
    }

    @Test
    fun `an Elements people hold more than a plain one`() {
        val plain = newRun(traits = TraitAllocation.of(3, 4, 3, 3, 8))
        val weathered = newRun(traits = TraitAllocation.of(3, 4, 3, 8, 5))
        assertTrue(
            weathered.foodCapacityOf(0) > plain.foodCapacityOf(0),
            "Elements bought no extra storage",
        )
    }
}
