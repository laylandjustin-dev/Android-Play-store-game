package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Politics as PoliticsConfig
import com.pixeltown.sim.GameConfig.Survival as SurvivalConfig
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import kotlin.math.max
import kotlin.math.min

/**
 * Elections, the vote, the Premier's decisions, and unrest.
 *
 * The vote is the heart of it: citizens do not have opinions, they have *needs*, and they vote for
 * whichever candidate's agenda best matches the need they feel most. That makes the electorate a
 * feedback loop on the state of the town — a hungry town elects farmers, a raided town elects
 * generals — rather than a random event each year.
 */
internal object CouncilSystem {

    // ------------------------------------------------------------------ elections

    /**
     * The [PoliticsConfig.CANDIDATE_COUNT] citizens with the highest influence stand for Premier.
     * Ties break by id so a rerun of the same seed produces the same ballot.
     */
    fun chooseCandidates(
        members: List<Citizen>,
        rng: SimRandom,
        favours: BuildingCategory? = null,
    ): List<Candidate> {
        val eligible = members
            .filter { it.ageYears >= PoliticsConfig.VOTING_AGE_YEARS }
            .sortedWith(compareByDescending<Citizen> { it.influence }.thenBy { it.id })
            .take(PoliticsConfig.CANDIDATE_COUNT)

        return eligible.map { citizen ->
            Candidate(
                citizenId = citizen.id,
                // Their own name, not a fresh invention: a candidate is a citizen the player can
                // find on the map, and the Chronicle's Premiers now belong to families the town
                // has heard of.
                name = citizen.fullName,
                ageYears = citizen.ageYears,
                job = citizen.job,
                agenda = Agenda.random(rng, favours),
                temperament = Temperament.entries[rng.nextInt(Temperament.entries.size)],
            )
        }
    }

    /**
     * Every adult votes for the candidate whose agenda best matches their own felt needs.
     *
     * [endorsed] is the candidate the player has spent influence on, who gets a weighted edge.
     */
    fun holdVote(
        members: List<Citizen>,
        candidates: List<Candidate>,
        effects: CivEffects,
        civ: Civilization,
        endorsed: Int?,
    ): ElectionResult? {
        if (candidates.isEmpty()) return null

        val voters = members.filter { it.ageYears >= PoliticsConfig.VOTING_AGE_YEARS }
        if (voters.isEmpty()) return null

        for (candidate in candidates) candidate.votes = 0

        val needs = DoubleArray(BuildingCategory.entries.size)
        for (voter in voters) {
            needs.fill(0.0)
            accumulateNeeds(voter, effects, civ, voters.size, needs)
            var best: Candidate? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (candidate in candidates) {
                var score = 0.0
                for (category in BuildingCategory.entries) {
                    score += candidate.agenda[category] * needs[category.ordinal]
                }
                if (candidate.citizenId == endorsed) {
                    score *= 1.0 + PoliticsConfig.ENDORSE_VOTE_WEIGHT_BONUS
                }
                // Ties break toward the lower citizen id, deterministically.
                if (score > bestScore || (score == bestScore && best != null && candidate.citizenId < best.citizenId)) {
                    bestScore = score
                    best = candidate
                }
            }
            best?.votes = (best?.votes ?: 0) + 1
        }

        val winner = candidates.maxWith(compareBy<Candidate> { it.votes }.thenByDescending { -it.citizenId })
        return ElectionResult(
            day = 0, // filled in by the caller, which owns the clock
            termNumber = 0,
            civId = civ.id,
            winnerName = winner.name,
            winnerCitizenId = winner.citizenId,
            agenda = winner.agenda,
            temperament = winner.temperament,
            voteCounts = candidates.map { it.name to it.votes },
            turnout = voters.size,
        )
    }

    /**
     * What this citizen wants the town to spend itself on, derived from their own condition.
     * Every category keeps a baseline pull so a contented voter still has preferences.
     */
    fun needsOf(
        citizen: Citizen,
        effects: CivEffects,
        civ: Civilization,
        population: Int,
    ): Map<BuildingCategory, Double> {
        val out = DoubleArray(BuildingCategory.entries.size)
        accumulateNeeds(citizen, effects, civ, population, out)
        return BuildingCategory.entries.associateWith { out[it.ordinal] }
    }

