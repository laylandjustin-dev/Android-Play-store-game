package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Traits as TraitConfig

/**
 * What a people *is*, derived from what they spent their points on.
 *
 * A civilisation's build is its identity, and until now it was invisible: five towns in five
 * colours, all drawn identically, and nothing on the map said which one farmed and which one
 * raided. An archetype gives each build a name and a shape, so the question "who is that?" has an
 * answer you can see at a glance rather than one you look up in a panel.
 *
 * The dominant trait decides it, with ties broken in trait order so the mapping is total and
 * deterministic — every legal allocation has exactly one archetype, including the even spread,
 * which gets its own.
 */
enum class Archetype(val label: String, val blurb: String, val shape: MarkerShape) {
    /** Speed. Quick hands, long legs, first to everything. */
    SWIFT("Swift", "quick hands and long legs", MarkerShape.CHEVRON),

    /** Health. They bury fewer of their own and shrug off the plagues that thin a neighbour. */
    HARDY("Hardy", "they bury fewer of their own", MarkerShape.CROSS),

    /** Hunting. The wild feeds them, and the same skill points at their neighbours. */
    WILD("Wild", "the wild feeds them, and arms them", MarkerShape.STAR),

    /** Elements. The seasons barely touch them. */
    WEATHERED("Weathered", "the seasons barely touch them", MarkerShape.DIAMOND),

    /** Farming. Patient, rooted, and fed. */
    ROOTED("Rooted", "patient, rooted and fed", MarkerShape.SQUARE),

    /** No dominant trait at all: good at everything, best at nothing. */
    BALANCED("Balanced", "good at everything, best at nothing", MarkerShape.RING),
    ;

    companion object {
        /**
         * The archetype of an allocation: the trait furthest above the base value, or [BALANCED]
         * when nothing stands out by at least [DOMINANCE_MARGIN].
         *
         * The margin is what stops a single stray point from renaming a people — 5/5/5/5/6 is still
         * a balanced town that happens to farm slightly better, not a farming civilisation.
         */
        fun of(traits: TraitAllocation): Archetype {
            var best = Trait.SPEED
            var bestValue = Int.MIN_VALUE
            for (trait in Trait.entries) {
                // Strictly greater keeps trait order as the tiebreak, which makes this total.
                if (traits[trait] > bestValue) {
                    bestValue = traits[trait]
                    best = trait
                }
            }
            if (bestValue - TraitConfig.BASE_VALUE < DOMINANCE_MARGIN) return BALANCED
            return when (best) {
                Trait.SPEED -> SWIFT
                Trait.HEALTH -> HARDY
                Trait.HUNTING -> WILD
                Trait.ELEMENTS -> WEATHERED
                Trait.FARMING -> ROOTED
            }
        }

        /** How far above base a trait must stand before it names the people. */
        const val DOMINANCE_MARGIN = 3
    }
}

/**
 * The glyph a civ's home site is marked with.
 *
 * Shape carries what colour cannot: a colour-blind player, or one looking at four rivals in four
 * similar hues on a phone, can still tell a Rooted town from a Wild one. Each shape is drawn as
 * single cells on the world grid — this is a pixel game, so a marker is a handful of lit pixels,
 * never an outline with a stroke width.
 */
enum class MarkerShape {
    /** A broken ring: four arcs with gaps at the diagonals. */
    RING,

    /** Four corner brackets, like a surveyed plot. */
    SQUARE,

    /** Points at the four compass directions. */
    DIAMOND,

    /** A plus through the centre, stopping short of it. */
    CROSS,

    /** Eight rays, alternating long and short. */
    STAR,

    /** Two arrowheads pointing north. */
    CHEVRON,
    ;

    /**
     * The cells this shape occupies at [radius], as offsets from the centre.
     *
     * Computed rather than tabulated so a radius change cannot leave a shape half-updated, and
     * returned as offsets so the renderer owns all the clipping and bounds logic in one place.
     * The centre itself is never included: a marker must not paint over the home cell.
     */
    fun offsets(radius: Int): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>(radius * 8)
        when (this) {
            // Four arcs with gaps at the diagonals, so it reads as a marker rather than as a wall
            // somebody built — and so it is not mistaken for the SQUARE brackets beside it.
            RING -> for (o in -radius + 1..radius - 1) {
                if (kotlin.math.abs(o) > radius - 2) continue
                cells += o to -radius
                cells += o to radius
                cells += -radius to o
                cells += radius to o
            }

            SQUARE -> for (o in 0..radius / 2) {
                // Corner brackets only, so it reads as a marked plot rather than a solid wall.
                cells += (-radius + o) to -radius
                cells += (radius - o) to -radius
                cells += (-radius + o) to radius
                cells += (radius - o) to radius
                cells += -radius to (-radius + o)
                cells += -radius to (radius - o)
                cells += radius to (-radius + o)
                cells += radius to (radius - o)
            }

            DIAMOND -> for (o in 0..radius) {
                val inner = radius - o
                cells += o to -inner
                cells += -o to -inner
                cells += o to inner
                cells += -o to inner
            }

            CROSS -> for (o in 2..radius) {
                cells += 0 to -o
                cells += 0 to o
                cells += -o to 0
                cells += o to 0
            }

            STAR -> {
                for (o in 2..radius) {
                    cells += 0 to -o
                    cells += 0 to o
                    cells += -o to 0
                    cells += o to 0
                }
                val short = radius / 2
                for (o in 1..short) {
                    cells += o to -o
                    cells += -o to -o
                    cells += o to o
                    cells += -o to o
                }
            }

            CHEVRON -> for (o in 0..radius) {
                cells += o to (-radius + o)
                cells += -o to (-radius + o)
                cells += o to (o - 1)
                cells += -o to (o - 1)
            }
        }
        return cells.filterNot { it.first == 0 && it.second == 0 }.distinct()
    }
}
