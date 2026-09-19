package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Meta
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.pow
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
            (Meta.UPGRADE_BASE_COST * Meta.UPGRADE_COST_GROWTH.pow(next)).toInt()
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
    /**
     * What the player called their colony. Always a name the game will display — [RunConfig] runs
     * it through [ColonyName.sanitise] on construction, so no caller can put an unusable string
     * into a run or a save.
     */
    val colonyName: String = ColonyName.DEFAULT,
    /**
     * Where the player chose to land, as a cell index, or null to take the generator's pick. An
     * illegal cell is ignored by `WorldGenerator` rather than rejected here: what counts as legal
     * depends on the island, which depends on the seed.
     */
    val startCell: Int? = null,
    /** Index into [Palette.PLAYER_CHOICES]. Out-of-range falls back to the default colour. */
    val colorIndex: Int = 0,
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
    /**
     * Cleaned here rather than at every call site, so a name is legal by the time it reaches the
     * simulation, the save, or a UI — whatever a caller passed in.
     */
    val colony: String get() = ColonyName.sanitise(colonyName)

    /** The per-run civ palette this configuration implies. */
    val colors: CivColors get() = CivColors.forPlayerChoice(colorIndex, civCount)

    companion object {
        /** Builds the starting configuration a legacy earns, for the traits the player allocated. */
        fun from(
            seed: Long,
            traits: TraitAllocation,
            legacy: Legacy,
            offlineCapHours: Int = Meta.OFFLINE_CAP_HOURS_FREE,
            colonyName: String = ColonyName.DEFAULT,
            startCell: Int? = null,
            colorIndex: Int = 0,
        ): RunConfig = RunConfig(
            seed = seed,
            traits = traits,
            colonyName = colonyName,
            startCell = startCell,
            colorIndex = colorIndex,
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

/**
 * How a finished run scored, what it earned, and — the larger half — what the player actually
 * decided along the way.
 *
 * The score was always the easy part. A run of three centuries is a few dozen real decisions: ten
 * opening points, a point every decade, a tech at every tier, the categories chartered and
 * petitioned. None of that was visible anywhere when the run ended, so a player could not tell a
 * good run from a lucky one. Everything here is a *record*, computed at the end from state the
 * simulation was keeping anyway; nothing in it feeds back into the game.
 */
data class RunSummary(
    val endState: EndState,
    val yearsSurvived: Int,
    val peakPopulation: Int,
    val techTier: Int,
    val chroniclePointsEarned: Int,
    /** The allocation the run opened on, and the one it ended with. */
    val openingTraits: TraitAllocation = TraitAllocation.BASE,
    val finalTraits: TraitAllocation = TraitAllocation.BASE,
    /** Where each decade point went, as a count per trait, and how many went unattended. */
    val traitGrowth: Map<Trait, Int> = emptyMap(),
    val traitPointsAutoSpent: Int = 0,
    val traitPointsUnspent: Int = 0,
    /** The techs chosen, in order, one per tier reached. */
    val techsChosen: List<String> = emptyList(),
    /** Completed buildings by category, and the standing charter at the end. */
    val buildingsByCategory: Map<BuildingCategory, Int> = emptyMap(),
    val finalCharter: BuildingCategory? = null,
    /** Which platform the town kept electing, by dominant category. */
    val premiersByPlatform: Map<BuildingCategory, Int> = emptyMap(),
    val termsServed: Int = 0,
    val coups: Int = 0,
    /** What killed people, and how many of each. */
    val deathsByCause: Map<DeathCause, Int> = emptyMap(),
    val totalBirths: Int = 0,
    val totalDeaths: Int = 0,
    /** Wars, raids and trades this civ was party to, and the rivals still standing at the end. */
    val warsFought: Int = 0,
    val raidsSuffered: Int = 0,
    val rivalsSurviving: Int = 0,
    val rivalsExtinct: Int = 0,
) {
    /** A whole-number percentage of [total], for the UI. Zero total reads as zero, never NaN. */
    private fun share(part: Int, total: Int): Int =
        if (total <= 0) 0 else ((part * 100.0) / total).roundToInt()

    /** Where the run's growth points went, as percentages. Largest share first. */
    fun traitGrowthShare(): List<Pair<Trait, Int>> {
        val total = traitGrowth.values.sum()
        return traitGrowth.entries
            .sortedWith(compareByDescending<Map.Entry<Trait, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { it.key to share(it.value, total) }
    }

    /** What the town built, as percentages of its completed buildings. Largest share first. */
    fun buildingShare(): List<Pair<BuildingCategory, Int>> {
        val total = buildingsByCategory.values.sum()
        return buildingsByCategory.entries
            .sortedWith(compareByDescending<Map.Entry<BuildingCategory, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { it.key to share(it.value, total) }
    }

    /** What killed this people, as percentages of all deaths. Largest share first. */
    fun deathShare(): List<Pair<DeathCause, Int>> {
        val total = deathsByCause.values.sum()
        return deathsByCause.entries
            .sortedWith(compareByDescending<Map.Entry<DeathCause, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { it.key to share(it.value, total) }
    }

    /** Which platform the electorate kept returning, as percentages. Largest share first. */
    fun platformShare(): List<Pair<BuildingCategory, Int>> {
        val total = premiersByPlatform.values.sum()
        return premiersByPlatform.entries
            .sortedWith(compareByDescending<Map.Entry<BuildingCategory, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { it.key to share(it.value, total) }
    }

    /**
     * How much of this people's growth they chose themselves.
     *
     * The one number in here that is a judgement rather than a record: an idle run auto-spends its
     * points on the safe answer (AD-50), so a low figure is the game telling the player that their
     * civilisation grew up without them.
     */
    val attentionShare: Int
        get() {
            val spent = traitGrowth.values.sum()
            return share(spent - traitPointsAutoSpent, spent)
        }
}