    /**
     * The same calculation, accumulated into [into] rather than returning a map.
     *
     * The town-wide aggregate runs this over every citizen, and allocating a five-entry map per
     * citizen made it the most expensive thing in the tick once towns passed a thousand people.
     */
    fun accumulateNeeds(
        citizen: Citizen,
        effects: CivEffects,
        civ: Civilization,
        population: Int,
        into: DoubleArray,
    ) {
        val hunger = 1.0 - citizen.nutrition
        val sickness = 1.0 - (citizen.hp / max(1.0f, civ.traits.maxHp)).toDouble()
        val careShortfall = if (population <= 0) 0.0 else {
            (1.0 - effects.careCapacity / population).coerceIn(0.0, 1.0)
        }
        val fear = 1.0 - (SurvivalConfig.SAFETY_BASELINE + effects.safetyBonus).coerceIn(0.0, 1.0)
        val homeless = if (citizen.homeBuildingId == null) 1.0 else 0.0
        val contentment = citizen.morale.toDouble()

        into[BuildingCategory.FARMS.ordinal] +=
            PoliticsConfig.VOTE_BASELINE + PoliticsConfig.VOTE_HUNGER_WEIGHT * hunger
        into[BuildingCategory.HEALTH.ordinal] +=
            PoliticsConfig.VOTE_BASELINE + PoliticsConfig.VOTE_HEALTH_WEIGHT * max(sickness, careShortfall)
        into[BuildingCategory.MILITARY.ordinal] +=
            PoliticsConfig.VOTE_BASELINE + PoliticsConfig.VOTE_FEAR_WEIGHT * fear
        into[BuildingCategory.LIFESTYLE.ordinal] +=
            PoliticsConfig.VOTE_BASELINE + PoliticsConfig.VOTE_HOMELESS_WEIGHT * homeless
        // Only a town that is fed and safe has any appetite for scholarship.
        into[BuildingCategory.TECH.ordinal] +=
            PoliticsConfig.VOTE_BASELINE + PoliticsConfig.VOTE_CURIOSITY_WEIGHT * contentment * (1.0 - hunger)

        // A personal leaning on top of circumstance. Hardship still swamps it.
        into[citizen.politicalBias.ordinal] += citizen.politicalBiasStrength
    }

    /**
     * The cause a people's character inclines them toward. The player's civ has no such pull —
     * their politics is whatever their own condition makes it.
     */
    fun agendaBiasOf(civ: Civilization): BuildingCategory? = when {
        civ.isPlayer -> null
        else -> when (civ.personality) {
            Personality.MILITANT -> BuildingCategory.MILITARY
            Personality.EXPANSIONIST -> BuildingCategory.FARMS
            Personality.MERCANTILE -> BuildingCategory.LIFESTYLE
            Personality.ISOLATIONIST -> BuildingCategory.HEALTH
        }
    }

    /**
     * What a people's own trait sheet says they should be building, and how strongly.
     *
     * The missing half of the building layer. A civ's agenda came from its Premier's platform and
     * the town's felt needs, and its *traits* — the one thing the player actually chose, and the
     * thing that decides what the town is good at — said nothing at all about what got built. A
     * Hunting people built libraries as readily as a scholarly one.
     *
     * Each trait argues for the category it makes the town good at, weighted by how far above base
     * it stands, so a flat people has no lean and a committed one has a strong one. This is the same
     * principle as AD-33 (personality should be legible in what a civ *does*) applied to the trait
     * sheet instead of to the personality, and it applies to the player's civ too — their allocation
     * is a statement of intent, and the town acting on it is what makes the choice mean something.
     */
    fun traitLeanOf(traits: TraitAllocation): Map<BuildingCategory, Double> {
        val lean = DoubleArray(BuildingCategory.entries.size)
        fun add(category: BuildingCategory, trait: Trait) {
            val above = traits[trait] - TraitConfig.BASE_VALUE
            if (above > 0) lean[category.ordinal] += above.toDouble()
        }
        // Farming feeds the town; Elements is the other half of surviving a bad year on stores.
        add(BuildingCategory.FARMS, Trait.FARMING)
        add(BuildingCategory.FARMS, Trait.ELEMENTS)
        // Hunting doubles as military effectiveness, so a hunting people are a fighting people.
        add(BuildingCategory.MILITARY, Trait.HUNTING)
        // Health is the trait that makes clinics and aqueducts worth their stone.
        add(BuildingCategory.HEALTH, Trait.HEALTH)
        // Speed is work rate and research rate both, which is what tech buildings multiply.
        add(BuildingCategory.TECH, Trait.SPEED)
        // Gathering is the building trait: a people who work timber fast build the town out.
        add(BuildingCategory.LIFESTYLE, Trait.GATHERING)

        val total = lean.sum()
        if (total <= 0.0) return BuildingCategory.entries.associateWith { 0.0 }
        return BuildingCategory.entries.associateWith { lean[it.ordinal] / total }
    }

    // ------------------------------------------------------------------ the Premier's decisions

