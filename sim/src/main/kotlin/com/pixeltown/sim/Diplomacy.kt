package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Rivals as RivalConfig
import kotlin.math.max
import kotlin.math.min

/**
 * How every civ feels about every other one.
 *
 * Tension is symmetric — two civs are in the same relationship — and drives everything from trade
 * to invasion. War is a latch: it is declared when tension crosses a threshold and only ends when
 * weariness has worn tension back down, so wars have a shape rather than flickering on and off.
 */
class Relations(private val civCount: Int) {

    private val tension = Array(civCount) { DoubleArray(civCount) }
    private val war = Array(civCount) { BooleanArray(civCount) }
    private val tradedWith = Array(civCount) { IntArray(civCount) }
    private val warStartedOn = Array(civCount) { LongArray(civCount) { -1L } }
    /** Day the last war between a pair ended, or [NEVER]. */
    private val peaceMadeOn = Array(civCount) { LongArray(civCount) { NEVER } }

    fun tensionBetween(a: Int, b: Int): Double = if (a == b) 0.0 else tension[a][b]

    companion object {
        /** No war has ever ended between this pair. */
        const val NEVER = Long.MIN_VALUE

        fun restore(save: RelationsSave): Relations {
            val relations = Relations(save.civCount)
            for (a in 0 until save.civCount) {
                for (b in 0 until save.civCount) {
                    val i = a * save.civCount + b
                    relations.tension[a][b] = save.tension[i]
                    relations.war[a][b] = save.war[i]
                    relations.tradedWith[a][b] = save.trades[i]
                    relations.warStartedOn[a][b] = save.warStartedOn[i]
                    relations.peaceMadeOn[a][b] = save.peaceMadeOn[i]
                }
            }
            return relations
        }
    }

    fun atWar(a: Int, b: Int): Boolean = a != b && war[a][b]

    fun anyWar(civId: Int): Boolean = (0 until civCount).any { atWar(civId, it) }

    fun enemiesOf(civId: Int): List<Int> = (0 until civCount).filter { atWar(civId, it) }

    fun tradeCount(a: Int, b: Int): Int = if (a == b) 0 else tradedWith[a][b]

    fun warStartDay(a: Int, b: Int): Long = warStartedOn[a][b]

    fun raise(a: Int, b: Int, amount: Double) {
        if (a == b) return
        val next = (tension[a][b] + amount).coerceIn(0.0, RivalConfig.TENSION_MAX)
        tension[a][b] = next
        tension[b][a] = next
    }

    fun ease(a: Int, b: Int, amount: Double) = raise(a, b, -amount)

    fun recordTrade(a: Int, b: Int) {
        if (a == b) return
        tradedWith[a][b]++
        tradedWith[b][a]++
    }

    fun declareWar(a: Int, b: Int, day: Long) {
        if (a == b) return
        war[a][b] = true
        war[b][a] = true
        warStartedOn[a][b] = day
        warStartedOn[b][a] = day
    }

    fun makePeace(a: Int, b: Int, day: Long) {
        if (a == b) return
        war[a][b] = false
        war[b][a] = false
        warStartedOn[a][b] = -1L
        warStartedOn[b][a] = -1L
        peaceMadeOn[a][b] = day
        peaceMadeOn[b][a] = day
    }

    /**
     * True while a truce still holds, so wars cannot be re-declared the season they end.
     *
     * The "never" case is checked explicitly rather than by sentinel arithmetic: the sentinel was
     * once `Long.MIN_VALUE`, and `day - Long.MIN_VALUE` overflows to a negative number, which read
     * as "in truce". Every pair of civs was permanently at truce and no war was ever declared in
     * any run.
     */
    fun inTruce(a: Int, b: Int, day: Long): Boolean {
        if (a == b) return false
        val madePeace = peaceMadeOn[a][b]
        if (madePeace == NEVER) return false
        return day - madePeace < GameConfig.Rivals.PEACE_COOLDOWN_DAYS
    }

    /** Flat views of the matrices, for the save file. Row-major, civCount x civCount. */
    fun snapshot(): RelationsSave = RelationsSave(
        civCount = civCount,
        tension = tension.flatMap { it.toList() },
        war = war.flatMap { it.toList() },
        trades = tradedWith.flatMap { it.toList() },
        warStartedOn = warStartedOn.flatMap { it.toList() },
        peaceMadeOn = peaceMadeOn.flatMap { it.toList() },
    )

    /** Tension cools slowly on its own; wars additionally wear both sides down. */
    fun decay(perSeason: Double) {
        for (a in 0 until civCount) {
            for (b in a + 1 until civCount) {
                raise(a, b, -perSeason)
            }
        }
    }
}

/** What one civ knows and feels about another, for the Rivals screen. */
data class RivalReport(
    val civId: Int,
    val name: String,
    val personality: Personality,
    val population: Int,
    val militaryStrength: Double,
    val techTier: Int,
    val tension: Double,
    val atWar: Boolean,
    val trades: Int,
    val aggression: Double,
)

/** An army in the field: real citizens walking the map, not an abstraction. */
class Army(
    val id: Int,
    val civId: Int,
    val targetCivId: Int,
    /** The cell this force is marching on. */
    var targetCell: Int,
    val kind: Kind,
    val startedOnDay: Long,
) {
    enum class Kind { RAID, WAR }

    /** Citizen ids under arms. Casualties remove them; the army dissolves when it empties. */
    val members = ArrayList<Int>()

    var startingSize: Int = 0

    /** Set when the force has taken what it came for and is heading home. */
    var returning: Boolean = false

    val size: Int get() = members.size

    fun isBroken(): Boolean =
        startingSize > 0 && size.toDouble() / startingSize < GameConfig.Rivals.ARMY_BROKEN_AT
}

