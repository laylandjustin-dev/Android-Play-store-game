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
enum class Archetype(
    val label: String,
    val blurb: String,
    val shape: MarkerShape,
    /**
     * The unit only this people fields, once they have the armoury for it.
     *
     * [BALANCED] fields the generic [UnitKind.MEN_AT_ARMS], because a people with no speciality
     * should not have a speciality unit — that is the honest reading of "best at nothing".
     */
    val uniqueUnit: UnitKind = UnitKind.MEN_AT_ARMS,
    /** Multiplier on this civ's military strength. */
    val strengthBonus: Double = 1.0,
    /** Multiplier on how fast this civ's armies cross the map. */
    val marchBonus: Double = 1.0,
    /** Multiplier on the wood and stone a building costs this civ. */
    val buildCostBonus: Double = 1.0,
    /** Multiplier on how fast this civ's buildings fall into disrepair. */
    val decayBonus: Double = 1.0,
    /** Multiplier on the integrity of this civ's walls, which is what a siege must chew through. */
    val wallBonus: Double = 1.0,
    /** Multiplier on this people's resistance to disease. */
    val diseaseBonus: Double = 1.0,
) {
    /** Speed. Quick hands, long legs, first to everything. */
    SWIFT(
        "Swift", "quick hands and long legs", MarkerShape.CHEVRON,
        uniqueUnit = UnitKind.LANCERS, marchBonus = 1.45, strengthBonus = 1.05,
    ),

    /** Health. They bury fewer of their own and shrug off the plagues that thin a neighbour. */
    HARDY(
        "Hardy", "they bury fewer of their own", MarkerShape.CROSS,
        uniqueUnit = UnitKind.SHIELDBEARERS, strengthBonus = 1.10, diseaseBonus = 1.50,
    ),

    /** Hunting. The wild feeds them, and the same skill points at their neighbours. */
    WILD(
        "Wild", "the wild feeds them, and arms them", MarkerShape.STAR,
        uniqueUnit = UnitKind.BEASTMASTERS, strengthBonus = 1.25,
    ),

    /** Elements. The seasons barely touch them. */
    WEATHERED(
        "Weathered", "the seasons barely touch them", MarkerShape.DIAMOND,
        uniqueUnit = UnitKind.WALLWRIGHTS, decayBonus = 0.45, wallBonus = 1.55,
    ),

    /** Farming. Patient, rooted, and fed. */
    ROOTED(
        "Rooted", "patient, rooted and fed", MarkerShape.SQUARE,
        uniqueUnit = UnitKind.REAPERS, buildCostBonus = 0.90, decayBonus = 0.75,
    ),

    /** Gathering. Timber, stone, and something built on every ridge. */
    BUILDERS(
        "Builders", "timber, stone, and always raising something", MarkerShape.CHEVRON_DOWN,
        uniqueUnit = UnitKind.SAPPERS, buildCostBonus = 0.80, wallBonus = 1.30,
    ),

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
                Trait.GATHERING -> BUILDERS
            }
        }

        /** How far above base a trait must stand before it names the people. */
        const val DOMINANCE_MARGIN = 3

        /** Looked up by name, tolerantly, for decoding a save. See AD-57 on enum names. */
        fun byNameOrNull(name: String): Archetype? = entries.firstOrNull { it.name == name }
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

    /** Two arrowheads pointing south, so it cannot be mistaken for CHEVRON at a glance. */
    CHEVRON_DOWN,
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

            CHEVRON_DOWN -> for (o in 0..radius) {
                cells += o to (radius - o)
                cells += -o to (radius - o)
                cells += o to (1 - o)
                cells += -o to (1 - o)
            }
        }
        return cells.filterNot { it.first == 0 && it.second == 0 }.distinct()
    }
}
