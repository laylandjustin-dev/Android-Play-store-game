package com.pixeltown.sim

import kotlin.math.abs
import kotlin.math.roundToInt

/** One thing a trait changes, as the allocation screen and the growth modal show it. */
data class TraitEffect(
    val label: String,
    /** What it is now, and what it would be with one more point in the trait. */
    val from: Double,
    val to: Double,
    /** How the value is written: a multiplier, a plain number, a percentage, or years. */
    val unit: Unit_,
    /** True when a *lower* number is the better one, so the UI can colour it correctly. */
    val lowerIsBetter: Boolean = false,
) {
    enum class Unit_ { MULTIPLIER, NUMBER, PERCENT, YEARS }

    /** The change as a signed percentage of the current value. Zero when nothing moves. */
    val percentChange: Int
        get() {
            if (abs(from) < 1e-9) return if (abs(to) < 1e-9) 0 else 100
            return (((to - from) / abs(from)) * 100).roundToInt()
        }

    val changes: Boolean get() = abs(to - from) > 1e-9
}

/**
 * What each trait actually does, computed by asking the simulation rather than by describing it.
 *
 * The allocation screen used to show six one-line blurbs and a flat list of derived numbers, which
 * told a player what a trait was *about* but never what a point was *worth*. This builds every row
 * by constructing the allocation one point higher and reading the same `TraitAllocation` the game
 * runs on, so the screen cannot drift from the simulation the way prose does: if a formula is
 * retuned, these numbers move with it and no copy needs editing.
 */
object TraitEffects {

    /** The rows for [trait], given the allocation the player currently has. */
    fun of(traits: TraitAllocation, trait: Trait): List<TraitEffect> {
        val next = traits.withPointIn(trait) ?: return emptyList()
        return rowsFor(trait, traits, next)
    }

    /** Every trait's rows at once, for a screen that shows them all. */
    fun all(traits: TraitAllocation): Map<Trait, List<TraitEffect>> =
        Trait.entries.associateWith { of(traits, it) }

    private fun rowsFor(
        trait: Trait,
        now: TraitAllocation,
        next: TraitAllocation,
    ): List<TraitEffect> = when (trait) {
        Trait.SPEED -> listOf(
            row("Work rate", now.workMultiplier, next.workMultiplier, TraitEffect.Unit_.MULTIPLIER),
            row("Movement", now.moveSpeed, next.moveSpeed, TraitEffect.Unit_.NUMBER),
        )

        Trait.HEALTH -> listOf(
            row("Lifespan", now.lifespanYears, next.lifespanYears, TraitEffect.Unit_.YEARS),
            row("Hit points", now.maxHp.toDouble(), next.maxHp.toDouble(), TraitEffect.Unit_.NUMBER),
            row("Disease resistance", now.diseaseResist, next.diseaseResist, TraitEffect.Unit_.PERCENT),
            row("Birth rate", now.fertilityMultiplier, next.fertilityMultiplier, TraitEffect.Unit_.MULTIPLIER),
            row(
                "Food eaten", now.rationMultiplier, next.rationMultiplier,
                TraitEffect.Unit_.MULTIPLIER, lowerIsBetter = true,
            ),
        )

        // One row, not two: `Citizen.strength` *is* `huntYield` scaled by that person's vigour,
        // condition, skill and age, so hunting and fighting are the same number by construction.
        // Listing them separately would have shown the same figure twice and implied two dials.
        Trait.HUNTING -> listOf(
            row(
                "Hunt yield and fighting strength",
                now.huntYield, next.huntYield, TraitEffect.Unit_.MULTIPLIER,
            ),
        )

        Trait.ELEMENTS -> listOf(
            row("Weather protection", now.elementsShelter, next.elementsShelter, TraitEffect.Unit_.PERCENT),
            row(
                "Winter food bill", now.winterRationSurcharge, next.winterRationSurcharge,
                TraitEffect.Unit_.PERCENT, lowerIsBetter = true,
            ),
            row(
                "Worst-season yield",
                now.seasonalYieldMultiplier(1.0), next.seasonalYieldMultiplier(1.0),
                TraitEffect.Unit_.MULTIPLIER,
            ),
        )

        Trait.FARMING -> listOf(
            row("Farm yield", now.farmYield, next.farmYield, TraitEffect.Unit_.MULTIPLIER),
            row(
                "Soil recovery", now.fertilityRecoveryPerDay, next.fertilityRecoveryPerDay,
                TraitEffect.Unit_.NUMBER,
            ),
        )

        Trait.GATHERING -> listOf(
            row("Timber and stone", now.gatherYield, next.gatherYield, TraitEffect.Unit_.MULTIPLIER),
            row("Build rate", now.buildRate, next.buildRate, TraitEffect.Unit_.MULTIPLIER),
        )
    }

    private fun row(
        label: String,
        from: Double,
        to: Double,
        unit: TraitEffect.Unit_,
        lowerIsBetter: Boolean = false,
    ) = TraitEffect(label, from, to, unit, lowerIsBetter)
}
