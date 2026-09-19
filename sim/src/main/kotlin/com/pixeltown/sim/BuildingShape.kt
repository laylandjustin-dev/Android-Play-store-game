package com.pixeltown.sim

/**
 * The silhouette a building is drawn with, one per [BuildingCategory].
 *
 * What a town has built should be readable from the map rather than from a list. Colour alone
 * cannot carry it: five accent colours on a 128x128 island, at a zoom where a whole building is a
 * few pixels across, are hard to tell apart and impossible for a colour-blind player. A shape is
 * the second channel.
 *
 * The honest constraint is the grid. A building occupies `footprint` cells square, and three
 * quarters of the catalogue are tier 0 or 1 at footprint 2 — four pixels, which cannot hold a
 * pentagon. So each shape is defined at both sizes: at 3 and above the real figure is drawn, and at
 * 2 a reduced silhouette keeps whatever of it survives. The footprint is always fully painted, with
 * the shape in the category's accent and the remainder in a darker tone of it, so a building still
 * reads as one solid object while its outline says what it is.
 */
enum class BuildingShape {
    /** Farms: a pentagon, peaked like a roof over a full base. */
    PENTAGON,

    /** Health: a circle, the only shape with no corners at all. */
    CIRCLE,

    /** Military: a triangle. */
    TRIANGLE,

    /** Tech: a long rectangle, the only shape wider than it is tall. */
    RECTANGLE,

    /** Lifestyle: a hollow diamond — a circle's outline with its middle left open. */
    HOLLOW_DIAMOND,
    ;

    /**
     * Which cells of a [size] x [size] footprint are part of the figure, row-major from the top
     * left. Cells outside it are still painted, in a darker tone.
     *
     * Sizes above 3 scale the 3-wide figure by nearest neighbour rather than adding detail, so a
     * larger building is the same shape drawn bigger and never a different-looking one.
     */
    fun mask(size: Int): BooleanArray {
        if (size <= 1) return BooleanArray(size * size) { true }
        if (size == 2) return small()

        val rows = large()
        if (size == 3) return rows
        // Nearest-neighbour upscale of the 3x3 figure.
        return BooleanArray(size * size) { i ->
            val x = (i % size) * 3 / size
            val y = (i / size) * 3 / size
            rows[y * 3 + x]
        }
    }

    /**
     * Four pixels, which is what a tier 0 or 1 building gets.
     *
     * Pentagon and circle are both solid here — neither a peak nor a curve exists at this size —
     * so those two are told apart by their accent colour alone until the town builds a tier 2. The
     * other three keep a silhouette that survives the reduction.
     */
    private fun small(): BooleanArray = when (this) {
        PENTAGON, CIRCLE -> booleanArrayOf(true, true, true, true)
        // A corner cut away, which is as much of a wedge as four pixels can be.
        TRIANGLE -> booleanArrayOf(false, true, true, true)
        // One full row: the only shape that is wider than it is tall, even here.
        RECTANGLE -> booleanArrayOf(false, false, true, true)
        // Two opposite corners, so the middle reads as open.
        HOLLOW_DIAMOND -> booleanArrayOf(false, true, true, false)
    }

    /** The real figure, on a 3x3 grid. */
    private fun large(): BooleanArray = when (this) {
        // .#.   a peak
        // ###   over a solid base
        // ###
        PENTAGON -> booleanArrayOf(
            false, true, false,
            true, true, true,
            true, true, true,
        )
        // .#.   no corners
        // ###
        // .#.
        CIRCLE -> booleanArrayOf(
            false, true, false,
            true, true, true,
            false, true, false,
        )
        // ..#   a wedge rising to the right
        // .##
        // ###
        TRIANGLE -> booleanArrayOf(
            false, false, true,
            false, true, true,
            true, true, true,
        )
        // ...   a bar, wider than it is tall
        // ###
        // ...
        RECTANGLE -> booleanArrayOf(
            false, false, false,
            true, true, true,
            false, false, false,
        )
        // .#.   the circle's outline
        // #.#   with the middle left open
        // .#.
        HOLLOW_DIAMOND -> booleanArrayOf(
            false, true, false,
            true, false, true,
            false, true, false,
        )
    }

    companion object {
        /** The shape each category is drawn with. */
        fun of(category: BuildingCategory): BuildingShape = when (category) {
            BuildingCategory.FARMS -> PENTAGON
            BuildingCategory.HEALTH -> CIRCLE
            BuildingCategory.MILITARY -> TRIANGLE
            BuildingCategory.TECH -> RECTANGLE
            BuildingCategory.LIFESTYLE -> HOLLOW_DIAMOND
        }
    }
}
