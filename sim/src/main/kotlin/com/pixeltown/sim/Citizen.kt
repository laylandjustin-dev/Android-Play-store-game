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
    /**
     * How this one turned out, around 1.0.
     *
     * The civ's traits say what a people are like; this says what *this person* is like. One
     * number rather than five, because it moves work output, hit points, disease resistance and
     * fighting strength together: a strong citizen is a better farmer and a better soldier, which
     * is how a body works. Inherited from parents with a fresh draw on top, so a town's people
     * drift across generations. See `GameConfig.Traits.VIGOUR_MIN`.
     */
    var vigour: Float = 1f

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
     * How much work this citizen gets done, relative to a trained adult of average build.
     *
     * Skill is what they have learned, vigour is what they are, and a hungry or sick body does
     * less of either. Children work at a fraction and the enlisted are not working at all.
     */
    fun effectiveness(): Double {
        if (isChild) return GameConfig.Life.CHILD_WORK_FRACTION
        val learned = GameConfig.Economy.SKILL_OUTPUT_FLOOR +
            (1.0 - GameConfig.Economy.SKILL_OUTPUT_FLOOR) * skill
        return learned * vigour * condition()
    }

    /**
     * 0..1 for how well this body is doing today: fed and unhurt is 1, starving or badly hurt
     * tends to 0. Kept separate from [effectiveness] because combat wants it too.
     */
    fun condition(maxHp: Float = 1f): Double {
        val fed = (0.55f + 0.45f * nutrition).coerceIn(0f, 1f)
        val whole = if (maxHp > 1f) (0.45f + 0.55f * (hp / maxHp)).coerceIn(0f, 1f) else 1f
        return (fed * whole).toDouble()
    }

    /**
     * What this citizen is worth in a fight, before the civ's tech and walls are counted.
     *
     * The same body that farms well fights well, so this is deliberately built from the same
     * pieces as [effectiveness] with the civ's Hunting trait — its military coefficient — over the
     * top, and an age curve: the very young and the very old are not soldiers.
     */
    fun strength(traits: TraitAllocation): Double {
        val prime = agePrime()
        return traits.huntYield * vigour * condition(traits.maxHp) *
            (GameConfig.Economy.SKILL_OUTPUT_FLOOR + (1.0 - GameConfig.Economy.SKILL_OUTPUT_FLOOR) * skill) *
            prime
    }

    /** 0..1 by age: rises through adolescence, holds through the working years, falls with frailty. */
    fun agePrime(): Double {
        val years = ageYears
        val childhood = GameConfig.Life.CHILD_UNTIL_YEARS
        return when {
            years < childhood -> 0.25 * (years.toDouble() / childhood)
            years < childhood + 6 -> 0.45 + 0.55 * ((years - childhood) / 6.0)
            years < 45 -> 1.0
            else -> (1.0 - (years - 45) * 0.022).coerceAtLeast(0.25)
        }
    }

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