/**
 * The arithmetic of posture, trade and battle. Pure functions: no state, no randomness except
 * what is handed in, so every one of them is directly testable.
 */
internal object DiplomacySystem {

    /**
     * Posture is derived, not scripted, exactly as the design asks: a starving militarist attacks
     * and a fat mercantile civ trades. Personality only biases the result.
     */
    fun aggressionOf(civ: Civilization, militaryShare: Double, foodSecurity: Double): Double {
        val bias = RivalConfig.PERSONALITY_BIAS.getValue(civ.personality)
        val armed = (militaryShare / RivalConfig.MILITARY_SHARE_REFERENCE).coerceIn(0.0, 1.0)
        return (RivalConfig.AGGRESSION_W_MILITARY * armed +
            RivalConfig.AGGRESSION_W_HUNGER * (1.0 - foodSecurity) +
            RivalConfig.AGGRESSION_W_PERSONALITY * bias).coerceIn(0.0, 1.0)
    }

    /** Days of food in store, normalised: 1.0 means comfortably provisioned. */
    fun foodSecurityOf(civ: Civilization, dailyConsumption: Double): Double {
        if (dailyConsumption <= 0.0) return 1.0
        val days = civ[Resource.FOOD] / dailyConsumption
        return (days / RivalConfig.TRADE_SURPLUS_DAYS).coerceIn(0.0, 1.0)
    }

    /**
     * Military strength: `sum(soldiers x (0.4 + 0.18 x Hunting) x skill) x techMultiplier`, plus
     * whatever the civ's barracks and armouries contribute. Walls are a defender's bonus and are
     * applied at the point of battle, not here.
     */
    fun strengthOf(
        soldiers: List<Citizen>,
        traits: TraitAllocation,
        techMultiplier: Double,
        effects: CivEffects,
    ): Double {
        // Each soldier counts for what they are individually, not for an average: a levy of the
        // old and the hungry is worth less than the same number of prime, fed, trained people,
        // which is the whole reason a citizen carries a strength score.
        var sum = 0.0
        for (soldier in soldiers) sum += soldier.strength(traits)
        return sum * techMultiplier + effects.militaryStrength
    }

    /**
     * One day of battle. Attrition on both sides in proportion to the other's strength, with a
     * random component — never a single dice roll that settles a war.
     *
     * Returns casualties as (attacker, defender).
     */
    fun resolveBattleDay(
        attackerStrength: Double,
        defenderStrength: Double,
        attackerSize: Int,
        defenderSize: Int,
        rng: SimRandom,
    ): Pair<Int, Int> {
        if (attackerSize <= 0 || defenderSize <= 0) return 0 to 0
        val total = attackerStrength + defenderStrength
        if (total <= 0.0) return 0 to 0

        fun casualties(ownSize: Int, enemyStrength: Double): Int {
            val share = enemyStrength / total
            val spread = 1.0 + rng.nextDouble(-RivalConfig.COMBAT_RANDOM_SPREAD, RivalConfig.COMBAT_RANDOM_SPREAD)
            val toll = ownSize * RivalConfig.COMBAT_DAILY_ATTRITION * share * 2.0 * spread
            // A battle always costs someone: rounding to zero every day would stalemate forever.
            return max(if (toll >= 1.0) toll.toInt() else if (rng.chance(toll)) 1 else 0, 0)
                .coerceAtMost(ownSize)
        }

        return casualties(attackerSize, defenderStrength) to casualties(defenderSize, attackerStrength)
    }

    /**
     * How much of a resource a civ will trade away: a quarter of whatever it holds above the
     * reserve it wants to keep.
     */
    fun tradeableSurplus(civ: Civilization, resource: Resource, reserve: Double): Double {
        val surplus = civ[resource] - reserve
        return if (surplus <= 0.0) 0.0 else surplus * RivalConfig.TRADE_FRACTION
    }

    /**
     * Safety felt by a civ's citizens: the baseline and its own walls, less the shadow of the
     * strongest rival that has reason to attack it.
     */
    fun safetyFor(
        baseline: Double,
        wallsAndTowers: Double,
        ownStrength: Double,
        worstThreatStrength: Double,
        atWar: Boolean,
    ): Double {
        val raw = baseline + wallsAndTowers
        if (worstThreatStrength <= 0.0) return raw.coerceIn(0.0, 1.0)
        val ratio = worstThreatStrength / max(1.0, ownStrength + worstThreatStrength)
        val penalty = RivalConfig.THREAT_SAFETY_PENALTY * ratio * (if (atWar) 1.0 else 0.6)
        return (raw - penalty).coerceIn(0.0, 1.0)
    }

    /** Whether [weaker] should submit to a tribute demand rather than take the tension hit. */
    fun shouldSubmit(weakerStrength: Double, strongerStrength: Double): Boolean =
        strongerStrength > 0.0 && weakerStrength / strongerStrength < RivalConfig.TRIBUTE_SUBMIT_STRENGTH_RATIO

    /** Clamp helper shared by the interaction code. */
    fun clamp01(value: Double): Double = min(1.0, max(0.0, value))
}
