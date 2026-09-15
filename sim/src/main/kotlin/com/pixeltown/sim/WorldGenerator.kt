package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Terrain as TerrainConfig
import com.pixeltown.sim.GameConfig.World as WorldConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A generated world plus the starting sites chosen for the five civilisations. */
class GeneratedWorld(
    val world: World,
    /** Cell index of each civ's starting site, indexed by civ id. */
    val civStartSites: IntArray,
)

/**
 * Procedural island generation, driven entirely by the run's [SimRandom].
 *
 * Pipeline: value-noise elevation and moisture, radial falloff to make an island, normalise,
 * classify into terrain, carve rivers by steepest descent from the wettest peaks, seed per-cell
 * fertility and wild game, then choose well-separated starting sites.
 */
object WorldGenerator {

    fun generate(rng: SimRandom, width: Int = WorldConfig.WIDTH, height: Int = WorldConfig.HEIGHT): GeneratedWorld {
        val world = World(width, height)
        val elevationNoise = ValueNoise(rng)
        val moistureNoise = ValueNoise(rng)

        sampleFields(world, elevationNoise, moistureNoise)
        normalise(world.elevation)
        normalise(world.moisture)
        classify(world)
        carveRivers(world)
        seedCellState(world, rng)

        val sites = chooseStartSites(world, rng)
        return GeneratedWorld(world, sites)
    }

    // ------------------------------------------------------------------ fields

    private fun sampleFields(world: World, elevationNoise: ValueNoise, moistureNoise: ValueNoise) {
        val cx = (world.width - 1) / 2.0
        val cy = (world.height - 1) / 2.0

        for (y in 0 until world.height) {
            for (x in 0 until world.width) {
                val i = world.index(x, y)
                val raw = elevationNoise.fractal(x.toDouble(), y.toDouble())
                world.elevation[i] = (raw * islandFalloff(x, y, cx, cy)).toFloat()
                // Moisture is sampled at a lower frequency so wet regions are broad, not speckled.
                world.moisture[i] = moistureNoise
                    .fractal(x.toDouble(), y.toDouble(), frequency = WorldConfig.NOISE_BASE_FREQUENCY * 0.6)
                    .toFloat()
            }
        }
    }

    /**
     * Multiplies elevation down toward the map edge so the landmass is an island rather than a
     * continent clipped by the border.
     *
     * Distance is measured as a square (Chebyshev) mask, not a radius: a radial falloff only
     * reaches zero at the four corners, which left land running off the middle of each edge. The
     * square mask reaches zero along every border, so the coastline is always drawn by the noise
     * rather than by the map bounds. Flat inside [WorldConfig.ISLAND_FALLOFF_START].
     */
    private fun islandFalloff(x: Int, y: Int, cx: Double, cy: Double): Double {
        val distance = max(abs(x - cx) / cx, abs(y - cy) / cy)
        if (distance <= WorldConfig.ISLAND_FALLOFF_START) return 1.0
        val t = (distance - WorldConfig.ISLAND_FALLOFF_START) / (1.0 - WorldConfig.ISLAND_FALLOFF_START)
        return max(0.0, 1.0 - t.pow(WorldConfig.ISLAND_FALLOFF_POWER))
    }

