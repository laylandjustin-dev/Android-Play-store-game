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

    fun dailyFoodNeed(): Double =
        if (isChild) GameConfig.Economy.FOOD_PER_CHILD_PER_DAY else GameConfig.Economy.FOOD_PER_ADULT_PER_DAY

    override fun toString(): String = "Citizen($id civ=$civId age=${ageYears}y survival=$survival)"
}

/** Why a citizen died. Every death is logged with one of these. */
enum class DeathCause { STARVATION, ILLNESS, EXPOSURE, OLD_AGE, COMBAT, DISASTER, CHILDBIRTH }
