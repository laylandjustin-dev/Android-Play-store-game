package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Economy
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Traits as TraitConfig

/** Health and Lifestyle both reach the birth rate, from opposite directions (AD-61). */
class FertilityTest {

    @Test
    fun `a Health-three people conceive at exactly the documented rate`() {
        // Measured from the base value, so every table in CLAUDE.md still reads true.
        val base = TraitAllocation.of(3, TraitConfig.BASE_VALUE, 3, 4, 8)
        assertEquals(1.0, base.fertilityMultiplier, 0.0001)
    }

    @Test
    fun `health moves fertility in both directions and never to zero`() {
        val frail = TraitAllocation.of(3, 1, 3, 4, 8)
        val hale = TraitAllocation.of(3, 8, 3, 4, 5)
        assertTrue(frail.fertilityMultiplier < 1.0, "a frail people were not less fertile")
        assertTrue(hale.fertilityMultiplier > 1.0, "a healthy people were not more fertile")
        assertTrue(frail.fertilityMultiplier > 0.0, "a trait made a people barren outright")
    }

    @Test
    fun `lifestyle buildings raise the birth rate, and the sum is capped`() {
        val none = CivEffects()
        assertEquals(0.0, none.fertilityBonus, 0.0001)

        // One plaza raises it; twenty must not, or the cheapest category in the game would double
        // the population curve on its own.
        fun plazas(count: Int) = BuildingSystem.aggregate(
            (1..count).map { Building(it, BuildingType.PLAZA, 0, 2, 2).also { b -> b.addProgress(1e9) } },
        )
        assertTrue(plazas(1).fertilityBonus > 0.0, "a plaza raised nothing")
        assertTrue(plazas(4).fertilityBonus > plazas(1).fertilityBonus, "a second plaza added nothing")
        assertEquals(
            Economy.MAX_LIFESTYLE_FERTILITY_BONUS,
            plazas(20).fertilityBonus,
            0.0001,
            "the lifestyle fertility bonus is not capped",
        )
    }

    @Test
    fun `a healthier people really does out-breed a frail one`() {
        fun birthsAt(health: Int): Int {
            val sim = Simulation.newRun(
                RunConfig(seed = 404L, traits = TraitAllocation.of(3, health, 3, 4, 8), civCount = 1),
            )
            sim.runUnattended(40 * Time.DAYS_PER_YEAR)
            return sim.civ(0).totalBirths
        }
        val frail = birthsAt(1)
        val hale = birthsAt(8)
        assertTrue(hale > frail, "a Health-8 people bore $hale children against a Health-1 people's $frail")
    }
}
