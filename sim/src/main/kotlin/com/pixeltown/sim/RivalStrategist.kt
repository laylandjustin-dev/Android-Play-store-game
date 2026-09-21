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
        // Strength through arms: hunting is military effectiveness, speed gets them there first,
        // and walls are the one military building nobody wins a siege without.
        Personality.MILITANT to doubleArrayOf(2.0, 1.5, 4.0, 1.0, 1.5, 2.0),
        // Surplus is the point: farm hard, work fast, and weather the bad years to keep trading.
        Personality.MERCANTILE to doubleArrayOf(2.0, 1.5, 1.0, 1.5, 3.0, 2.5),
        // Grow and spread: food and bodies, with the legs to claim ground and the timber to hold it.
        Personality.EXPANSIONIST to doubleArrayOf(2.0, 2.5, 1.5, 1.0, 2.5, 2.5),
        // Endure alone: the seasons and disease are the enemy, not the neighbours.
        Personality.ISOLATIONIST to doubleArrayOf(1.0, 2.5, 1.0, 3.0, 2.0, 1.5),
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
            val pick = weightedPick(weights, values, rng, TraitConfig.ALLOCATION_MAX_PER_TRAIT) ?: break
            values[pick]++
            remaining--
        }
        return TraitAllocation.of(*values)
    }

    /** Allocations for a whole set of rivals, no two identical and, where it can manage it, no two
     * the same *people*. */
    fun allocateAll(
        personalities: List<Personality>,
        rng: SimRandom,
        budget: Int = TraitConfig.ALLOCATION_POINTS,
    ): List<TraitAllocation> {
        val chosen = ArrayList<TraitAllocation>(personalities.size)
        for (personality in personalities) {
            var candidate = allocate(personality, rng, budget)
            var attempts = 0
            // Two rejections, in order of how badly they read. An identical sheet is rejected
            // outright. A *different* sheet that makes the same people is rejected too, because
            // since AD-80 a rival card headlines what a civ is — "Rooted · upkeep -25% · Reapers" —
            // and a live run produced three Rooted neighbours in a row, which reads as a bug even
            // though their numbers differed. `MIN_VIABLE_FARMING` is what biases toward it: Farming
            // is raised before the priors get a say, so it is the dominant trait more often than
            // anything else.
            //
            // Identity diversity is a preference rather than a guarantee. There are six rivals and
            // seven archetypes, but the priors do not reach all of them equally, so after
            // DIVERSITY_ATTEMPTS the duplicate is accepted rather than looped on: a rival who is a
            // second Rooted is worse than a rival who is a copy, and both are better than a hang.
            while (attempts < DIVERSITY_ATTEMPTS &&
                chosen.any { it.values.contentEquals(candidate.values) }
            ) {
                candidate = allocate(personality, rng, budget)
                attempts++
            }
            val taken = chosen.mapTo(HashSet()) { Archetype.of(it) }
            while (attempts < DIVERSITY_ATTEMPTS && Archetype.of(candidate) in taken) {
                val next = allocate(personality, rng, budget)
                attempts++
                if (chosen.none { it.values.contentEquals(next.values) }) candidate = next
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

    /**
     * Weighted draw over the traits that still have room, or null when every trait is capped.
     *
     * [cap] is the ceiling that applies to *this* draw: the opening allocation is bound by the
     * screen's `ALLOCATION_MAX_PER_TRAIT` (8) exactly as the player's is, while a decade's growth
     * point may climb to the lifetime `MAX_PER_TRAIT` (20). Passing one number for both was how a
     * rival could be born with 20 in a trait no player could open with.
     */
    private fun weightedPick(
        weights: DoubleArray,
        values: IntArray,
        rng: SimRandom,
        cap: Int = TraitConfig.MAX_PER_TRAIT,
    ): Int? {
        var total = 0.0
        for (i in weights.indices) {
            if (values[i] < cap) total += weights[i]
        }
        if (total <= 0.0) return null

        var roll = rng.nextDouble(total)
        for (i in weights.indices) {
            if (values[i] >= cap) continue
            roll -= weights[i]
            if (roll <= 0.0) return i
        }
        return weights.indices.lastOrNull { values[it] < cap }
    }

    private const val DIVERSITY_ATTEMPTS = 12
}
