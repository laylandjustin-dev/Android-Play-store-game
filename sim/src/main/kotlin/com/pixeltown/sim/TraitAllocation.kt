package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Traits as TraitConfig

/**
 * A civilisation's five inherited traits and everything derived from them.
 *
 * Genetic to a people: the player allocates ten points on the opening screen and rivals draw
 * their own (see `RivalStrategist`). A people also grows — every decade a civ earns a point, so
 * the allocation is immutable but a civ's *current* allocation is replaced as it improves.
 * Derived values are computed once at construction, not per citizen per tick — they are read
 * constantly by the economy and survival systems.
 */
data class TraitAllocation(
    val speed: Int,
    val health: Int,
    val hunting: Int,
    val elements: Int,
    val farming: Int,
    val logging: Int = TraitConfig.BASE_VALUE,
) {
    init {
        for (value in values) {
            require(value in TraitConfig.MIN_PER_TRAIT..TraitConfig.MAX_PER_TRAIT) {
                "trait value $value is outside ${TraitConfig.MIN_PER_TRAIT}..${TraitConfig.MAX_PER_TRAIT}"
            }
        }
    }

    val values: IntArray get() = intArrayOf(speed, health, hunting, elements, farming, logging)

    operator fun get(trait: Trait): Int = when (trait) {
        Trait.SPEED -> speed
        Trait.HEALTH -> health
        Trait.HUNTING -> hunting
        Trait.ELEMENTS -> elements
        Trait.FARMING -> farming
        Trait.LOGGING -> logging
    }

    /**
     * This allocation with one more point in [trait], or null if that trait is already at
     * [TraitConfig.MAX_PER_TRAIT].
     *
     * A whole new [TraitAllocation] rather than a mutation: every derived stat is computed at
     * construction, so replacing the object is what keeps them consistent. Nothing caches a
     * civ's traits — every system reads `civ.traits` live — so a people really can grow mid-run.
     */
    fun withPointIn(trait: Trait): TraitAllocation? {
        if (this[trait] >= TraitConfig.MAX_PER_TRAIT) return null
        val next = values
        next[trait.ordinal]++
        return of(*next)
    }

    /** Traits that still have room for another point. */
    val improvable: List<Trait> get() = Trait.entries.filter { this[it] < TraitConfig.MAX_PER_TRAIT }

    /** Points spent above the base allocation. Must equal the run's budget to be legal. */
    val pointsSpent: Int get() = values.sumOf { it - TraitConfig.BASE_VALUE }

    // ---- derived stats, per the design document ----

    /** Work output, movement and build/research rate. */
    val workMultiplier: Double = TraitConfig.WORK_MULT_BASE + TraitConfig.WORK_MULT_PER_SPEED * speed

    /** Cells a citizen can cross per day. */
    val moveSpeed: Double = TraitConfig.MOVE_SPEED_BASE + TraitConfig.MOVE_SPEED_PER_SPEED * speed

    val lifespanYears: Double = TraitConfig.LIFESPAN_YEARS_BASE + TraitConfig.LIFESPAN_YEARS_PER_HEALTH * health

    val lifespanDays: Int = (lifespanYears * GameConfig.Time.DAYS_PER_YEAR).toInt()

    val diseaseResist: Double = TraitConfig.DISEASE_RESIST_PER_HEALTH * health

    /** How readily this people bear children, relative to the baseline. */
    val fertilityMultiplier: Double = (
        1.0 + TraitConfig.FERTILITY_PER_HEALTH * (health - TraitConfig.BASE_VALUE)
        ).coerceAtLeast(0.25)

    val maxHp: Float = (TraitConfig.MAX_HP_BASE + TraitConfig.MAX_HP_PER_HEALTH * health).toFloat()

    val huntYield: Double = TraitConfig.HUNT_YIELD_BASE + TraitConfig.HUNT_YIELD_PER_HUNTING * hunting

    val farmYield: Double = TraitConfig.FARM_YIELD_BASE + TraitConfig.FARM_YIELD_PER_FARMING * farming

    /**
     * Food a citizen of this people needs per day, relative to the baseline — their metabolism.
     *
     * Measured against the base value rather than zero, so a Health-3 people eats exactly the
     * standard ration and the trait reads as a change from the default in both directions.
     */
    val rationMultiplier: Double = (
        1.0 - TraitConfig.RATION_REDUCTION_PER_HEALTH * (health - TraitConfig.BASE_VALUE)
        ).coerceAtLeast(0.5)

    /**
     * The extra food this people needs in the harshest season, as a fraction of their ration. A
     * cold people eats more to stay warm; a weathered one barely notices the winter.
     */
    val winterRationSurcharge: Double =
        (TraitConfig.WINTER_RATION_SURCHARGE * (1.0 - elementsShelterOf(elements))).coerceAtLeast(0.0)

    /** Wood and stone a gatherer brings back, per worked day. */
    val gatherYield: Double = TraitConfig.GATHER_YIELD_BASE + TraitConfig.GATHER_YIELD_PER_LOGGING * logging

    /** How fast this people raise a structure, relative to the baseline. */
    val buildRate: Double = TraitConfig.BUILD_RATE_BASE + TraitConfig.BUILD_RATE_PER_LOGGING * logging

    /** Fraction by which weather, season and disaster penalties are reduced. */
    val elementsShelter: Double = TraitConfig.ELEMENTS_PENALTY_REDUCTION_PER_POINT * elements

    /** Soil recovery per day, which is why a farming people can work land harder. */
    val fertilityRecoveryPerDay: Double =
        TraitConfig.FERTILITY_RECOVERY_BASE + TraitConfig.FERTILITY_RECOVERY_PER_FARMING * farming

    /**
     * Seasonal multiplier on farm and hunt output.
     *
     * This is the trait interaction the design asks for: Elements damps the seasonal swing, so
     * Farming-8/Elements-3 boom-and-busts while Farming-5/Elements-5 is steadier. [severity] is
     * 0 in the kindest season and 1 in the harshest.
     */
    fun seasonalYieldMultiplier(severity: Double): Double {
        val swing = TraitConfig.SEASON_SWING_AMPLITUDE -
            TraitConfig.SEASON_SWING_DAMP_PER_ELEMENTS * elements
        return (1.0 - severity * swing.coerceAtLeast(0.0)).coerceAtLeast(0.05)
    }

    companion object {
        /** Shared with [elementsShelter], which is declared after these two need it. */
        private fun elementsShelterOf(elements: Int): Double =
            TraitConfig.ELEMENTS_PENALTY_REDUCTION_PER_POINT * elements

        /** All five traits at base, before any points are spent. */
        val BASE = TraitAllocation(
            TraitConfig.BASE_VALUE,
            TraitConfig.BASE_VALUE,
            TraitConfig.BASE_VALUE,
            TraitConfig.BASE_VALUE,
            TraitConfig.BASE_VALUE,
            TraitConfig.BASE_VALUE,
        )

        /**
         * A flat one point in every trait — the shape a player produces when they refuse to choose.
         *
         * It spends six of the ten, not all of them: ten points do not divide evenly over six
         * traits. The balance sweep's `naive-even` spends the full budget as evenly as it can
         * (5/5/5/5/4/4) and is what the §12 target is measured against; this is the flat build the
         * tests use where the exact figures do not matter, only that nothing is specialised.
         */
        val EVEN_SPREAD = TraitAllocation(4, 4, 4, 4, 4, 4)

        /**
         * Builds an allocation from values in [Trait] order. Five values are accepted as well as
         * six: Logging arrived after a great many tests and balance tables were written against
         * the original five, and defaulting it to base keeps every one of them meaningful.
         */
        fun of(vararg values: Int): TraitAllocation {
            require(values.size == TraitConfig.COUNT || values.size == TraitConfig.COUNT - 1) {
                "expected ${TraitConfig.COUNT} traits, or ${TraitConfig.COUNT - 1} without Logging"
            }
            val logging = if (values.size == TraitConfig.COUNT) values[5] else TraitConfig.BASE_VALUE
            return TraitAllocation(values[0], values[1], values[2], values[3], values[4], logging)
        }

        /**
         * A legal random allocation from the standard budget, for rival civs. Points are spent one
         * at a time on a random trait that still has room, so the result is always exactly legal.
         */
        fun random(rng: SimRandom, budget: Int = TraitConfig.ALLOCATION_POINTS): TraitAllocation {
            val values = IntArray(TraitConfig.COUNT) { TraitConfig.BASE_VALUE }
            var remaining = budget
            while (remaining > 0) {
                val candidates = values.indices.filter { values[it] < TraitConfig.MAX_PER_TRAIT }
                if (candidates.isEmpty()) break
                values[rng.pick(candidates)]++
                remaining--
            }
            return of(*values)
        }
    }
}
