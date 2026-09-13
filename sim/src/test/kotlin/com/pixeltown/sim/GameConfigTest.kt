package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Traits
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Guards the invariants the design document states about the constants file. */
class GameConfigTest {

    @Test
    fun `trait allocation budget is reachable and bounded`() {
        val floor = Traits.BASE_VALUE * Traits.COUNT
        val ceiling = Traits.MAX_PER_TRAIT * Traits.COUNT
        assertTrue(floor + Traits.ALLOCATION_POINTS <= ceiling)
        assertTrue(Traits.MAX_PER_TRAIT > Traits.BASE_VALUE)
    }

    @Test
    fun `survival weights sum to the score maximum`() {
        val sum = GameConfig.Survival.let {
            it.W_HEALTH + it.W_NUTRITION + it.W_SHELTER + it.W_SAFETY + it.W_CARE + it.W_MORALE
        }
        assertEquals(GameConfig.Survival.MAX, sum, absoluteTolerance = 1e-9)
    }

    @Test
    fun `derived trait formulas match the design document`() {
        assertEquals(1.75, Traits.WORK_MULT_BASE + Traits.WORK_MULT_PER_SPEED * 8, 1e-9)
        assertEquals(72.0, Traits.LIFESPAN_YEARS_BASE + Traits.LIFESPAN_YEARS_PER_HEALTH * 8, 1e-9)
        assertEquals(1.84, Traits.HUNT_YIELD_BASE + Traits.HUNT_YIELD_PER_HUNTING * 8, 1e-9)
        assertEquals(1.78, Traits.FARM_YIELD_BASE + Traits.FARM_YIELD_PER_FARMING * 8, 1e-9)
    }

    @Test
    fun `every terrain type has fertility and wild game defined`() {
        for (t in TerrainType.entries) {
            assertTrue(GameConfig.Terrain.FERTILITY.containsKey(t), "fertility missing for $t")
            assertTrue(GameConfig.Terrain.WILD_GAME.containsKey(t), "wild game missing for $t")
        }
    }

    @Test
    fun `every enum keyed table is complete`() {
        for (t in Temperament.entries) assertTrue(GameConfig.Politics.TEMPERAMENT_DEVIATION.containsKey(t))
        for (p in Personality.entries) assertTrue(GameConfig.Rivals.PERSONALITY_BIAS.containsKey(p))
        for (e in EndState.entries) assertTrue(GameConfig.Meta.END_STATE_BONUS.containsKey(e))
    }

    @Test
    fun `purchased allocation points are capped at four`() {
        assertEquals(4, Traits.MAX_PURCHASED_ALLOCATION_POINTS)
        assertEquals(
            Traits.MAX_PURCHASED_ALLOCATION_POINTS,
            GameConfig.Meta.ALLOCATION_POINT_UPGRADE_COSTS.size,
        )
        // Costs must be strictly increasing so the fourth point is a real commitment.
        val costs = GameConfig.Meta.ALLOCATION_POINT_UPGRADE_COSTS
        for (i in 1 until costs.size) assertTrue(costs[i] > costs[i - 1])
    }

    @Test
    fun `tech tier six is a genuine grind`() {
        val total = (1..GameConfig.Tech.MAX_TIER).sumOf {
            GameConfig.Tech.COST_BASE * Math.pow(GameConfig.Tech.COST_GROWTH, it.toDouble())
        }
        assertTrue(total > 20_000, "total knowledge to tier 6 was $total")
    }

    @Test
    fun `world is the documented 128 by 128 grid`() {
        assertEquals(128, GameConfig.World.WIDTH)
        assertEquals(128, GameConfig.World.HEIGHT)
        assertEquals(360, GameConfig.Time.DAYS_PER_YEAR)
    }
}
