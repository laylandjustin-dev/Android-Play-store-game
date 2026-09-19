package com.pixeltown.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Buildings as BuildingConfig
import com.pixeltown.sim.GameConfig.Time

/** Where a town may build: near its village, and joined to itself. */
class BuildingSitingTest {

    private fun chebyshev(ax: Int, ay: Int, bx: Int, by: Int) = max(abs(ax - bx), abs(ay - by))

    @Test
    fun `a town stays within reach of its own village`() {
        val sim = Simulation.newRun(RunConfig(seed = 1_000L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattended(120 * Time.DAYS_PER_YEAR)

        for (civ in sim.civs) {
            val homeX = civ.homeSite % sim.world.width
            val homeY = civ.homeSite / sim.world.width
            for (building in sim.buildingsOf(civ.id)) {
                val distance = chebyshev(building.x, building.y, homeX, homeY)
                assertTrue(
                    distance <= BuildingConfig.MAX_SITE_DISTANCE,
                    "${civ.name} built $distance cells from its village",
                )
            }
        }
    }

    @Test
    fun `every building after the first joins the town`() {
        val sim = Simulation.newRun(RunConfig(seed = 1_000L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattended(150 * Time.DAYS_PER_YEAR)

        for (civ in sim.civs) {
            val buildings = sim.buildingsOf(civ.id)
            if (buildings.size < 2) continue
            // Every building must be near *another* of the civ's, which is what makes a settlement
            // one place rather than a scatter. Deliberately not "near an earlier one": the list is
            // not guaranteed to be in placement order, and a building that anchored a neighbour can
            // since have fallen to ruin and been removed.
            for (building in buildings) {
                val others = buildings.filter { it !== building }
                val nearest = others.minOf { chebyshev(it.x, it.y, building.x, building.y) }
                assertTrue(
                    nearest <= BuildingConfig.MAX_DISTANCE_FROM_OWN_BUILDING,
                    "${civ.name} put a ${building.type} $nearest cells from anything else it owns",
                )
            }
        }
    }

    @Test
    fun `the first building needs nothing to be near`() {
        val world = World(64, 64)
        for (i in 0 until world.cellCount) world.setTerrain(i, TerrainType.PLAIN)
        val civ = Civilization(0, "Test", TraitAllocation.EVEN_SPREAD, Personality.ISOLATIONIST, world.index(32, 32))
        civ.population = 50

        assertNotNull(
            BuildingSystem.findSite(world, civ, footprint = 2, existing = emptyList()),
            "a civ with no buildings could not place its first",
        )
    }

    @Test
    fun `a site far from every existing building is refused`() {
        val world = World(64, 64)
        for (i in 0 until world.cellCount) world.setTerrain(i, TerrainType.PLAIN)
        val civ = Civilization(0, "Test", TraitAllocation.EVEN_SPREAD, Personality.ISOLATIONIST, world.index(32, 32))
        civ.population = 50

        // One building, parked far enough away that nothing inside the civ's reach is near it.
        val far = Building(id = 1, civId = 0, type = BuildingType.FIELD, x = 2, y = 2)
        BuildingSystem.place(world, far)
        val site = BuildingSystem.findSite(world, civ, footprint = 2, existing = listOf(far))

        if (site != null) {
            val distance = chebyshev(site % world.width, site / world.width, far.x, far.y)
            assertTrue(
                distance <= BuildingConfig.MAX_DISTANCE_FROM_OWN_BUILDING,
                "placed $distance cells from the only building the civ owns",
            )
        }
    }

    @Test
    fun `a town with nowhere legal left simply does not build`() {
        // Water everywhere but the home cell: nothing fits, and the answer is null rather than a
        // site on the sea.
        val world = World(32, 32)
        for (i in 0 until world.cellCount) world.setTerrain(i, TerrainType.OCEAN)
        val civ = Civilization(0, "Test", TraitAllocation.EVEN_SPREAD, Personality.ISOLATIONIST, world.index(16, 16))
        civ.population = 50
        assertNull(BuildingSystem.findSite(world, civ, footprint = 2, existing = emptyList()))
    }
}