    /**
     * What to build next, chosen from the Premier's agenda and their temperament.
     *
     * A [Temperament.PRAGMATIC] Premier mostly follows the town's actual need; a
     * [Temperament.ZEALOT] follows their agenda almost regardless of it. That deviation is the
     * whole point of the office — an ideologue who builds temples through a famine is a story.
     */
    fun chooseCategory(
        premier: Premier,
        need: Map<BuildingCategory, Double>,
        rng: SimRandom,
        /** What this people's traits incline them toward — see [traitLeanOf]. */
        traitLean: Map<BuildingCategory, Double> = emptyMap(),
        /**
         * How badly the town is doing, 0 (thriving) to 1 (in trouble).
         *
         * The balance the whole thing turns on. A comfortable town may indulge its character; a
         * hungry one may not, and as this rises the trait lean is squeezed out in favour of what the
         * town actually needs. Without it a Hunting people would go on building watchtowers through
         * a famine, which is a story the *Premier's* temperament is already there to tell — a
         * people's character should shape a good decade, not override survival.
         */
        distress: Double = 0.0,
    ): BuildingCategory {
        val deviation = PoliticsConfig.TEMPERAMENT_DEVIATION.getValue(premier.temperament)
        val leanWeight = PoliticsConfig.TRAIT_LEAN_WEIGHT * (1.0 - distress.coerceIn(0.0, 1.0))
        val scores = BuildingCategory.entries.map { category ->
            val needScore = need[category] ?: 0.0
            val agendaScore = premier.agenda[category]
            val leanScore = traitLean[category] ?: 0.0
            val political = (1.0 - deviation) * needScore + deviation * agendaScore
            category to (1.0 - leanWeight) * political + leanWeight * leanScore
        }
        val total = scores.sumOf { it.second }
        if (total <= 0.0) return premier.agenda.dominant

        // Weighted draw rather than argmax, so a town's building list has variety in it.
        var roll = rng.nextDouble(total)
        for ((category, score) in scores) {
            roll -= score
            if (roll <= 0.0) return category
        }
        return scores.last().first
    }

    /**
     * Job weights implied by an agenda. This is how politics reaches the economy.
     *
     * Two shares are protected from the agenda entirely: food, which no ideology may starve
     * below [GameConfig.Economy.MIN_FOOD_WORKER_SHARE], and materials, without which the town
     * cannot build whatever the Premier is elected to build. What is left is split by agenda.
     */
    fun jobWeightsFor(agenda: Agenda, buildingInProgress: Boolean): Map<Job, Double> {
        val farms = agenda[BuildingCategory.FARMS]
        val health = agenda[BuildingCategory.HEALTH]
        val military = agenda[BuildingCategory.MILITARY]
        val tech = agenda[BuildingCategory.TECH]
        val lifestyle = agenda[BuildingCategory.LIFESTYLE]

        val infraShare = GameConfig.Economy.INFRASTRUCTURE_WORKER_SHARE *
            (if (buildingInProgress) 1.0 else 0.7)
        val minFood = GameConfig.Economy.MIN_FOOD_WORKER_SHARE
        val discretionary = (1.0 - minFood - infraShare).coerceAtLeast(0.0)

        // A Farms agenda spends its discretionary share on more food; everyone else's goes to
        // their own priority.
        val foodShare = minFood + discretionary * farms
        val remaining = (discretionary * (1.0 - farms)).coerceAtLeast(0.0)
        val otherTotal = (health + military + tech + lifestyle).coerceAtLeast(1e-9)

        // The farm/hunt split stays fixed; see AD-25 in CLAUDE.md.
        val farmerShare = foodShare * FARM_SHARE_OF_FOOD
        return mapOf(
            Job.FARMER to farmerShare,
            Job.HUNTER to foodShare - farmerShare,
            Job.BUILDER to infraShare * GameConfig.Economy.BUILDER_SHARE_OF_INFRASTRUCTURE,
            Job.GATHERER to infraShare * (1.0 - GameConfig.Economy.BUILDER_SHARE_OF_INFRASTRUCTURE),
            Job.HEALER to remaining * health / otherTotal,
            Job.SOLDIER to remaining * military / otherTotal,
            Job.SCHOLAR to remaining * tech / otherTotal,
            Job.ARTISAN to remaining * lifestyle / otherTotal,
        )
    }

    // ------------------------------------------------------------------ unrest

    /**
     * Unrest rises when the town's felt needs go unanswered by the sitting Premier, and decays
     * when they are met. [needGap] is how far the Premier's agenda sits from what people want.
     */
    fun updateUnrest(civ: Civilization, needGap: Double, meanSurvival: Double) {
        val suffering = (1.0 - meanSurvival / SurvivalConfig.MAX).coerceIn(0.0, 1.0)
        val pressure = suffering * needGap
        civ.unrest = if (pressure > PoliticsConfig.UNREST_PRESSURE_THRESHOLD) {
            min(1.0, civ.unrest + PoliticsConfig.UNREST_PER_DAY_PER_UNMET_NEED * pressure)
        } else {
            max(0.0, civ.unrest - PoliticsConfig.UNREST_DECAY_PER_DAY)
        }
    }

    /** The share of food jobs that go to farming rather than hunting. */
    private const val FARM_SHARE_OF_FOOD = 0.70
}
