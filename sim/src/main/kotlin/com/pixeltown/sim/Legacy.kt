package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Meta
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import kotlin.math.floor
import kotlin.math.min

/** A permanent upgrade bought with Chronicle points, carried across runs. */
enum class LegacyUpgrade(val maxLevel: Int) {
    /** +1 starting allocation point per level. Hard-capped at +4, however much is spent. */
    ALLOCATION_POINT(TraitConfig.MAX_PURCHASED_ALLOCATION_POINTS),

    /** +5 settlers per level. */
    EXTRA_SETTLERS(4),

    /** A building already standing on day one. */
    STARTING_BUILDING(3),

    /** Skill matures faster, so a young colony is less helpless. */
    FAST_LEARNERS(3),

    /** Citizens start with influence, so the council is contested sooner. */
    STANDING(3),

    /** A floor under soil fertility, so land cannot be exhausted entirely. */
    FERTILITY_FLOOR(3),

    /** Rivals begin better disposed toward you. */
    DIPLOMACY(3),
}

/**
 * What a player carries between runs: Chronicle points earned and upgrades bought.
 *
 * Everything purchasable with real money routes through Chronicle points (see §14 of the brief),
 * so this is the single place where meta progression lives — grind and purchase are the same
 * currency and the same cap applies to both.
 */
class Legacy {

    private val levels = HashMap<LegacyUpgrade, Int>()

    var chroniclePoints: Int = 0
        private set

    var runsPlayed: Int = 0
        private set

    var bestYears: Int = 0
        private set

    fun levelOf(upgrade: LegacyUpgrade): Int = levels[upgrade] ?: 0

    fun grant(points: Int) {
        require(points >= 0) { "cannot grant negative Chronicle points" }
        chroniclePoints += points
    }

    /** Spends points. Returns false and changes nothing if they cannot be afforded. */
    fun spend(points: Int): Boolean {
        if (points < 0 || chroniclePoints < points) return false
        chroniclePoints -= points
        return true
    }

    /** Cost of the next level of an upgrade, or null if it is already maxed. */
    fun costOf(upgrade: LegacyUpgrade): Int? {
        val next = levelOf(upgrade)
        if (next >= upgrade.maxLevel) return null
        return if (upgrade == LegacyUpgrade.ALLOCATION_POINT) {
            Meta.ALLOCATION_POINT_UPGRADE_COSTS[next]
        } else {
            (Meta.UPGRADE_BASE_COST * Math.pow(Meta.UPGRADE_COST_GROWTH, next.toDouble())).toInt()
        }
    }

    /**
     * Buys one level. The cap is enforced here rather than at the point of sale, so no amount of
     * spending — earned or purchased — can exceed it.
     */
    fun buy(upgrade: LegacyUpgrade): Boolean {
        val cost = costOf(upgrade) ?: return false
        if (!spend(cost)) return false
        levels[upgrade] = levelOf(upgrade) + 1
        return true
    }

    fun recordRun(yearsSurvived: Int) {
        runsPlayed++
        if (yearsSurvived > bestYears) bestYears = yearsSurvived
    }

    /** Extra allocation points this legacy grants, never more than the cap. */
    val bonusAllocationPoints: Int
        get() = min(levelOf(LegacyUpgrade.ALLOCATION_POINT), TraitConfig.MAX_PURCHASED_ALLOCATION_POINTS)

    val startingSettlers: Int
        get() = GameConfig.World.STARTING_SETTLERS +
            levelOf(LegacyUpgrade.EXTRA_SETTLERS) * Meta.SETTLERS_PER_UPGRADE

    val skillGrowthMultiplier: Double
        get() = 1.0 + levelOf(LegacyUpgrade.FAST_LEARNERS) * Meta.SKILL_GROWTH_PER_UPGRADE

    val startingInfluence: Float
        get() = (levelOf(LegacyUpgrade.STANDING) * Meta.INFLUENCE_PER_UPGRADE).toFloat()

    val fertilityFloor: Float
        get() = (levelOf(LegacyUpgrade.FERTILITY_FLOOR) * Meta.FERTILITY_FLOOR_PER_UPGRADE).toFloat()

    val startingTensionRelief: Double
        get() = levelOf(LegacyUpgrade.DIPLOMACY) * Meta.TENSION_RELIEF_PER_UPGRADE

    val startingBuildings: Int
        get() = levelOf(LegacyUpgrade.STARTING_BUILDING)

    /** For the save file. */
    fun snapshotLevels(): Map<LegacyUpgrade, Int> = levels.toMap()

    companion object {
        /**
         * Chronicle points earned by a finished run:
         * `floor(peakPopulation/10 + years/4 + techTier^2 x 6 + endStateBonus)`.
         */
        fun scoreRun(peakPopulation: Int, yearsSurvived: Int, techTier: Int, endState: EndState): Int {
            val score = peakPopulation * Meta.CHRONICLE_PER_PEAK_POP +
                yearsSurvived * Meta.CHRONICLE_PER_YEAR +
                techTier * techTier * Meta.CHRONICLE_TECH_TIER_FACTOR +
                Meta.END_STATE_BONUS.getValue(endState)
            return floor(score).toInt().coerceAtLeast(0)
        }

        fun restore(points: Int, runs: Int, best: Int, levels: Map<LegacyUpgrade, Int>): Legacy {
            val legacy = Legacy()
            legacy.chroniclePoints = points
            legacy.runsPlayed = runs
            legacy.bestYears = best
            for ((upgrade, level) in levels) {
                legacy.levels[upgrade] = min(level, upgrade.maxLevel)
            }
            return legacy
        }
    }
}

/**
 * Everything that decides how a run starts, fixed at the moment it begins.
 *
 * The simulation reads this once and never consults the billing layer or the legacy again, which
 * is what keeps a purchase from being able to change a run already in progress (AD-8).
 */
data class RunConfig(
    val seed: Long,
    val traits: TraitAllocation,
    val settlers: Int = GameConfig.World.STARTING_SETTLERS,
    val civCount: Int = GameConfig.World.TOTAL_CIV_COUNT,
    val skillGrowthMultiplier: Double = 1.0,
    val startingInfluence: Float = 0f,
    val fertilityFloor: Float = 0f,
    val startingTensionRelief: Double = 0.0,
    val startingBuildings: Int = 0,
    /** Offline catch-up cap in hours, raised by the Founders Pass. */
    val offlineCapHours: Int = Meta.OFFLINE_CAP_HOURS_FREE,
) {
    companion object {
        /** Builds the starting configuration a legacy earns, for the traits the player allocated. */
        fun from(
            seed: Long,
            traits: TraitAllocation,
            legacy: Legacy,
            offlineCapHours: Int = Meta.OFFLINE_CAP_HOURS_FREE,
        ): RunConfig = RunConfig(
            seed = seed,
            traits = traits,
            settlers = legacy.startingSettlers,
            skillGrowthMultiplier = legacy.skillGrowthMultiplier,
            startingInfluence = legacy.startingInfluence,
            fertilityFloor = legacy.fertilityFloor,
            startingTensionRelief = legacy.startingTensionRelief,
            startingBuildings = legacy.startingBuildings,
            offlineCapHours = offlineCapHours,
        )
    }
}

/** How a finished run scored, and what it earned. */
data class RunSummary(
    val endState: EndState,
    val yearsSurvived: Int,
    val peakPopulation: Int,
    val techTier: Int,
    val chroniclePointsEarned: Int,
)
