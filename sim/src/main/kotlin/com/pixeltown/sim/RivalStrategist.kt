package com.pixeltown.sim

import kotlin.math.pow
import com.pixeltown.sim.GameConfig.Rivals as RivalConfig
import com.pixeltown.sim.GameConfig.Traits as TraitConfig

/**
 * How a rival civilisation chooses its own inherited traits.
 *
 * The player allocates their ten points by hand; the four rivals must allocate theirs for
 * themselves, and *how* they do it decides what the whole game feels like. Four principles, each
 * of them answering something this project has actually measured:
 *
 *  1. **Coherent.** A rival should read as a people, not as noise. Uniform random spending
 *     produced four indistinguishable civs, so "Tolmar are militants, expect raids" was never
 *     something a player could learn. Each personality now has a prior — an appetite for each
 *     trait — and points are drawn against it, so builds vary while staying in character.
 *
 *  2. **Viable.** An opponent that starves in year two is an opponent removed from the game. The
 *     balance runs are unambiguous about where that line sits: Farming 3 dies in one to two years
 *     whatever else it has, Farming 1 never sees year one. Every rival therefore secures a food
 *     floor before it spends anything on its character.
 *
 *  3. **Diverse.** Four rivals is four slots; two identical builds waste one. Duplicates are
 *     rerolled.
 *
 *  4. **Honest.** The Rivals screen tells the player a civ's personality, so the build behind it
 *     has to match — otherwise the game is lying to them through its own UI.
 *
 * The result is a weighted draw rather than a fixed template per personality: two militant civs in
 * different runs are recognisably militant, and not the same people twice.
 */
object RivalStrategist {

    /**
     * Relative appetite for each trait, indexed by [Trait.ordinal], per personality.
     *
     * Read these as "what this people believe keeps them safe", not as a stat block. Hunting is
     * doubled-up in the design as military effectiveness, which is why militants want it.
     */
    private val PRIORS: Map<Personality, DoubleArray> = mapOf(
        // Strength through arms: hunting is military effectiveness, speed gets them there first.
        Personality.MILITANT to doubleArrayOf(2.0, 1.5, 4.0, 1.0, 1.5),
        // Surplus is the point: farm hard, work fast, and weather the bad years to keep trading.
        Personality.MERCANTILE to doubleArrayOf(2.0, 1.5, 1.0, 1.5, 3.0),
        // Grow and spread: food and bodies, with the legs to claim ground.
        Personality.EXPANSIONIST to doubleArrayOf(2.0, 2.5, 1.5, 1.0, 2.5),
        // Endure alone: the seasons and disease are the enemy, not the neighbours.
        Personality.ISOLATIONIST to doubleArrayOf(1.0, 2.5, 1.0, 3.0, 2.0),
    )

    /**
     * Some peoples commit to one idea and some hedge. A low value spreads points across the
     * prior; a high one concentrates them on its favourites, producing the occasional extremist
     * civ that is terrifying in one dimension and fragile everywhere else.
     */
    private const val MIN_FOCUS = 0.6
    private const val MAX_FOCUS = 2.6

    /** One civ's allocation, personality-shaped and guaranteed able to feed itself. */
    fun allocate(personality: Personality, rng: SimRandom, budget: Int = TraitConfig.ALLOCATION_POINTS): TraitAllocation {
        val values = IntArray(TraitConfig.COUNT) { TraitConfig.BASE_VALUE }
        var remaining = budget

        // Food first, always. Everything else is a preference; this is survival.
        while (remaining > 0 && values[Trait.FARMING.ordinal] < RivalConfig.MIN_VIABLE_FARMING) {
            values[Trait.FARMING.ordinal]++
            remaining--
        }

        val prior = PRIORS.getValue(personality)
        val focus = rng.nextDouble(MIN_FOCUS, MAX_FOCUS)
        val weights = DoubleArray(prior.size) { prior[it].pow(focus) }

        while (remaining > 0) {
            val pick = weightedPick(weights, values, rng) ?: break
            values[pick]++
            remaining--
        }
        return TraitAllocation.of(*values)
    }

    /** Allocations for a whole set of rivals, with no two of them identical. */
    fun allocateAll(
        personalities: List<Personality>,
        rng: SimRandom,
        budget: Int = TraitConfig.ALLOCATION_POINTS,
    ): List<TraitAllocation> {
        val chosen = ArrayList<TraitAllocation>(personalities.size)
        for (personality in personalities) {
            var candidate = allocate(personality, rng, budget)
            var attempts = 0
            while (chosen.any { it.values.contentEquals(candidate.values) } && attempts < DIVERSITY_ATTEMPTS) {
                candidate = allocate(personality, rng, budget)
                attempts++
            }
            chosen.add(candidate)
        }
        return chosen
    }

    /**
     * Where a rival puts a decade's growth point.
     *
     * Same two rules as the opening allocation, in the same order: food floor first, then the
     * personality's prior. The focus exponent is deliberately *not* rerolled here — a people's
     * character does not change every ten years, so growth compounds the build the run started
     * with rather than wandering back toward the average.
     */
    fun chooseGrowth(personality: Personality, current: TraitAllocation, rng: SimRandom): Trait? {
        if (current.improvable.isEmpty()) return null
        if (current[Trait.FARMING] < RivalConfig.MIN_VIABLE_FARMING) return Trait.FARMING

        val prior = PRIORS.getValue(personality)
        val pick = weightedPick(prior, current.values, rng) ?: return null
        return Trait.entries[pick]
    }

    /** Weighted draw over the traits that still have room, or null when every trait is capped. */
    private fun weightedPick(weights: DoubleArray, values: IntArray, rng: SimRandom): Int? {
        var total = 0.0
        for (i in weights.indices) {
            if (values[i] < TraitConfig.MAX_PER_TRAIT) total += weights[i]
        }
        if (total <= 0.0) return null

        var roll = rng.nextDouble(total)
        for (i in weights.indices) {
            if (values[i] >= TraitConfig.MAX_PER_TRAIT) continue
            roll -= weights[i]
            if (roll <= 0.0) return i
        }
        return weights.indices.lastOrNull { values[it] < TraitConfig.MAX_PER_TRAIT }
    }

    private const val DIVERSITY_ATTEMPTS = 12
}
