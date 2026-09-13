package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.World as WorldConfig

/**
 * The world grid: [width] x [height] cells, one citizen per cell, one cell per pixel.
 *
 * Stored as parallel arrays (struct-of-arrays) rather than a `Cell` object per tile. 16,384 cells
 * are read every frame by the renderer and swept repeatedly by the job and rival systems; flat
 * primitive arrays keep that cache-friendly and keep the save small.
 */
class World(
    val width: Int = WorldConfig.WIDTH,
    val height: Int = WorldConfig.HEIGHT,
) {
    val cellCount: Int = width * height

    /** [TerrainType] ordinals. */
    val terrain = ByteArray(cellCount)

    /** Generation inputs, kept because rivers, sites and the renderer all read them. */
    val elevation = FloatArray(cellCount)
    val moisture = FloatArray(cellCount)

    /** Mutable per-cell state. */
    val fertility = FloatArray(cellCount)
    val wildGame = FloatArray(cellCount)
    val ownerCivId = ByteArray(cellCount) { NO_CIV }
    val buildingId = IntArray(cellCount) { NONE }
    val occupantId = IntArray(cellCount) { NONE }

    fun index(x: Int, y: Int): Int = y * width + x

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun terrainAt(index: Int): TerrainType = TERRAIN_TYPES[terrain[index].toInt()]

    fun terrainAt(x: Int, y: Int): TerrainType = terrainAt(index(x, y))

    fun setTerrain(index: Int, type: TerrainType) {
        terrain[index] = type.ordinal.toByte()
    }

    fun isWater(index: Int): Boolean =
        terrain[index] == OCEAN_ORDINAL || terrain[index] == RIVER_ORDINAL

    fun isLand(index: Int): Boolean = terrain[index] != OCEAN_ORDINAL

    /** Walkable for citizens: anything that is not ocean and not bare mountain. */
    fun isWalkable(index: Int): Boolean =
        terrain[index] != OCEAN_ORDINAL && terrain[index] != MOUNTAIN_ORDINAL

    fun isBuildable(index: Int): Boolean = isWalkable(index) && terrain[index] != RIVER_ORDINAL

    fun isOccupied(index: Int): Boolean = occupantId[index] != NONE

    /** Counts cells of each terrain type. Used by tests and the Ledger screen. */
    fun terrainHistogram(): IntArray {
        val counts = IntArray(TERRAIN_TYPES.size)
        for (i in 0 until cellCount) counts[terrain[i].toInt()]++
        return counts
    }

    val landCellCount: Int
        get() {
            var n = 0
            for (i in 0 until cellCount) if (isLand(i)) n++
            return n
        }

    companion object {
        const val NONE = -1
        const val NO_CIV: Byte = -1

        private val TERRAIN_TYPES = TerrainType.entries.toTypedArray()
        private val OCEAN_ORDINAL = TerrainType.OCEAN.ordinal.toByte()
        private val RIVER_ORDINAL = TerrainType.RIVER.ordinal.toByte()
        private val MOUNTAIN_ORDINAL = TerrainType.MOUNTAIN.ordinal.toByte()

        /** The eight neighbour offsets, in a fixed order so iteration is deterministic. */
        val NEIGHBOUR_DX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val NEIGHBOUR_DY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    }
}
