package com.pixeltown.sim

/**
 * ARGB colours for the pixel renderer, as plain ints.
 *
 * Lives in `:sim` rather than the app so that palettes are data — swappable by the
 * `palette_pack` entitlement later — and so the renderer has no colour literals in it.
 * Terrain is muted earth tones; civs are bright so citizens read as the brightest thing on
 * screen, with the player in warm gold.
 */
object Palette {

    const val VOID = 0xFF000000.toInt()

    /** Terrain colours, indexed by [TerrainType.ordinal]. */
    val TERRAIN: IntArray = IntArray(TerrainType.entries.size).also { c ->
        c[TerrainType.OCEAN.ordinal] = 0xFF14202E.toInt()
        c[TerrainType.BEACH.ordinal] = 0xFF6E6244.toInt()
        c[TerrainType.PLAIN.ordinal] = 0xFF636B39.toInt()
        c[TerrainType.FOREST.ordinal] = 0xFF22361F.toInt()
        c[TerrainType.HILL.ordinal] = 0xFF5A5344.toInt()
        c[TerrainType.MOUNTAIN.ordinal] = 0xFF6B6B6B.toInt()
        c[TerrainType.RIVER.ordinal] = 0xFF2A4763.toInt()
        c[TerrainType.MARSH.ordinal] = 0xFF3E4A45.toInt()
    }

    /** Civ colours, indexed by civ id. 0 is the player: warm gold. */
    val CIV: IntArray = intArrayOf(
        0xFFF2C14E.toInt(), // player — warm gold
        0xFFD4443C.toInt(), // crimson
        0xFF3FA8A0.toInt(), // teal
        0xFF8A5BD6.toInt(), // violet
        0xFF9FD16B.toInt(), // pale green
    )

    /** Building accent colours, indexed by [BuildingCategory.ordinal]. */
    val BUILDING: IntArray = IntArray(BuildingCategory.entries.size).also { c ->
        c[BuildingCategory.FARMS.ordinal] = 0xFFC9A227.toInt()
        c[BuildingCategory.HEALTH.ordinal] = 0xFFE8E8E8.toInt()
        c[BuildingCategory.MILITARY.ordinal] = 0xFF9A3B2F.toInt()
        c[BuildingCategory.TECH.ordinal] = 0xFF4C7BD1.toInt()
        c[BuildingCategory.LIFESTYLE.ordinal] = 0xFFC96FA8.toInt()
    }

    fun terrainColor(terrain: TerrainType): Int = TERRAIN[terrain.ordinal]

    fun civColor(civId: Int): Int = CIV[civId % CIV.size]

    /**
     * Colours the player may choose from, brightest-first. A fixed set rather than a free picker:
     * every one of these is tested against the terrain palette, and a player who picked, say, a
     * forest green would lose their own people against the trees.
     */
    val PLAYER_CHOICES: IntArray = intArrayOf(
        0xFFF2C14E.toInt(), // gold (the default)
        0xFFEF6F4A.toInt(), // ember
        0xFF6FC2F2.toInt(), // ice
        0xFFE85D9B.toInt(), // magenta
        0xFF9FD16B.toInt(), // lime
        0xFFB18CF0.toInt(), // lilac
        0xFFE8DCB8.toInt(), // bone
        0xFF3FD9B0.toInt(), // jade
    )

    /**
     * Scales an ARGB colour's channels by [factor], clamped to `[0, 1]`. Used to dim citizens by
     * survival score, so a starving town visibly darkens, and to shade terrain by fertility.
     */
    fun scaleBrightness(argb: Int, factor: Float): Int {
        val f = when {
            factor <= 0f -> 0f
            factor >= 1f -> 1f
            else -> factor
        }
        val a = argb ushr 24 and 0xFF
        val r = ((argb ushr 16 and 0xFF) * f).toInt()
        val g = ((argb ushr 8 and 0xFF) * f).toInt()
        val b = ((argb and 0xFF) * f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
