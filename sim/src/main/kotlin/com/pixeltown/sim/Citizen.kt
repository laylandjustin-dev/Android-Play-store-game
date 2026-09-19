package com.pixeltown.sim

/**
 * One person, drawn as one pixel.
 *
 * A mutable class rather than an immutable data class: this is updated in place thousands of
 * times per tick and copying would dominate the frame budget. If the naive layout drops frames
 * past ~3,000 agents it becomes a struct-of-arrays store — measure before changing it.
 */
class Citizen(
    val id: Int,
    var x: Int,
    var y: Int,
    val civId: Int,
    val sex: Sex,
    var ageDays: Int,
    var hp: Float,
    var nutrition: Float,
    var morale: Float,
    var survival: Float,
    var job: Job,
    var skill: Float,
    var influence: Float,
    var partnerId: Int? = null,
    var pregnantUntilDay: Int? = null,
    var homeBuildingId: Int? = null,
) {
    /** The cell this citizen works, or [World.NONE] for indoor work and the unemployed. */
    var workCell: Int = World.NONE

    /**
     * Under arms and marching. The weekly job assignment leaves these citizens alone: without the
     * flag, a civ would call up an army on the first of the month and reassign half of it to
     * farming a week later, mid-campaign.
     */
    var enlisted: Boolean = false

    /**
     * The cause this person leans toward regardless of circumstance, and how strongly.
     *
     * Without it the electorate is a hive mind: citizens in the same town are in near-identical
     * condition, so they compute near-identical needs and every election came back 95-0. A small
     * personal bias makes elections contested in calm years while genuine hardship — a famine, a
     * raid — still swamps it and swings the town as a bloc.
     */
    var politicalBias: BuildingCategory = BuildingCategory.FARMS
    var politicalBiasStrength: Float = 0f

    /** Consecutive days at zero nutrition. Starvation kills at [GameConfig.Life.STARVATION_DAYS]. */
    var starvingDays: Int = 0

    /** Day the partner died, so a widow can re-pair after the mourning period. */
    var widowedOnDay: Int? = null

    /** False once the citizen dies; dead citizens are compacted out at the end of the tick. */
    var alive: Boolean = true

    val ageYears: Int get() = ageDays / GameConfig.Time.DAYS_PER_YEAR

    val isChild: Boolean get() = ageYears < GameConfig.Life.CHILD_UNTIL_YEARS

    val isAdult: Boolean get() = !isChild

    val isPregnant: Boolean get() = pregnantUntilDay != null

    /**
     * Food this citizen needs today.
     *
     * [traits] and [seasonSeverity] are optional so the many call sites that only want a rough
     * demand figure — trade reserves, job quotas — keep working unchanged, while feeding uses the
     * real numbers. Health lowers the ration and Elements removes the winter surcharge: see
     * `GameConfig.Traits.RATION_REDUCTION_PER_HEALTH`.
     */
    fun dailyFoodNeed(traits: TraitAllocation? = null, seasonSeverity: Double = 0.0): Double {
        val base = if (isChild) {
            GameConfig.Economy.FOOD_PER_CHILD_PER_DAY
        } else {
            GameConfig.Economy.FOOD_PER_ADULT_PER_DAY
        }
        if (traits == null) return base
        return base * traits.rationMultiplier * (1.0 + traits.winterRationSurcharge * seasonSeverity)
    }

    override fun toString(): String = "Citizen($id civ=$civId age=${ageYears}y survival=$survival)"
}

/** Why a citizen died. Every death is logged with one of these. */
enum class DeathCause {
    STARVATION, ILLNESS, EXPOSURE, OLD_AGE, COMBAT, DISASTER, CHILDBIRTH,

    /**
     * Left the town for good. Not a death, but a loss to the civ in exactly the same way, and it
     * belongs in the Ledger's breakdown under its own name rather than polluting another cause.
     */
    EMIGRATION,
}
