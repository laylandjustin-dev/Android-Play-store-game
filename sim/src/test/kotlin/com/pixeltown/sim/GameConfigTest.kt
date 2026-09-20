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
    fun `derived trait formulas match the design document at the top of the range`() {
        assertEquals(72.0, Traits.LIFESPAN_YEARS_BASE + Traits.LIFESPAN_YEARS_PER_HEALTH * 8, 1e-9)
        assertEquals(1.84, Traits.HUNT_YIELD_BASE + Traits.HUNT_YIELD_PER_HUNTING * 8, 1e-9)
        // M7 reshaped this one but deliberately kept its endpoint: a master farmer is still the
        // 1.78 the design specifies, and only the slope below them moved.
        assertEquals(1.78, Traits.FARM_YIELD_BASE + Traits.FARM_YIELD_PER_FARMING * 8, 1e-9)
    }

    @Test
    fun `the two M7 deviations from the design's formulas are the ones recorded`() {
        // Kept as an explicit guard rather than deleted: these are conscious deviations, measured
        // in the balance sweep and argued for in GameConfig, and a third one appearing silently
        // should fail the build.
        assertEquals(1.42, Traits.WORK_MULT_BASE + Traits.WORK_MULT_PER_SPEED * 8, 1e-9)
        assertEquals(2.08, Traits.MOVE_SPEED_BASE + Traits.MOVE_SPEED_PER_SPEED * 8, 1e-9)

        // Both narrowed rather than weakened: a Speed-1 people is better off than the design had
        // them, a Speed-8 people worse, because Speed was deciding viability rather than degree.
        assertTrue(Traits.WORK_MULT_BASE + Traits.WORK_MULT_PER_SPEED > 0.70, "speed 1 work rate fell")
        assertTrue(Traits.MOVE_SPEED_BASE + Traits.MOVE_SPEED_PER_SPEED > 0.78, "speed 1 movement fell")
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
    fun `world is the documented 400 by 400 grid`() {
        assertEquals(400, GameConfig.World.WIDTH)
        assertEquals(400, GameConfig.World.HEIGHT)
        assertEquals(360, GameConfig.Time.DAYS_PER_YEAR)
    }

    @Test
    fun `the map is square, and every civ has room to grow into it`() {
        // Not a restatement of the numbers above: these are the relationships the map size has to
        // keep. A square grid is what makes the island mask reach zero on every border (AD-11), and
        // five civs each building out to MAX_SITE_DISTANCE must fit with their separation intact.
        assertEquals(GameConfig.World.WIDTH, GameConfig.World.HEIGHT, "the island mask assumes a square grid")
        assertTrue(
            GameConfig.World.MIN_CIV_START_SEPARATION > GameConfig.Buildings.BASE_SITE_DISTANCE,
            "civs start closer together than the radius they immediately build into",
        )
        assertTrue(
            GameConfig.World.MIN_CIV_START_SEPARATION * GameConfig.World.TOTAL_CIV_COUNT <
                GameConfig.World.WIDTH * 2,
            "five civs at the required separation cannot fit on the map",
        )
    }

    @Test
    fun `building footprints match the documented sizes`() {
        // The brief fixes these: walls one cell, towers two, houses three, works and farms five to
        // six, and the civic buildings larger again. A footprint is visible on screen, so a wrong
        // one is a design error rather than a balance one.
        fun fp(type: BuildingType) = GameConfig.Buildings.spec(type).footprint
        assertEquals(1, fp(BuildingType.WALL), "a wall is one cell wide")
        assertEquals(2, fp(BuildingType.WATCHTOWER), "a watchtower is 2x2")
        for (house in listOf(BuildingType.HOUSING, BuildingType.HUT)) {
            assertEquals(3, fp(house), "$house is a 3x3 dwelling")
        }
        for (works in listOf(BuildingType.FIELD, BuildingType.BARRACKS, BuildingType.WORKSHOP)) {
            assertTrue(fp(works) in 5..6, "$works should be 5x5 to 6x6, not ${fp(works)}x${fp(works)}")
        }
        // And no footprint may exceed what the siting rules can actually place.
        for (type in BuildingType.entries) {
            assertTrue(
                fp(type) < GameConfig.Buildings.MAX_DISTANCE_FROM_OWN_BUILDING,
                "$type is wider than a town is allowed to spread in one step",
            )
        }
    }
}
