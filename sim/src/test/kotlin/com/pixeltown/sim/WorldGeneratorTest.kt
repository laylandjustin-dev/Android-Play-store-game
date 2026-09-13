package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.World as WorldConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorldGeneratorTest {

    private val seeds = longArrayOf(1L, 7L, 42L, 1234L, 20260913L, -99L, Long.MAX_VALUE / 3)

    @Test
    fun `the same seed generates a byte-identical world`() {
        for (seed in seeds) {
            val a = WorldGenerator.generate(SimRandom(seed))
            val b = WorldGenerator.generate(SimRandom(seed))
            assertTrue(a.world.terrain.contentEquals(b.world.terrain), "terrain differs for seed $seed")
            assertTrue(a.world.elevation.contentEquals(b.world.elevation), "elevation differs for seed $seed")
            assertTrue(a.world.moisture.contentEquals(b.world.moisture), "moisture differs for seed $seed")
            assertTrue(a.world.fertility.contentEquals(b.world.fertility), "fertility differs for seed $seed")
            assertTrue(a.world.wildGame.contentEquals(b.world.wildGame), "wild game differs for seed $seed")
            assertTrue(a.civStartSites.contentEquals(b.civStartSites), "start sites differ for seed $seed")
        }
    }

    @Test
    fun `different seeds generate different worlds`() {
        val a = WorldGenerator.generate(SimRandom(1L)).world
        val b = WorldGenerator.generate(SimRandom(2L)).world
        assertTrue(!a.terrain.contentEquals(b.terrain))
    }

    @Test
    fun `every world is an island with a workable amount of land`() {
        for (seed in seeds) {
            val world = WorldGenerator.generate(SimRandom(seed)).world
            val landFraction = world.landCellCount.toDouble() / world.cellCount
            assertTrue(
                landFraction in 0.25..0.75,
                "seed $seed produced a land fraction of $landFraction",
            )
            // The border must be ocean: the landmass may not be clipped by the map edge.
            for (x in 0 until world.width) {
                assertEquals(TerrainType.OCEAN, world.terrainAt(x, 0), "seed $seed top edge")
                assertEquals(TerrainType.OCEAN, world.terrainAt(x, world.height - 1), "seed $seed bottom edge")
            }
            for (y in 0 until world.height) {
                assertEquals(TerrainType.OCEAN, world.terrainAt(0, y), "seed $seed left edge")
                assertEquals(TerrainType.OCEAN, world.terrainAt(world.width - 1, y), "seed $seed right edge")
            }
        }
    }

    @Test
    fun `worlds contain varied terrain including farmland and rivers`() {
        for (seed in seeds) {
            val world = WorldGenerator.generate(SimRandom(seed)).world
            val histogram = world.terrainHistogram()
            for (type in listOf(
                TerrainType.OCEAN,
                TerrainType.BEACH,
                TerrainType.PLAIN,
                TerrainType.FOREST,
                TerrainType.HILL,
                TerrainType.RIVER,
            )) {
                assertTrue(histogram[type.ordinal] > 0, "seed $seed has no $type")
            }
        }
    }

    @Test
    fun `rivers flow downhill`() {
        val world = WorldGenerator.generate(SimRandom(42L)).world
        // Every river cell must touch either another river cell or the ocean: no orphan puddles.
        for (i in 0 until world.cellCount) {
            if (world.terrainAt(i) != TerrainType.RIVER) continue
            val x = i % world.width
            val y = i / world.width
            var connected = false
            for (n in World.NEIGHBOUR_DX.indices) {
                val nx = x + World.NEIGHBOUR_DX[n]
                val ny = y + World.NEIGHBOUR_DY[n]
                if (!world.inBounds(nx, ny)) continue
                val t = world.terrainAt(nx, ny)
                if (t == TerrainType.RIVER || t == TerrainType.OCEAN) connected = true
            }
            assertTrue(connected, "orphan river cell at ($x, $y)")
        }
    }

    @Test
    fun `fields and cell state stay inside their documented ranges`() {
        for (seed in seeds) {
            val world = WorldGenerator.generate(SimRandom(seed)).world
            for (i in 0 until world.cellCount) {
                assertTrue(world.elevation[i] in 0f..1f, "elevation out of range")
                assertTrue(world.moisture[i] in 0f..1f, "moisture out of range")
                assertTrue(world.fertility[i] in 0f..1f, "fertility out of range")
                assertTrue(world.wildGame[i] in 0f..1f, "wild game out of range")
            }
            // Ocean and mountain are never farmable.
            for (i in 0 until world.cellCount) {
                val t = world.terrainAt(i)
                if (t == TerrainType.OCEAN || t == TerrainType.MOUNTAIN || t == TerrainType.RIVER) {
                    assertEquals(0f, world.fertility[i], "farmable $t cell")
                }
            }
        }
    }

    @Test
    fun `every civ gets a distinct buildable starting site`() {
        for (seed in seeds) {
            val generated = WorldGenerator.generate(SimRandom(seed))
            val sites = generated.civStartSites
            assertEquals(WorldConfig.TOTAL_CIV_COUNT, sites.size, "seed $seed site count")
            assertEquals(sites.size, sites.distinct().size, "seed $seed has duplicate sites")
            for (site in sites) {
                assertTrue(generated.world.isBuildable(site), "seed $seed placed a civ on unbuildable ground")
            }
        }
    }

    @Test
    fun `starting sites are kept apart`() {
        for (seed in seeds) {
            val generated = WorldGenerator.generate(SimRandom(seed))
            val world = generated.world
            val sites = generated.civStartSites
            var closest = Int.MAX_VALUE
            for (i in sites.indices) {
                for (j in i + 1 until sites.size) {
                    val d = max(
                        abs(sites[i] % world.width - sites[j] % world.width),
                        abs(sites[i] / world.width - sites[j] / world.width),
                    )
                    closest = minOf(closest, d)
                }
            }
            // The generator relaxes separation on cramped maps, but never below this.
            assertTrue(closest >= 4, "seed $seed put two civs $closest cells apart")
        }
    }

    @Test
    fun `the landmass is one connected island, not an archipelago`() {
        // Armies walk across the map as pixel columns (M5), so rivals must be reachable on foot.
        for (seed in seeds) {
            val world = WorldGenerator.generate(SimRandom(seed)).world
            val largest = landComponents(world).max()
            val share = largest.toDouble() / world.landCellCount
            assertTrue(share >= 0.85, "seed $seed has only $share of its land in one landmass")
        }
    }

    @Test
    fun `every starting site sits on the main landmass`() {
        for (seed in seeds) {
            val generated = WorldGenerator.generate(SimRandom(seed))
            val world = generated.world
            val mainland = largestLandComponentCells(world)
            for ((civId, site) in generated.civStartSites.withIndex()) {
                assertTrue(site in mainland, "seed $seed stranded civ $civId on an islet")
            }
        }
    }

    /** Sizes of the connected land components, largest not necessarily first. */
    private fun landComponents(world: World): List<Int> = componentCells(world).map { it.size }

    private fun largestLandComponentCells(world: World): Set<Int> =
        componentCells(world).maxBy { it.size }

    private fun componentCells(world: World): List<Set<Int>> {
        val seen = BooleanArray(world.cellCount)
        val components = ArrayList<Set<Int>>()
        for (start in 0 until world.cellCount) {
            if (seen[start] || !world.isLand(start)) continue
            val cells = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            queue.addLast(start)
            seen[start] = true
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                cells.add(current)
                val x = current % world.width
                val y = current / world.width
                for (n in World.NEIGHBOUR_DX.indices) {
                    val nx = x + World.NEIGHBOUR_DX[n]
                    val ny = y + World.NEIGHBOUR_DY[n]
                    if (!world.inBounds(nx, ny)) continue
                    val ni = world.index(nx, ny)
                    if (!seen[ni] && world.isLand(ni)) {
                        seen[ni] = true
                        queue.addLast(ni)
                    }
                }
            }
            components.add(cells)
        }
        return components
    }

    @Test
    fun `generation is not prohibitively slow`() {
        // A new run must not stall the UI: generation happens while the allocation screen is up.
        val start = System.nanoTime()
        repeat(5) { WorldGenerator.generate(SimRandom(it.toLong())) }
        val millisEach = (System.nanoTime() - start) / 1_000_000.0 / 5
        assertTrue(millisEach < 750, "generation took ${millisEach}ms per world")
    }
}