    /** Rescales a field to exactly `[0, 1]` so the thresholds in [GameConfig] are meaningful. */
    private fun normalise(field: FloatArray) {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in field) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = hi - lo
        if (span <= 0f) {
            field.fill(0f)
            return
        }
        for (i in field.indices) field[i] = (field[i] - lo) / span
    }

    // ------------------------------------------------------------------ terrain

    /**
     * Classifies by quantile of the elevation and moisture fields rather than by absolute value,
     * so every seed yields a comparable amount of usable land (see
     * [WorldConfig.TARGET_LAND_FRACTION]) while the island's shape still varies freely.
     */
    private fun classify(world: World) {
        val seaLevel = quantile(world.elevation, 1.0 - WorldConfig.TARGET_LAND_FRACTION)

        // Land thresholds are quantiles *of the land*, so they ignore however much ocean there is.
        val landElevations = world.elevation.filter { it >= seaLevel }.toFloatArray()
        val beachLevel = quantile(landElevations, WorldConfig.BEACH_SHARE_OF_LAND)
        val hillLevel = quantile(
            landElevations,
            1.0 - WorldConfig.HILL_SHARE_OF_LAND - WorldConfig.MOUNTAIN_SHARE_OF_LAND,
        )
        val mountainLevel = quantile(landElevations, 1.0 - WorldConfig.MOUNTAIN_SHARE_OF_LAND)

        val landMoisture = FloatArray(landElevations.size)
        var m = 0
        for (i in 0 until world.cellCount) {
            if (world.elevation[i] >= seaLevel) landMoisture[m++] = world.moisture[i]
        }
        val marshMoisture = quantile(landMoisture, 1.0 - WorldConfig.MARSH_SHARE_OF_LAND)
        val forestMoisture = quantile(
            landMoisture,
            1.0 - WorldConfig.MARSH_SHARE_OF_LAND - WorldConfig.FOREST_SHARE_OF_LAND,
        )

        for (i in 0 until world.cellCount) {
            val e = world.elevation[i]
            val moist = world.moisture[i]
            val type = when {
                e < seaLevel -> TerrainType.OCEAN
                e < beachLevel -> TerrainType.BEACH
                e >= mountainLevel -> TerrainType.MOUNTAIN
                e >= hillLevel -> TerrainType.HILL
                moist >= marshMoisture -> TerrainType.MARSH
                moist >= forestMoisture -> TerrainType.FOREST
                else -> TerrainType.PLAIN
            }
            world.setTerrain(i, type)
        }
    }

    /** The value at [fraction] through the sorted field. Copies, so the field order is untouched. */
    private fun quantile(field: FloatArray, fraction: Double): Float {
        if (field.isEmpty()) return 0f
        val sorted = field.copyOf()
        sorted.sort()
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /**
     * Rivers run from the wettest high ground to the sea by steepest descent. A walk that reaches
     * a local minimum stops there — a short river ending in a marsh is a believable outcome and
     * cheaper than flood-filling basins.
     */
    private fun carveRivers(world: World) {
        val flow = smoothed(world, WorldConfig.RIVER_FLOW_SMOOTHING_PASSES)
        for (source in findRiverSources(world)) {
            var current = source
            var length = 0
            val visited = HashSet<Int>()
            while (length < WorldConfig.RIVER_MAX_LENGTH && visited.add(current)) {
                if (world.terrainAt(current) == TerrainType.OCEAN) break
                // Mountain peaks keep their rock face; the river becomes visible below them.
                if (world.terrainAt(current) != TerrainType.MOUNTAIN) {
                    world.setTerrain(current, TerrainType.RIVER)
                }
                val next = lowestNeighbour(world, current, flow) ?: break
                // A small uphill breach is allowed so one noise pit does not end the river.
                if (flow[next] >= flow[current] + WorldConfig.RIVER_BREACH_TOLERANCE) break
                current = next
                length++
            }
        }
    }

    /** Box-blurred copy of the elevation field, used only for flow routing. */
    private fun smoothed(world: World, passes: Int): FloatArray {
        var src = world.elevation.copyOf()
        var dst = FloatArray(world.cellCount)
        repeat(passes) {
            for (y in 0 until world.height) {
                for (x in 0 until world.width) {
                    var sum = 0f
                    var n = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (!world.inBounds(nx, ny)) continue
                            sum += src[world.index(nx, ny)]
                            n++
                        }
                    }
                    dst[world.index(x, y)] = sum / n
                }
            }
            val swap = src
            src = dst
            dst = swap
        }
        return src
    }

    /**
     * The wettest cells above [WorldConfig.RIVER_MIN_SOURCE_ELEVATION], spread out so that all the
     * sources are not drawn from the same ridge. Chosen by sort order, not randomly, so the river
     * layout is a pure function of the noise fields.
     */
    private fun findRiverSources(world: World): List<Int> {
        val distanceToOcean = distanceToOcean(world)
        val candidates = ArrayList<Int>()
        for (i in 0 until world.cellCount) {
            if (distanceToOcean[i] >= WorldConfig.RIVER_SOURCE_MIN_DISTANCE_FROM_OCEAN) candidates.add(i)
        }
        if (candidates.isEmpty()) return emptyList()

        val score = DoubleArray(world.cellCount)
        for (i in candidates) {
            score[i] = distanceToOcean[i] + WorldConfig.RIVER_SOURCE_MOISTURE_WEIGHT * world.moisture[i]
        }
        candidates.sortWith(compareByDescending<Int> { score[it] }.thenBy { it })

        val chosen = ArrayList<Int>(WorldConfig.RIVER_SOURCE_COUNT)
        val minSeparation = world.width / WorldConfig.RIVER_SOURCE_COUNT
        for (candidate in candidates) {
            if (chosen.size >= WorldConfig.RIVER_SOURCE_COUNT) break
            val far = chosen.none { chebyshev(world, it, candidate) < minSeparation }
            if (far) chosen.add(candidate)
        }
        return chosen
    }

    /** Breadth-first distance in cells from every land cell to the nearest ocean cell. */
    private fun distanceToOcean(world: World): IntArray {
        val distance = IntArray(world.cellCount) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (i in 0 until world.cellCount) {
            if (world.terrainAt(i) == TerrainType.OCEAN) {
                distance[i] = 0
                queue.addLast(i)
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val x = current % world.width
            val y = current / world.width
            for (n in World.NEIGHBOUR_DX.indices) {
                val nx = x + World.NEIGHBOUR_DX[n]
                val ny = y + World.NEIGHBOUR_DY[n]
                if (!world.inBounds(nx, ny)) continue
                val ni = world.index(nx, ny)
                if (distance[ni] != Int.MAX_VALUE) continue
                distance[ni] = distance[current] + 1
                queue.addLast(ni)
            }
        }
        return distance
    }

    private fun lowestNeighbour(world: World, index: Int, field: FloatArray): Int? {
        val x = index % world.width
        val y = index / world.width
        var best = -1
        var bestElevation = Float.MAX_VALUE
        for (n in World.NEIGHBOUR_DX.indices) {
            val nx = x + World.NEIGHBOUR_DX[n]
            val ny = y + World.NEIGHBOUR_DY[n]
            if (!world.inBounds(nx, ny)) continue
            val ni = world.index(nx, ny)
            if (field[ni] < bestElevation) {
                bestElevation = field[ni]
                best = ni
            }
        }
        return if (best >= 0) best else null
    }

    // ------------------------------------------------------------------ cell state

    private fun seedCellState(world: World, rng: SimRandom) {
        for (i in 0 until world.cellCount) {
            val type = world.terrainAt(i)
            val baseFertility = TerrainConfig.FERTILITY.getValue(type)
            val baseGame = TerrainConfig.WILD_GAME.getValue(type)

            // River banks are the best farmland on the map: fertility is boosted near fresh water.
            val riverBonus = if (baseFertility > 0.0 && adjacentToRiver(world, i)) 0.15 else 0.0

            world.fertility[i] = jitter(rng, baseFertility + riverBonus, TerrainConfig.FERTILITY_JITTER)
            world.wildGame[i] = jitter(rng, baseGame, TerrainConfig.WILD_GAME_JITTER)
        }
    }

    private fun jitter(rng: SimRandom, base: Double, amount: Double): Float {
        if (base <= 0.0) return 0f
        val varied = base + rng.nextDouble(-amount, amount)
        return min(1.0, max(0.0, varied)).toFloat()
    }

    private fun adjacentToRiver(world: World, index: Int): Boolean {
        val x = index % world.width
        val y = index / world.width
        for (n in World.NEIGHBOUR_DX.indices) {
            val nx = x + World.NEIGHBOUR_DX[n]
            val ny = y + World.NEIGHBOUR_DY[n]
            if (world.inBounds(nx, ny) && world.terrainAt(nx, ny) == TerrainType.RIVER) return true
        }
        return false
    }

    // ------------------------------------------------------------------ start sites

    /**
     * Picks one site per civ: liveable ground with good fertility and game nearby, kept at least
     * [WorldConfig.MIN_CIV_START_SEPARATION] apart. If the island is too small or too fragmented
     * to hold five well-separated sites, the separation requirement is relaxed in steps rather
     * than failing — a cramped map should still be playable.
     */
    private fun chooseStartSites(world: World, rng: SimRandom): IntArray {
        // Rivals must be reachable on foot (armies walk across the map in M5), so every civ is
        // placed on the main landmass — never stranded on an offshore islet.
        val mainland = mainlandMask(world)
        val scored = ArrayList<Pair<Int, Double>>()
        for (i in 0 until world.cellCount) {
            if (!mainland[i] || !world.isBuildable(i)) continue
            val score = siteScore(world, i) + rng.nextDouble(0.0, WorldConfig.CIV_START_SCORE_JITTER)
            if (score > 0.0) scored.add(i to score)
        }
        scored.sortWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })

        var separation = WorldConfig.MIN_CIV_START_SEPARATION
        while (separation >= 4) {
            val sites = ArrayList<Int>(WorldConfig.TOTAL_CIV_COUNT)
            for ((index, _) in scored) {
                if (sites.size >= WorldConfig.TOTAL_CIV_COUNT) break
                if (sites.none { chebyshev(world, it, index) < separation }) sites.add(index)
            }
            if (sites.size == WorldConfig.TOTAL_CIV_COUNT) return sites.toIntArray()
            separation -= 2
        }
        // Degenerate map: fall back to the best distinct cells available.
        return scored.take(WorldConfig.TOTAL_CIV_COUNT).map { it.first }.toIntArray()
    }

    /** Marks the cells of the largest connected land component. */
    private fun mainlandMask(world: World): BooleanArray {
        val seen = BooleanArray(world.cellCount)
        var best = BooleanArray(world.cellCount)
        var bestSize = 0
        for (start in 0 until world.cellCount) {
            if (seen[start] || !world.isLand(start)) continue
            val component = BooleanArray(world.cellCount)
            var size = 0
            val queue = ArrayDeque<Int>()
            queue.addLast(start)
            seen[start] = true
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component[current] = true
                size++
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
            if (size > bestSize) {
                bestSize = size
                best = component
            }
        }
        return best
    }

    /** Sum of fertility and wild game in a window around the cell — how liveable it is. */
    private fun siteScore(world: World, index: Int): Double {
        val x = index % world.width
        val y = index / world.width
        val r = WorldConfig.CIV_START_SCORE_RADIUS
        var score = 0.0
        for (dy in -r..r) {
            for (dx in -r..r) {
                val nx = x + dx
                val ny = y + dy
                if (!world.inBounds(nx, ny)) continue
                val ni = world.index(nx, ny)
                score += world.fertility[ni] + 0.5 * world.wildGame[ni]
            }
        }
        return score
    }

    private fun chebyshev(world: World, a: Int, b: Int): Int {
        val ax = a % world.width
        val ay = a / world.width
        val bx = b % world.width
        val by = b / world.width
        return max(abs(ax - bx), abs(ay - by))
    }
}
