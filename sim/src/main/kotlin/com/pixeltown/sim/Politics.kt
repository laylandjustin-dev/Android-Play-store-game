package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Politics as PoliticsConfig
import kotlin.math.abs

/**
 * A normalised weight vector over the five building categories: what a Premier believes the town
 * should spend itself on.
 */
data class Agenda(private val weights: DoubleArray) {

    init {
        require(weights.size == BuildingCategory.entries.size) { "an agenda needs one weight per category" }
    }

    operator fun get(category: BuildingCategory): Double = weights[category.ordinal]

    val dominant: BuildingCategory
        get() = BuildingCategory.entries.maxBy { weights[it.ordinal] }

    /** Distance from another agenda, 0 (identical) to 1 (opposed). Used by the vote. */
    fun distanceTo(other: Agenda): Double {
        var sum = 0.0
        for (i in weights.indices) sum += abs(weights[i] - other.weights[i])
        return (sum / 2.0).coerceIn(0.0, 1.0)
    }

    /** A copy with one category's weight shifted, renormalised. The player's petition uses this. */
    fun shifted(category: BuildingCategory, delta: Double): Agenda {
        val copy = weights.copyOf()
        copy[category.ordinal] = (copy[category.ordinal] + delta).coerceIn(0.0, 1.0)
        return of(copy)
    }

    fun toMap(): Map<BuildingCategory, Double> =
        BuildingCategory.entries.associateWith { weights[it.ordinal] }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Agenda && weights.contentEquals(other.weights))

    override fun hashCode(): Int = weights.contentHashCode()

    override fun toString(): String =
        BuildingCategory.entries.joinToString(" ") { "${it.name.take(4)}=%.2f".format(weights[it.ordinal]) }

    companion object {
        /** Normalises any set of non-negative weights into an agenda. */
        fun of(raw: DoubleArray): Agenda {
            val total = raw.sum()
            if (total <= 0.0) return balanced()
            return Agenda(DoubleArray(raw.size) { raw[it] / total })
        }

        fun of(map: Map<BuildingCategory, Double>): Agenda =
            of(DoubleArray(BuildingCategory.entries.size) { map[BuildingCategory.entries[it]] ?: 0.0 })

        fun balanced(): Agenda =
            Agenda(DoubleArray(BuildingCategory.entries.size) { 1.0 / BuildingCategory.entries.size })

        /**
         * A candidate's platform: one category they care about most, a second they will tolerate,
         * and a floor under the rest so no agenda ignores a category entirely.
         *
         * [favours] tilts the draw toward a category — that is how a civ's personality reaches its
         * politics. A militant people elect generals more often than a mercantile one does, which
         * is what keeps a standing army in existence in peacetime and stops the whole map settling
         * into a permanent, eventless peace.
         */
        fun random(rng: SimRandom, favours: BuildingCategory? = null): Agenda {
            val raw = DoubleArray(BuildingCategory.entries.size) {
                PoliticsConfig.AGENDA_FLOOR + rng.nextDouble(0.0, PoliticsConfig.AGENDA_NOISE)
            }
            val primary = if (favours != null && rng.chance(PoliticsConfig.PERSONALITY_AGENDA_CHANCE)) {
                favours.ordinal
            } else {
                rng.nextInt(raw.size)
            }
            raw[primary] += PoliticsConfig.AGENDA_PRIMARY_WEIGHT
            raw[rng.nextInt(raw.size)] += PoliticsConfig.AGENDA_SECONDARY_WEIGHT
            return of(raw)
        }
    }
}

/** Someone standing for Premier. */
class Candidate(
    val citizenId: Int,
    val name: String,
    val ageYears: Int,
    val job: Job,
    val agenda: Agenda,
    val temperament: Temperament,
) {
    var votes: Int = 0

    /** A one-line pitch, generated from the agenda and temperament. Templates, never an LLM. */
    val pitch: String = PitchWriter.write(agenda, temperament)

    override fun toString(): String = "$name ($ageYears, ${job.name.lowercase()}) — $pitch"
}

/** Builds a candidate's pitch line from their platform. */
object PitchWriter {

