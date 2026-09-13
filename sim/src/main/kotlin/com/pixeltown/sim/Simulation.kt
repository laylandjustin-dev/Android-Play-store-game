package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Economy
import com.pixeltown.sim.GameConfig.Life
import com.pixeltown.sim.GameConfig.Survival as SurvivalConfig
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.World as WorldConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The simulation: one world, up to five civilisations, and every citizen alive in it.
 *
 * One [step] is one day for everybody. Systems run in a fixed order (below) and iterate citizens
 * in id order, so a tick is a pure function of the state that entered it plus the next draws from
 * the run's single [SimRandom]. Nothing here queries the clock for real time, reads entitlements,
 * or touches Android — this same code path runs live, offline catch-up, and the balance harness.
 *
 * At M2 there are no jobs, so nobody produces food: a colony lives off its founding stores, then
 * starves. That is the intended behaviour and is asserted by `ColonyCollapseTest`.
 */
class Simulation(
    val world: World,
    val civs: List<Civilization>,
    val rng: SimRandom,
    val clock: SimClock = SimClock(),
    val chronicle: Chronicle = Chronicle(),
) {
    /** Everyone alive, always in ascending id order. */
    private val living = ArrayList<Citizen>()

    private val byId = HashMap<Int, Citizen>()

    private var nextCitizenId = 0

    /** Set when the run reaches a terminal state; null while it is still running. */
    var endState: EndState? = null
        private set

    val citizens: List<Citizen> get() = living

    val population: Int get() = living.size

    val day: Long get() = clock.tick

    /**
     * The day as an Int, which is what citizens store for due dates and mourning periods. A run is
     * capped at a few hundred game-years (~180,000 days), so 32 bits is ample.
     */
    private val dayInt: Int get() = clock.tick.toInt()

    val year: Int get() = clock.year

    val season: Season get() = clock.season

    fun civ(id: Int): Civilization = civs[id]

    fun populationOf(civId: Int): Int = living.count { it.civId == civId }

    fun citizenOrNull(id: Int): Citizen? = byId[id]

    // ------------------------------------------------------------------ founding

    /** Places a civ's founding settlers around its home site and stocks its stores. */
    fun found(civ: Civilization, settlers: Int = WorldConfig.STARTING_SETTLERS) {
        val placed = ArrayList<Citizen>(settlers)
        for (n in 0 until settlers) {
            val cell = findFreeCell(civ.homeSite, WorldConfig.SETTLEMENT_SPAWN_RADIUS) ?: break
            val citizen = spawn(
                civId = civ.id,
                cell = cell,
                sex = if (n % 2 == 0) Sex.FEMALE else Sex.MALE,
                ageDays = rng.nextInt(
                    WorldConfig.SETTLER_MIN_AGE_YEARS * Time.DAYS_PER_YEAR,
                    WorldConfig.SETTLER_MAX_AGE_YEARS * Time.DAYS_PER_YEAR,
                ),
                traits = civ.traits,
            )
            placed.add(citizen)
        }

        // Some settlers arrive already partnered, so the colony can grow from day one.
        val females = placed.filter { it.sex == Sex.FEMALE }
        val males = placed.filter { it.sex == Sex.MALE }.toMutableList()
        val pairs = (min(females.size, males.size) * WorldConfig.SETTLER_PARTNERED_SHARE).toInt()
        for (i in 0 until pairs) {
            pair(females[i], males[i])
        }

        civ.add(Resource.FOOD, settlers * Economy.STARTING_FOOD_PER_SETTLER)
        civ.population = placed.size
        world.ownerCivId[civ.homeSite] = civ.id.toByte()
        chronicle.record(
            ChronicleEvent(day, ChronicleEventKind.FOUNDING, civ.id, detail = civ.name, value = placed.size),
        )
    }

    private fun spawn(civId: Int, cell: Int, sex: Sex, ageDays: Int, traits: TraitAllocation): Citizen {
        val citizen = Citizen(
            id = nextCitizenId++,
            x = cell % world.width,
            y = cell / world.width,
            civId = civId,
            sex = sex,
            ageDays = ageDays,
            hp = traits.maxHp,
            nutrition = 1f,
            morale = Life.MORALE_BASELINE.toFloat(),
            survival = 0f,
            job = if (ageDays < Life.CHILD_UNTIL_YEARS * Time.DAYS_PER_YEAR) Job.CHILD else Job.IDLE,
            skill = 0f,
            influence = 0f,
        )
        living.add(citizen)
        byId[citizen.id] = citizen
        world.occupantId[cell] = citizen.id
        return citizen
    }

    /** Spiral search outward from [origin] for a walkable, unoccupied cell. */
    private fun findFreeCell(origin: Int, maxRadius: Int): Int? {
        val ox = origin % world.width
        val oy = origin / world.width
        for (radius in 0..maxRadius) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    // Only the ring at this radius, so cells are visited nearest-first.
                    if (max(abs(dx), abs(dy)) != radius) continue
                    val x = ox + dx
                    val y = oy + dy
                    if (!world.inBounds(x, y)) continue
                    val i = world.index(x, y)
                    if (world.isWalkable(i) && !world.isOccupied(i)) return i
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ the tick

    /** Advances one day. Systems run in this order every tick, for everyone. */
    fun step() {
        clock.runTicks(1) { /* the clock only counts days; the systems below are the tick */ }

        ageEveryone()
        feedEveryone()
        updateBodies()
        recomputeSurvival()
        applyDisease()
        resolveDeaths()
        formPairs()
        conceiveAndBirth()
        moveEveryone()
        driftMoraleAndInfluence()
        updateCivStatistics()
        checkEndState()
    }

    /** Runs [days] whole days. The only entry point offline catch-up and the harness need. */
    fun run(days: Int) {
        repeat(days) {
            if (endState != null) return
            step()
        }
    }

    /** Runs until the run ends or [maxDays] elapse. Returns the number of days actually run. */
    fun runUntilEnd(maxDays: Int): Int {
        var ran = 0
        while (endState == null && ran < maxDays) {
            step()
            ran++
        }
        return ran
    }

    // ------------------------------------------------------------------ systems

    private fun ageEveryone() {
        for (citizen in living) {
            citizen.ageDays++
            if (citizen.job == Job.CHILD && !citizen.isChild) citizen.job = Job.IDLE
        }
    }

    /**
     * Distributes each civ's food store across its citizens.
     *
     * Short rations are shared proportionally rather than first-come-first-served: iterating in id
     * order and feeding until the store ran dry would have starved high-id citizens first, making
     * who dies an artefact of spawn order rather than of the simulation.
     */
    private fun feedEveryone() {
        for (civ in civs) {
            val members = living.filter { it.civId == civ.id }
            if (members.isEmpty()) continue

            val demand = members.sumOf { it.dailyFoodNeed() }
            val available = civ.take(Resource.FOOD, demand)
            val fedFraction = if (demand <= 0.0) 1.0 else available / demand

            for (citizen in members) {
                if (fedFraction >= 1.0) {
                    citizen.nutrition = min(1f, citizen.nutrition + Life.NUTRITION_GAIN_PER_FED_DAY.toFloat())
                } else {
                    val loss = Life.NUTRITION_LOSS_PER_HUNGRY_DAY * (1.0 - fedFraction)
                    citizen.nutrition = max(0f, citizen.nutrition - loss.toFloat())
                }
                citizen.starvingDays = if (citizen.nutrition <= 0f) citizen.starvingDays + 1 else 0
            }

            // Spoilage: anything above storage capacity rots.
            val stored = civ[Resource.FOOD]
            if (stored > civ.foodStorageCapacity) {
                val excess = stored - civ.foodStorageCapacity
                civ[Resource.FOOD] = stored - excess * Economy.SPOILAGE_PER_DAY_OVER_CAPACITY
            }
        }
    }

    private fun updateBodies() {
        for (citizen in living) {
            val maxHp = traitsOf(citizen).maxHp
            if (citizen.nutrition <= 0f) {
                citizen.hp = max(0f, citizen.hp - Life.HP_LOSS_PER_STARVING_DAY.toFloat())
            } else if (citizen.hp < maxHp) {
                citizen.hp = min(maxHp, citizen.hp + Life.HP_REGEN_PER_FED_DAY.toFloat() * citizen.nutrition)
            }
        }
    }

    private fun recomputeSurvival() {
        val severity = seasonSeverity()
        for (citizen in living) {
            citizen.survival = survivalOf(citizen, severity)
        }
    }

    /**
     * The survival score, 0-100. Shelter and care are zero until buildings exist (M4) and safety
     * sits at its baseline until rivals do (M5), so the reachable ceiling at M2 is deliberately
     * below 100.
     */
    private fun survivalOf(citizen: Citizen, severity: Double): Float {
        val traits = traitsOf(citizen)
        val healthNorm = (citizen.hp / traits.maxHp).coerceIn(0f, 1f)

        var score = SurvivalConfig.W_HEALTH * healthNorm +
            SurvivalConfig.W_NUTRITION * citizen.nutrition +
            SurvivalConfig.W_SHELTER * shelterQuality(citizen) +
            SurvivalConfig.W_SAFETY * SurvivalConfig.SAFETY_BASELINE +
            SurvivalConfig.W_CARE * SurvivalConfig.CARE_BASELINE +
            SurvivalConfig.W_MORALE * citizen.morale

        score -= severity * (1.0 - traits.elementsShelter)
        score -= ageFrailty(citizen, traits)
        return score.coerceIn(SurvivalConfig.MIN, SurvivalConfig.MAX).toFloat()
    }

    private fun shelterQuality(citizen: Citizen): Double =
        if (citizen.homeBuildingId == null) SurvivalConfig.SHELTER_QUALITY_HOMELESS else 1.0

    /** Zero until [SurvivalConfig.AGE_FRAILTY_ONSET_FRACTION] of lifespan, then rising to the cap. */
    private fun ageFrailty(citizen: Citizen, traits: TraitAllocation): Double {
        val onset = traits.lifespanDays * SurvivalConfig.AGE_FRAILTY_ONSET_FRACTION
        if (citizen.ageDays <= onset) return 0.0
        val span = traits.lifespanDays - onset
        val progress = ((citizen.ageDays - onset) / span).coerceIn(0.0, 1.0)
        return SurvivalConfig.AGE_FRAILTY_MAX * progress
    }

    /** 0 in the kindest season, 1 in the harshest — the input to seasonal yield damping. */
    private fun seasonSeverity(): Double = SurvivalConfig.SEASON_SEVERITY[season.ordinal]

    private fun applyDisease() {
        for (citizen in living) {
            val traits = traitsOf(citizen)
            val chance = Life.DISEASE_EVENT_BASE_CHANCE * (1.0 - traits.diseaseResist).coerceAtLeast(0.0)
            if (rng.chance(chance)) {
                citizen.hp = max(0f, citizen.hp - Life.DISEASE_HP_DAMAGE.toFloat())
            }
        }
    }

    private fun resolveDeaths() {
        var died = false
        for (citizen in living) {
            val cause = deathCause(citizen) ?: continue
            kill(citizen, cause)
            died = true
        }
        if (died) compactDead()
    }

    /** Returns the cause if this citizen dies today, or null if they live. */
    private fun deathCause(citizen: Citizen): DeathCause? {
        val traits = traitsOf(citizen)

        // Hard causes first, so a discrete death is never masked by the probabilistic roll.
        if (citizen.starvingDays >= Life.STARVATION_CERTAIN_DEATH_DAYS) return DeathCause.STARVATION
        if (citizen.starvingDays >= Life.STARVATION_DAYS &&
            rng.chance(Life.STARVATION_DAILY_DEATH_CHANCE)
        ) {
            return DeathCause.STARVATION
        }
        if (citizen.hp <= 0f) return DeathCause.ILLNESS
        if (citizen.ageDays >= traits.lifespanDays * Life.MAX_AGE_LIFESPAN_MULTIPLE) return DeathCause.OLD_AGE

        val shortfall = 1.0 - citizen.survival / SurvivalConfig.MAX
        val probability = Life.DEATH_BASE *
            shortfall.pow(Life.DEATH_SURVIVAL_EXPONENT) *
            ageMortalityMultiplier(citizen, traits)
        if (!rng.chance(probability)) return null

        return when {
            citizen.ageDays > traits.lifespanDays * SurvivalConfig.AGE_FRAILTY_ONSET_FRACTION -> DeathCause.OLD_AGE
            citizen.nutrition < 0.35f -> DeathCause.STARVATION
            else -> DeathCause.EXPOSURE
        }
    }

    /** 1.0 for a healthy adult, rising with frailty and for the very young. */
    private fun ageMortalityMultiplier(citizen: Citizen, traits: TraitAllocation): Double {
        if (citizen.ageDays < Life.INFANT_AGE_DAYS) return Life.INFANT_MORTALITY_MULTIPLIER
        val onset = traits.lifespanDays * SurvivalConfig.AGE_FRAILTY_ONSET_FRACTION
        if (citizen.ageDays <= onset) return 1.0
        val progress = ((citizen.ageDays - onset) / (traits.lifespanDays - onset)).coerceIn(0.0, 1.0)
        return 1.0 + (Life.AGE_MORTALITY_MULTIPLIER_AT_LIFESPAN - 1.0) * progress
    }

    private fun kill(citizen: Citizen, cause: DeathCause) {
        citizen.alive = false
        world.occupantId[world.index(citizen.x, citizen.y)] = World.NONE

        citizen.partnerId?.let { partnerId ->
            byId[partnerId]?.let { partner ->
                partner.partnerId = null
                partner.widowedOnDay = dayInt
            }
        }

        val civ = civ(citizen.civId)
        civ.totalDeaths++
        chronicle.record(
            ChronicleEvent(day, ChronicleEventKind.DEATH, citizen.civId, citizen.id, deathCause = cause),
        )
    }

    private fun compactDead() {
        val survivors = ArrayList<Citizen>(living.size)
        for (citizen in living) {
            if (citizen.alive) survivors.add(citizen) else byId.remove(citizen.id)
        }
        living.clear()
        living.addAll(survivors)
    }

    private fun formPairs() {
        val candidates = living.filter(::canPair)
        if (candidates.size < 2) return

        // Iterate in id order and pair with the nearest eligible partner, so pairing does not
        // depend on list order after deaths have compacted the population.
        for (citizen in candidates) {
            if (citizen.partnerId != null || citizen.sex != Sex.FEMALE) continue
            if (!rng.chance(Life.PAIR_DAILY_PROBABILITY)) continue
            val partner = candidates
                .filter { it.sex == Sex.MALE && it.partnerId == null && it.civId == citizen.civId }
                .filter { abs(it.ageYears - citizen.ageYears) <= Life.PAIR_MAX_AGE_GAP_YEARS }
                .filter { chebyshev(it, citizen) <= Life.PAIR_SEARCH_RADIUS_CELLS }
                .minByOrNull { chebyshev(it, citizen) } ?: continue
            pair(citizen, partner)
        }
    }

    private fun canPair(citizen: Citizen): Boolean {
        if (citizen.ageYears < Life.PAIR_MIN_AGE_YEARS) return false
        if (citizen.partnerId != null) return false
        val widowed = citizen.widowedOnDay ?: return true
        return dayInt - widowed >= Life.WIDOW_REPAIR_DELAY_DAYS
    }

    private fun pair(a: Citizen, b: Citizen) {
        a.partnerId = b.id
        b.partnerId = a.id
        a.widowedOnDay = null
        b.widowedOnDay = null
        chronicle.record(ChronicleEvent(day, ChronicleEventKind.PAIRING, a.civId, a.id, value = b.id))
    }

    private fun conceiveAndBirth() {
        // Iterate by index over the population as it stood at the start of the tick: births append
        // to `living`, and iterating it directly threw a ConcurrentModificationException. Newborn
        // ids are always the largest, so appending also keeps the list in ascending id order.
        val count = living.size
        for (i in 0 until count) {
            val citizen = living[i]
            // Birth first, so a pregnancy that completes today is not also re-rolled for conception.
            val dueDay = citizen.pregnantUntilDay
            if (dueDay != null && dayInt >= dueDay) {
                citizen.pregnantUntilDay = null
                giveBirth(citizen)
                continue
            }
            if (canConceive(citizen) && rng.chance(conceptionChance(citizen))) {
                citizen.pregnantUntilDay = dayInt + Life.GESTATION_DAYS
            }
        }
    }

    private fun canConceive(citizen: Citizen): Boolean =
        citizen.sex == Sex.FEMALE &&
            !citizen.isPregnant &&
            citizen.partnerId != null &&
            citizen.ageYears in Life.FERTILE_AGE_MIN_YEARS..Life.FERTILE_AGE_MAX_YEARS &&
            citizen.survival > Life.BIRTH_MIN_SURVIVAL &&
            civ(citizen.civId)[Resource.FOOD] > 0.0

    private fun conceptionChance(citizen: Citizen): Double =
        Life.CONCEIVE_BASE * (citizen.survival / SurvivalConfig.MAX) * housingSlack(citizen.civId)

    /** 1.0 until housing exists (M4); then the share of housing capacity still free. */
    private fun housingSlack(civId: Int): Double = 1.0

    private fun giveBirth(mother: Citizen): Citizen? {
        val cell = findFreeCell(world.index(mother.x, mother.y), 3) ?: return null
        val civ = civ(mother.civId)
        val child = spawn(
            civId = mother.civId,
            cell = cell,
            sex = if (rng.nextBoolean()) Sex.FEMALE else Sex.MALE,
            ageDays = 0,
            traits = civ.traits,
        )
        civ.totalBirths++
        chronicle.record(ChronicleEvent(day, ChronicleEventKind.BIRTH, civ.id, child.id, value = mother.id))
        return child
    }

    /**
     * Citizens with no work wander. Movement is greedy and local — one step to a free walkable
     * neighbour — and never stacks two citizens on a cell, which the renderer relies on.
     */
    private fun moveEveryone() {
        for (citizen in living) {
            if (!rng.chance(Life.WANDER_CHANCE_PER_DAY)) continue
            val from = world.index(citizen.x, citizen.y)
            val direction = rng.nextInt(World.NEIGHBOUR_DX.size)
            val nx = citizen.x + World.NEIGHBOUR_DX[direction]
            val ny = citizen.y + World.NEIGHBOUR_DY[direction]
            if (!world.inBounds(nx, ny)) continue
            val to = world.index(nx, ny)
            if (!world.isWalkable(to) || world.isOccupied(to)) continue
            world.occupantId[from] = World.NONE
            world.occupantId[to] = citizen.id
            citizen.x = nx
            citizen.y = ny
        }
    }

    private fun driftMoraleAndInfluence() {
        for (citizen in living) {
            val target = Life.MORALE_BASELINE
            val drift = Life.MORALE_DRIFT_PER_DAY.toFloat()
            citizen.morale = when {
                citizen.morale < target -> min(target.toFloat(), citizen.morale + drift)
                citizen.morale > target -> max(target.toFloat(), citizen.morale - drift)
                else -> citizen.morale
            }
            if (citizen.isAdult) {
                val growth = Life.INFLUENCE_GROWTH_PER_DAY +
                    Life.INFLUENCE_PER_SKILL * citizen.skill * Life.INFLUENCE_GROWTH_PER_DAY +
                    Life.INFLUENCE_PER_MORALE * citizen.morale * Life.INFLUENCE_GROWTH_PER_DAY
                citizen.influence = min(1f, citizen.influence + growth.toFloat())
            }
        }
    }

    private fun updateCivStatistics() {
        val counts = IntArray(civs.size)
        for (citizen in living) counts[citizen.civId]++
        for (civ in civs) civ.population = counts[civ.id]
    }

    private fun checkEndState() {
        val player = civs.firstOrNull { it.isPlayer } ?: return
        if (player.isExtinct) {
            endState = EndState.COLLAPSE
            chronicle.record(
                ChronicleEvent(day, ChronicleEventKind.RUN_ENDED, player.id, detail = EndState.COLLAPSE.name),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun traitsOf(citizen: Citizen): TraitAllocation = civ(citizen.civId).traits

    private fun chebyshev(a: Citizen, b: Citizen): Int = max(abs(a.x - b.x), abs(a.y - b.y))

    /**
     * A cheap, order-independent fingerprint of the whole simulation state, used by the
     * determinism and save round-trip tests. Any divergence anywhere changes this number.
     */
    fun stateHash(): Long {
        var hash = 1125899906842597L
        hash = hash * 31 + day
        for (citizen in living) {
            hash = hash * 31 + citizen.id
            hash = hash * 31 + citizen.x
            hash = hash * 31 + citizen.y
            hash = hash * 31 + citizen.ageDays
            hash = hash * 31 + citizen.hp.toRawBits()
            hash = hash * 31 + citizen.nutrition.toRawBits()
            hash = hash * 31 + citizen.survival.toRawBits()
            hash = hash * 31 + (citizen.partnerId ?: -1)
            hash = hash * 31 + (citizen.pregnantUntilDay ?: -1)
        }
        for (civ in civs) {
            for (store in civ.stores) hash = hash * 31 + store.toRawBits()
            hash = hash * 31 + civ.population
            hash = hash * 31 + civ.techTier
        }
        return hash
    }

    companion object {
        /**
         * Builds a complete new run: generate the world from [seed], found the player with
         * [playerTraits] and the rivals with random legal allocations of the same budget.
         */
        fun newRun(
            seed: Long,
            playerTraits: TraitAllocation,
            settlers: Int = WorldConfig.STARTING_SETTLERS,
        ): Simulation {
            val rng = SimRandom(seed)
            val generated = WorldGenerator.generate(rng)
            val civs = ArrayList<Civilization>(WorldConfig.TOTAL_CIV_COUNT)
            for (id in 0 until WorldConfig.TOTAL_CIV_COUNT) {
                val isPlayer = id == WorldConfig.PLAYER_CIV_ID
                civs.add(
                    Civilization(
                        id = id,
                        name = CIV_NAMES[id],
                        traits = if (isPlayer) playerTraits else TraitAllocation.random(rng),
                        personality = if (isPlayer) Personality.ISOLATIONIST else {
                            Personality.entries[rng.nextInt(Personality.entries.size)]
                        },
                        homeSite = generated.civStartSites[id],
                    ),
                )
            }
            val simulation = Simulation(generated.world, civs, rng)
            for (civ in civs) simulation.found(civ, settlers)
            return simulation
        }

        /** Placeholder names until the naming system arrives with the Premier (M4). */
        val CIV_NAMES = listOf("Aurelia", "Kressen", "Tolmar", "Veyra", "Sildan")
    }
}