    private val PROMISE = mapOf(
        BuildingCategory.FARMS to listOf(
            "Full granaries before anything else.",
            "No child of this town will go hungry.",
            "We will make the fields yield.",
        ),
        BuildingCategory.HEALTH to listOf(
            "Clean water and a healer on every street.",
            "We bury too many. That ends.",
            "Care for the old, and the young will follow.",
        ),
        BuildingCategory.MILITARY to listOf(
            "Walls first. Ask the dead of the last raid.",
            "Let them look at our towers and think again.",
            "A town that cannot defend itself is a larder.",
        ),
        BuildingCategory.TECH to listOf(
            "Knowledge compounds. Nothing else does.",
            "Build the library and the rest follows.",
            "Our grandchildren will thank us for the Academy.",
        ),
        BuildingCategory.LIFESTYLE to listOf(
            "Roofs, and somewhere to gather under them.",
            "A people with nothing to celebrate stop working.",
            "Housing for all, and a plaza worth walking to.",
        ),
    )

    private val TONE = mapOf(
        Temperament.PASSIVE to "I will not gamble with what we have.",
        Temperament.PRAGMATIC to "I will spend where the need is greatest.",
        Temperament.AMBITIOUS to "We have been too careful for too long.",
        Temperament.ZEALOT to "There is one priority, and I will not be moved from it.",
    )

    fun write(agenda: Agenda, temperament: Temperament): String {
        val promises = PROMISE.getValue(agenda.dominant)
        // Deterministic choice: agendas with the same shape get the same line.
        val index = ((agenda[agenda.dominant] * 1000).toInt()) % promises.size
        return "${promises[index]} ${TONE.getValue(temperament)}"
    }
}

/** The sitting Premier and their term. */
class Premier(
    val citizenId: Int,
    val name: String,
    agenda: Agenda,
    val temperament: Temperament,
    val electedOnDay: Long,
    val termNumber: Int,
    /** True when installed by a coup rather than a vote. */
    val byCoup: Boolean = false,
) {
    /** The agenda can be shifted mid-term by a player petition. */
    var agenda: Agenda = agenda
        private set

    /** What was built during this term, for the Ledger's record of past Premiers. */
    val built = ArrayList<BuildingType>()

    var petitionActive: Boolean = false
        private set

    fun applyPetition(category: BuildingCategory, delta: Double) {
        agenda = agenda.shifted(category, delta)
        petitionActive = true
    }

    fun clearPetition(original: Agenda) {
        agenda = original
        petitionActive = false
    }
}

/** The result of one election, kept for the Ledger. */
data class ElectionResult(
    val day: Long,
    val termNumber: Int,
    val civId: Int,
    val winnerName: String,
    val winnerCitizenId: Int,
    val agenda: Agenda,
    val temperament: Temperament,
    val voteCounts: List<Pair<String, Int>>,
    val turnout: Int,
) {
    val totalVotes: Int get() = voteCounts.sumOf { it.second }
}

/** Name generation for candidates. Syllables, not a name list, so it never repeats itself. */
object NameGenerator {

    private val FIRST = listOf(
        "Ald", "Bren", "Cor", "Dara", "Eil", "Fen", "Gar", "Hal", "Ira", "Jor",
        "Kel", "Lys", "Mar", "Nev", "Oren", "Pell", "Quin", "Ros", "Sar", "Tam",
        "Ulf", "Ver", "Wyn", "Yar", "Zel",
    )
    private val SECOND = listOf(
        "a", "en", "is", "or", "wen", "ric", "dal", "mir", "ath", "ell",
        "und", "ora", "iel", "arn", "ost",
    )
    private val FAMILY = listOf(
        "Ashgrove", "Blackfen", "Coldwater", "Dunmoor", "Eastmarch", "Fairholt",
        "Greyhill", "Hearthstone", "Ironbrook", "Larkfield", "Mossbank", "Northreach",
        "Oakhollow", "Pinewatch", "Redbarrow", "Stonewell", "Thornfield", "Westmere",
    )

    fun name(rng: SimRandom): String =
        "${rng.pick(FIRST)}${rng.pick(SECOND)} ${rng.pick(FAMILY)}"
}
