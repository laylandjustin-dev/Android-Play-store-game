package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Economy
import com.pixeltown.sim.GameConfig.Life
import com.pixeltown.sim.GameConfig.Meta
import com.pixeltown.sim.GameConfig.Politics
import com.pixeltown.sim.GameConfig.Rivals as RivalConfig
import com.pixeltown.sim.GameConfig.Survival as SurvivalConfig
import com.pixeltown.sim.GameConfig.Traits as TraitConfig
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.World as WorldConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

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
    internal val living = ArrayList<Citizen>()

    internal val byId = HashMap<Int, Citizen>()

    internal var nextCitizenId = 0

    /** Work cell -> citizen id, so two workers never claim the same field. */
    internal val workClaims = HashMap<Int, Int>()

    /** Every structure on the map, finished or not. */
    internal val allBuildings = ArrayList<Building>()

    /** The same buildings, indexed by civ. */
    internal val buildingsByCiv = Array(civs.size) { ArrayList<Building>() }

    /**
     * Per-civ population index, rebuilt once per tick.
     *
     * Without it, systems that needed "this civ's citizens" filtered the whole living list on
     * every call, and two of them — care access in the survival score, and housing slack in the
     * conception check — did so *per citizen*, making the tick O(population^2). At 2,300 people a
     * tick cost 6.6ms against a 1ms budget at 100x speed.
     */
    private val membersByCiv = Array(civs.size) { ArrayList<Citizen>() }
    private val healersByCiv = IntArray(civs.size)
    private val housedByCiv = IntArray(civs.size)

    internal fun refreshPopulationIndex() {
        for (list in membersByCiv) list.clear()
        healersByCiv.fill(0)
        housedByCiv.fill(0)
        for (citizen in living) {
            membersByCiv[citizen.civId].add(citizen)
            if (citizen.job == Job.HEALER) healersByCiv[citizen.civId]++
            if (citizen.homeBuildingId != null) housedByCiv[citizen.civId]++
        }
    }

    internal var nextBuildingId = 0

    /** Per-civ building effects, recomputed whenever the building list changes. */
    private val effects = Array(civs.size) { CivEffects.NONE }

    /** The sitting Premier of each civ, null before the first election. */
    internal val premiers = arrayOfNulls<Premier>(civs.size)

    /** The agenda a Premier was elected on, so a petition can be reverted when it expires. */
    internal val electedAgendas = arrayOfNulls<Agenda>(civs.size)

    /** Candidates during the campaign window, per civ. */
    internal val campaigns = arrayOfNulls<List<Candidate>>(civs.size)

    /** The candidate the player has endorsed this campaign. */
    internal val endorsements = arrayOfNulls<Int>(civs.size)

    /** True when the player has vetoed the Premier's next build order. */
    internal val vetoPending = BooleanArray(civs.size)

    /** Every election held, newest last. The Ledger's record of who governed and why. */
    val elections = ArrayList<ElectionResult>()

    /** How every civ feels about every other one. */
    var relations = Relations(civs.size)
        private set

    internal fun relationsRestoredFrom(save: RelationsSave) {
        relations = Relations.restore(save)
    }

    /** Armies and raiding parties currently in the field. */
    internal val armies = ArrayList<Army>()

    internal var nextArmyId = 0

    /** Military strength per civ, recomputed once per tick. */
    private val strength = DoubleArray(civs.size)

    val armiesInField: List<Army> get() = armies

    val buildings: List<Building> get() = allBuildings

    /**
     * Buildings belonging to one civ. Indexed rather than filtered: this is read several times
     * per tick per civ, and scanning a list of hundreds of buildings each time showed up as a
     * dominant cost once towns grew.
     */
    fun buildingsOf(civId: Int): List<Building> = buildingsByCiv[civId]

    fun premierOf(civId: Int): Premier? = premiers[civId]

    fun effectsOf(civId: Int): CivEffects = effects[civId]

    /** The candidates standing in [civId]'s current election, or null outside a campaign. */
    fun campaignFor(civId: Int): List<Candidate>? = campaigns[civId]

    /** The candidate the player has endorsed this campaign, or null. For the election screen. */
    fun endorsementFor(civId: Int): Int? = endorsements[civId]

    /** Set when the run reaches a terminal state; null while it is still running. */
    var endState: EndState? = null
        private set

    /**
     * The configuration this run began with, read once at run start and never again — which is
     * what keeps a purchase from altering a run already in progress (AD-8).
     */
    var config: RunConfig = RunConfig(seed = 0L, traits = TraitAllocation.BASE)
        internal set

    /**
     * Which colour each civ is drawn in. Derived from [config] when the run starts, and held here
     * because the renderer needs it every frame and a colour is per-run state, not a global.
     */
    val colors: CivColors get() = config.colors

    /**
     * True while the run is being advanced with nobody watching — offline catch-up, or a headless
     * harness sweep. Decisions the game would otherwise hold open for the player are taken for
     * them while this is set, and only while it is set.
     */
    var unattended: Boolean = false
        private set

    /**
     * Runs [days] with nobody watching: the decisions live play holds open for the player are taken
     * on their behalf for the duration. Offline catch-up and the headless harness use this; live
     * play never does, which is what keeps the decade's trait point the player's to make.
     */
    fun runUnattended(days: Int) {
        unattended = true
        try {
            run(days)
        } finally {
            unattended = false
        }
    }

    /** As [runUntilEnd], with nobody watching. */
    fun runUnattendedUntilEnd(maxDays: Int): Int {
        unattended = true
        return try {
            runUntilEnd(maxDays)
        } finally {
            unattended = false
        }
    }

    /** The player's meta progression. Saved alongside the run. */
    var legacy: Legacy = Legacy()
        internal set

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
            // Founders were farmers and hunters somewhere before they emigrated. See
            // WorldConfig.SETTLER_STARTING_SKILL for why starting them at zero was the founding
            // cliff rather than a detail.
            citizen.skill = WorldConfig.SETTLER_STARTING_SKILL
            citizen.vigour = rng.nextDouble(TraitConfig.VIGOUR_MIN, TraitConfig.VIGOUR_MAX).toFloat()
            placed.add(citizen)
        }

        // Some settlers arrive already partnered, so the colony can grow from day one.
        val females = placed.filter { it.sex == Sex.FEMALE }
        val males = placed.filter { it.sex == Sex.MALE }.toMutableList()
        val pairs = (min(females.size, males.size) * WorldConfig.SETTLER_PARTNERED_SHARE).toInt()
        for (i in 0 until pairs) {
            pair(females[i], males[i])
        }

        // Set rather than added: the founding stores were carried ashore, not grown here, and the
        // ledger behind the end-of-run breakdown reports what the town *produced*.
        civ[Resource.FOOD] = settlers * Economy.STARTING_FOOD_PER_SETTLER
        refreshEffects(civ.id)
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
            influence = config.startingInfluence,
        ).apply {
            politicalBias = BuildingCategory.entries[rng.nextInt(BuildingCategory.entries.size)]
            politicalBiasStrength = rng.nextDouble(0.0, Politics.VOTE_BIAS_MAX).toFloat()
            // Everyone is named here, founders and newborns alike, so no code path can produce an
            // anonymous citizen. `giveBirth` overwrites the family afterwards with the parents'.
            nameCode = CitizenNames.randomGiven(rng)
            familyId = CitizenNames.randomFamily(rng)
        }
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
    /**
     * Advances one day. Returns false if it did nothing because the world is waiting on a decision
     * only the player can make.
     *
     * The return value is not decoration. A tier reached stops the world — nothing below runs, not
     * even the clock — and a caller looping `while (day < target) step()` would otherwise spin
     * forever, which is exactly what happened to the balance tests the first time this landed. A
     * caller that ignores the result and bounds its own loop by day count is writing an infinite
     * loop; [run] and [runUntilEnd] already stop, and [runUnattended] resolves the decision instead.
     */
    fun step(): Boolean {
        if (awaitingPlayer) return false

        clock.runTicks(1) { /* the clock only counts days; the systems below are the tick */ }

        refreshPopulationIndex()

        ageEveryone()
        awardGenerationPoints()
        runCouncil()
        assignJobsIfDue()
        produceGoods()
        advanceConstruction()
        payUpkeep()
        feedEveryone()
        assignHousing()
        updateBodies()
        recomputeSurvival()
        applyDisease()
        resolveDeaths()
        // Deaths have compacted the population; rebuild the index before the rest of the tick.
        refreshPopulationIndex()
        formPairs()
        conceiveAndBirth()
        moveEveryone()
        EconomySystem.regenerateLand(world, civs)
        driftMoraleAndInfluence()
        advanceTech()
        updateUnrest()
        runDiplomacy()
        marchArmies()
        fightBattles()
        updateCivStatistics()
        checkEndState()
        return true
    }

    /** Runs [days] whole days. The only entry point offline catch-up and the harness need. */
    fun run(days: Int) {
        repeat(days) {
            if (endState != null || awaitingPlayer) return
            step()
        }
    }

    /** Runs until the run ends or [maxDays] elapse. Returns the number of days actually run. */
    fun runUntilEnd(maxDays: Int): Int {
        var ran = 0
        while (endState == null && !awaitingPlayer && ran < maxDays) {
            step()
            ran++
        }
        return ran
    }

    // ------------------------------------------------------------------ systems

    // ------------------------------------------------------------------ the council

    /**
     * The political cycle: candidates are announced, the vote is held every
     * [Politics.TERM_YEARS] years, and the Premier acts once per season. Between those moments this
     * does nothing at all.
     *
     * The term is measured against the whole elapsed day count rather than the year, so a four-year
     * cycle stays on the same footing after a save, an offline catch-up, or an early election.
     */
    private fun runCouncil() {
        val term = Politics.TERM_YEARS * Time.DAYS_PER_YEAR
        val first = Politics.FIRST_ELECTION_YEAR * Time.DAYS_PER_YEAR

        // The founding year is special-cased: a town cannot build without a Premier, so it elects
        // one promptly and then keeps it for a full term. See Politics.FIRST_ELECTION_YEAR.
        val voteToday = if (day < first) false else (day - first) % term == 0L
        val campaignOpensToday = when {
            day < first -> day == (first - Politics.CAMPAIGN_DAYS).toLong()
            else -> (day - first + Politics.CAMPAIGN_DAYS) % term == 0L
        }

        if (campaignOpensToday) openCampaigns()
        if (voteToday && day > 0) holdElections()
        if (day % Politics.DAYS_PER_DECISION == 0L && day > 0) premierDecisions()
    }

    /**
     * A people grows. Every [TraitConfig.GENERATION_INTERVAL_YEARS] years each surviving civ earns
     * a trait point.
     *
     * The award is driven by a counter rather than by `day % interval == 0`, so a civ founded late
     * or restored from a save cannot be skipped or paid twice. Rivals spend on the spot (their
     * choice is a weighted draw, so it has to happen at a fixed point in the tick to stay
     * deterministic); the player's accumulate until they decide, which is the whole point of the
     * mechanic — a decision every ten years, on what the run has taught them so far.
     */
    private fun awardGenerationPoints() {
        val interval = TraitConfig.GENERATION_INTERVAL_YEARS * Time.DAYS_PER_YEAR
        if (interval <= 0) return
        val due = (day / interval).toInt()

        for (civ in civs) {
            if (civ.generationsAwarded >= due) continue
            // The population check comes *first*. Marking the decade paid and then skipping it meant
            // a civ that happened to read as empty at this instant forfeited the points permanently —
            // and `population` is refreshed at the end of the tick, so it is one day stale here. For a
            // truly extinct civ the difference is invisible; for a living one it is a lost decade.
            if (civ.population == 0) continue // an extinct people grows no more
            val owed = due - civ.generationsAwarded
            civ.generationsAwarded = due

            if (civ.unspentTraitPoints == 0) civ.oldestUnspentPointDay = day
            civ.unspentTraitPoints += owed * TraitConfig.GENERATION_POINTS_PER_AWARD
            if (civ.isPlayer) {
                chronicle.record(
                    ChronicleEvent(
                        day, ChronicleEventKind.GENERATION, civ.id, civ.id,
                        detail = "a generation comes of age: ${civ.unspentTraitPoints} trait point" +
                            (if (civ.unspentTraitPoints == 1) "" else "s") + " to spend",
                        value = civ.unspentTraitPoints,
                    ),
                )
            } else {
                while (civ.unspentTraitPoints > 0) {
                    val trait = RivalStrategist.chooseGrowth(civ.personality, civ.traits, rng) ?: break
                    if (!raiseTrait(civ, trait)) break
                }
            }
        }

        // With nobody watching, the player's point is spent the same day a rival's is — the safe
        // answer of `needBasedGrowth`, never the strong one. This is what makes the headless
        // harness measure a symmetric game: before it, the sweep's player civ banked its points
        // for a year while four rivals spent theirs immediately, and the harness read the
        // difference as difficulty.
        if (!unattended) return
        for (civ in civs) {
            if (!civ.isPlayer) continue
            while (civ.unspentTraitPoints > 0) {
                val trait = needBasedGrowth(civ) ?: break
                if (!raiseTrait(civ, trait)) break
                civ.autoSpentTraitPoints++
            }
        }
    }

    /**
     * Where a point goes when nobody chooses: food if the town is hungry or cannot feed itself,
     * otherwise the weakest trait it still has room in.
     *
     * Deliberately the *safe* answer rather than the strong one. Rounding a people out is never a
     * losing move, which is what makes it fair to apply on a player's behalf — and it leaves the
     * upside (committing hard to one idea) to a player who is actually watching.
     */
    internal fun needBasedGrowth(civ: Civilization): Trait? {
        val improvable = civ.traits.improvable
        if (improvable.isEmpty()) return null

        if (Trait.FARMING in improvable) {
            if (civ.traits[Trait.FARMING] < RivalConfig.MIN_VIABLE_FARMING) return Trait.FARMING
            val daysOfFood = if (civ.population > 0) {
                civ[Resource.FOOD] / (civ.population * Economy.FOOD_PER_ADULT_PER_DAY)
            } else {
                Double.MAX_VALUE
            }
            if (daysOfFood < Economy.HUNGRY_TOWN_FOOD_DAYS) return Trait.FARMING
        }
        return improvable.minByOrNull { civ.traits[it] * TraitConfig.COUNT + it.ordinal }
    }

    /**
     * Spends one of [civId]'s earned points on [trait]. False if there is nothing to spend or the
     * trait is already at its ceiling — the caller's request was simply not legal.
     */
    fun spendTraitPoint(civId: Int, trait: Trait): Boolean {
        val civ = civ(civId)
        if (civ.unspentTraitPoints <= 0) return false
        return raiseTrait(civ, trait)
    }

    private fun raiseTrait(civ: Civilization, trait: Trait): Boolean {
        val grown = civ.traits.withPointIn(trait) ?: return false
        civ.traits = grown
        civ.unspentTraitPoints--
        civ.traitGrowthHistory.add(trait)
        // Anything derived from the traits has to be recomputed now. Food storage scales with
        // Elements, and it was only ever refreshed when a building completed — so a civ that grew
        // an Elements point kept a stale capacity until its next build, while a reload recomputed
        // it correctly. The save round-trip test caught the mismatch, which is what it is for.
        refreshEffects(civ.id)
        if (civ.isPlayer) {
            chronicle.record(
                ChronicleEvent(
                    day, ChronicleEventKind.GENERATION, civ.id, civ.id,
                    detail = "${trait.name.lowercase()} rises to ${grown[trait]}",
                    value = grown[trait],
                ),
            )
        }
        return true
    }

    private fun openCampaigns() {
        for (civ in civs) {
            val members = membersOf(civ.id)
            if (members.isEmpty()) continue
            campaigns[civ.id] = CouncilSystem.chooseCandidates(members, rng, CouncilSystem.agendaBiasOf(civ))
            endorsements[civ.id] = null
            // The player's election stops the clock until they have seen the slate. A rival's does
            // not: there is nobody to stop for.
            if (civ.isPlayer) electionPending = true
        }
    }

    /**
     * True while the player's election is waiting to be looked at.
     *
     * An election is the one yearly event the player's four influence levers exist for, and it used
     * to happen while the world kept moving: at 10x the thirty-day campaign window is three seconds,
     * so in practice the slate appeared and the vote was counted before a player could read a single
     * candidate's pitch. It now pauses exactly as a tech tier and a growth point do (AD-59).
     *
     * [acknowledgeElection] clears it, whether the player endorsed somebody or chose to stay out of
     * it — which is why the UI offers "skip". Not acting is a legitimate answer to an election and
     * must not be more expensive than acting, so skipping costs nothing and the pause never repeats
     * for the same campaign.
     */
    var electionPending: Boolean = false
        internal set

    /**
     * Dismisses the election pause. Returns false when nothing was waiting.
     *
     * Called both when the player endorses somebody — endorsing *is* the decision, so the dialog
     * closes with it — and when they choose to stay out of it. Skipping stays free.
     */
    fun acknowledgeElection(): Boolean {
        if (!electionPending) return false
        electionPending = false
        return true
    }

    private fun holdElections() {
        // Whatever happened, the campaign is over: an unacknowledged pause must not survive the
        // vote it was about and strand the clock on a slate that no longer exists.
        electionPending = false
        for (civ in civs) {
            val members = membersOf(civ.id)
            val candidates = campaigns[civ.id]
            if (members.isEmpty() || candidates.isNullOrEmpty()) continue

            val result = CouncilSystem.holdVote(
                members = members,
                candidates = candidates,
                effects = effects[civ.id],
                civ = civ,
                endorsed = endorsements[civ.id],
            ) ?: continue

            civ.termCount++
            civ.vetoesUsedThisYear = 0
            installPremier(
                civ,
                Premier(
                    citizenId = result.winnerCitizenId,
                    name = result.winnerName,
                    agenda = result.agenda,
                    temperament = result.temperament,
                    electedOnDay = day,
                    termNumber = civ.termCount,
                ),
            )

            val recorded = result.copy(day = day, termNumber = civ.termCount)
            elections.add(recorded)
            chronicle.record(
                ChronicleEvent(
                    day, ChronicleEventKind.ELECTION, civ.id, result.winnerCitizenId,
                    detail = "${result.winnerName} (${result.temperament.name.lowercase()}, ${result.agenda.dominant.name.lowercase()})",
                    value = result.totalVotes,
                ),
            )
            campaigns[civ.id] = null
            endorsements[civ.id] = null
        }
    }

    private fun installPremier(civ: Civilization, premier: Premier) {
        // A charter outlives the Premier who was in office when it was written: every incoming
        // agenda is weighted toward it. This is the one instruction a player can leave that lasts
        // longer than a term, which is what makes the building layer something you steer rather
        // than something you watch.
        civ.charter?.let { premier.applyCharter(it, Politics.CHARTER_WEIGHT) }
        premiers[civ.id] = premier
        electedAgendas[civ.id] = premier.agenda
    }

    /**
     * Leaves a standing instruction that every future Premier weights toward, or clears it.
     *
     * Costs influence to set or change; clearing is free. Applies to the sitting Premier at once,
     * so the player sees it take effect rather than waiting for an election.
     */
    fun setCharter(civId: Int, category: BuildingCategory?): Boolean {
        val civ = civ(civId)
        if (category == civ.charter) return false
        if (category != null && !spendInfluence(civId, Politics.COST_CHARTER)) return false

        val previous = civ.charter
        civ.charter = category
        premiers[civId]?.let { premier ->
            // Take the old charter back out before adding the new one, or they compound.
            previous?.let { premier.applyCharter(it, -Politics.CHARTER_WEIGHT) }
            category?.let { premier.applyCharter(it, Politics.CHARTER_WEIGHT) }
            electedAgendas[civId] = premier.agenda
        }
        chronicle.record(
            ChronicleEvent(
                day, ChronicleEventKind.ELECTION, civId,
                detail = if (category == null) {
                    "${civ.name} sets its charter aside"
                } else {
                    "${civ.name} is chartered for ${category.name.lowercase()}"
                },
            ),
        )
        return true
    }

    /**
     * The Premier acts four times a year, not every tick: they set job weights from their agenda
     * and place up to [Politics.BUILD_ORDERS_PER_DECISION] build orders.
     */
    private fun premierDecisions() {
        for (civ in civs) {
            val premier = premiers[civ.id] ?: continue
            val members = membersOf(civ.id)
            if (members.isEmpty()) continue

            // A petition the player bought lasts a year, then the Premier reverts to their platform.
            if (premier.petitionActive && day - premier.electedOnDay >= Time.DAYS_PER_YEAR) {
                electedAgendas[civ.id]?.let { premier.clearPetition(it) }
            }

            repeat(Politics.BUILD_ORDERS_PER_DECISION) {
                placeBuildOrder(civ, premier, members)
            }
        }
    }

    /** Chooses what to build and where, and lays the foundation. Construction happens over time. */
    private fun placeBuildOrder(civ: Civilization, premier: Premier, members: List<Citizen>) {
        val need = townNeeds(civ, members)
        val category = CouncilSystem.chooseCategory(
            premier,
            need,
            rng,
            traitLean = CouncilSystem.traitLeanOf(civ.traits),
            distress = distressOf(civ, members),
        )
        val options = GameConfig.Buildings.available(category, civ.techTier)
        if (options.isEmpty()) return

        // Best available tier the civ can actually pay for.
        val spec = options.firstOrNull {
            civ[Resource.WOOD] >= it.woodCost && civ[Resource.STONE] >= it.stoneCost
        } ?: return

        if (vetoPending[civ.id]) {
            vetoPending[civ.id] = false
            chronicle.record(
                ChronicleEvent(day, ChronicleEventKind.BUILDING_COMPLETED, civ.id, detail = "VETOED ${spec.type.name}"),
            )
            return
        }

        val site = BuildingSystem.findSite(world, civ, spec.footprint, buildingsOf(civ.id)) ?: return
        civ.take(Resource.WOOD, spec.woodCost)
        civ.take(Resource.STONE, spec.stoneCost)

        val building = Building(
            id = nextBuildingId++,
            type = spec.type,
            civId = civ.id,
            x = site % world.width,
            y = site / world.width,
        )
        allBuildings.add(building)
        buildingsByCiv[civ.id].add(building)
        BuildingSystem.place(world, building)
    }

    /** How badly the town wants each category right now — the average of its citizens' needs. */
    /**
     * How much trouble a civ is in, 0 (thriving) to 1 (desperate).
     *
     * Two things, because they are the two ways a town dies: it cannot feed itself, or its people
     * are failing. Both are already computed every tick for other purposes, so this costs a walk of
     * the civ's own members and nothing more.
     *
     * Used to squeeze a people's trait lean out of their building decisions as things get worse —
     * every civilisation, the player's included, tries to stay alive before it tries to be itself.
     */
    private fun distressOf(civ: Civilization, members: List<Citizen>): Double {
        if (members.isEmpty()) return 1.0
        val daysOfFood = civ[Resource.FOOD] / (members.size * Economy.FOOD_PER_ADULT_PER_DAY)
        val hunger = (1.0 - daysOfFood / Economy.FOOD_COMFORTABLE_DAYS_OF_STOCK).coerceIn(0.0, 1.0)
        val survival = members.sumOf { it.survival.toDouble() } / members.size
        val failing = (1.0 - survival / SurvivalConfig.MAX).coerceIn(0.0, 1.0)
        return max(hunger, failing)
    }

    private fun townNeeds(civ: Civilization, members: List<Citizen>): Map<BuildingCategory, Double> {
        if (members.isEmpty()) return BuildingCategory.entries.associateWith { 0.0 }
        val totals = DoubleArray(BuildingCategory.entries.size)
        for (citizen in members) {
            CouncilSystem.accumulateNeeds(citizen, effects[civ.id], civ, members.size, totals)
        }
        return BuildingCategory.entries.associateWith { totals[it.ordinal] / members.size }
    }

    // ------------------------------------------------------------------ buildings

    private fun advanceConstruction() {
        for (civ in civs) {
            val civBuildings = buildingsOf(civ.id)
            if (civBuildings.none { !it.isComplete }) continue
            val builders = membersOf(civ.id).filter { it.job == Job.BUILDER }
            val completed = BuildingSystem.advanceConstruction(
                builders = builders,
                buildings = civBuildings,
                effects = effects[civ.id],
                traits = civ.traits,
                techMultiplier = techMultiplier(civ),
            )
            for (building in completed) {
                premiers[civ.id]?.built?.add(building.type)
                chronicle.record(
                    ChronicleEvent(
                        day, ChronicleEventKind.BUILDING_COMPLETED, civ.id,
                        detail = building.type.name, value = building.id,
                    ),
                )
            }
            if (completed.isNotEmpty()) refreshEffects(civ.id)
        }
    }

    /**
     * Upkeep is charged daily in wealth. A civ that cannot pay accrues unrest, and after a grace
     * period loses a building to ruin — which is what stops a town over-building into collapse.
     */
    private fun payUpkeep() {
        for (civ in civs) {
            val upkeep = effects[civ.id].upkeepWealth
            if (upkeep <= 0.0) {
                civ.unpaidUpkeepDays = 0
                continue
            }
            val paid = civ.take(Resource.WEALTH, upkeep)
            if (paid >= upkeep - 1e-9) {
                civ.unpaidUpkeepDays = 0
                continue
            }
            civ.unpaidUpkeepDays++
            if (civ.unpaidUpkeepDays >= GameConfig.Buildings.UPKEEP_GRACE_DAYS) {
                civ.unpaidUpkeepDays = 0
                ruinOneBuilding(civ)
            }
        }
    }

    /** The newest completed building falls down first: a town abandons its extravagances. */
    private fun ruinOneBuilding(civ: Civilization) {
        val victim = buildingsOf(civ.id).lastOrNull { it.isComplete } ?: return
        BuildingSystem.remove(world, victim)
        allBuildings.remove(victim)
        buildingsByCiv[civ.id].remove(victim)
        for (citizen in living) {
            if (citizen.homeBuildingId == victim.id) citizen.homeBuildingId = null
        }
        refreshEffects(civ.id)
        chronicle.record(
            ChronicleEvent(day, ChronicleEventKind.BUILDING_COMPLETED, civ.id, detail = "RUINED ${victim.type.name}"),
        )
    }

    /** Homeless citizens move into housing with space. Shelter is a big share of survival. */
    private fun assignHousing() {
        if (day % HOUSING_REVIEW_INTERVAL_DAYS != 0L) return
        for (civ in civs) {
            val homes = buildingsOf(civ.id).filter { it.isComplete && it.spec.housingCapacity > 0 }
            if (homes.isEmpty()) continue
            for (home in homes) home.residents = 0

            var index = 0
            for (citizen in living) {
                if (citizen.civId != civ.id) continue
                citizen.homeBuildingId = null
                while (index < homes.size && homes[index].residents >= homes[index].spec.housingCapacity) {
                    index++
                }
                if (index >= homes.size) break
                homes[index].residents++
                citizen.homeBuildingId = homes[index].id
            }
        }
    }

    internal fun refreshEffects(civId: Int) {
        effects[civId] = BuildingSystem.aggregate(buildingsOf(civId))
        effects[civId] = effects[civId].withTech(civs[civId].techChoices)
        civs[civId].foodStorageCapacity = foodCapacityOf(civId)
    }

    /**
     * How much food this civ can hold before the surplus rots.
     *
     * Derived rather than stored, because it depends on the population, which changes every tick —
     * the cached field was the reason a colony's founding stores sat 1,470 above a flat capacity of
     * 400 and visibly rotted for the player's first hundred days. Granaries add to it and Elements
     * multiplies it, exactly as before.
     */
    fun foodCapacityOf(civId: Int): Double {
        val civ = civs[civId]
        // Elements keeps a harvest through the winter rather than watching it spoil, so it scales
        // capacity the same way a granary adds to it.
        val weathered = 1.0 + TraitConfig.ELEMENTS_FOOD_STORAGE_BONUS * civ.traits[Trait.ELEMENTS]
        val forPopulation = Economy.FOOD_STORAGE_DAYS_PER_CITIZEN * civ.population *
            Economy.FOOD_PER_ADULT_PER_DAY
        val base = max(forPopulation, Economy.MIN_FOOD_STORAGE_CAPACITY)
        return (base + effects[civId].foodStorageBonus) * weathered
    }

    // ------------------------------------------------------------------ tech and unrest

    /** Knowledge buys tiers. Costs scale steeply, so tier 6 is the work of centuries. */
    private fun advanceTech() {
        for (civ in civs) {
            if (civ.techTier >= GameConfig.Tech.MAX_TIER) continue
            if (civ.pendingTechTier != null) continue // already waiting on a decision
            val cost = GameConfig.Tech.COST_BASE * GameConfig.Tech.COST_GROWTH.pow(civ.techTier + 1)
            if (civ[Resource.KNOWLEDGE] < cost) continue
            civ.take(Resource.KNOWLEDGE, cost)
            civ.techTier++
            chronicle.record(
                ChronicleEvent(day, ChronicleEventKind.TECH_TIER, civ.id, value = civ.techTier),
            )

            if (TechOption.forTier(civ.techTier).isEmpty()) continue
            if (civ.isPlayer && !unattended) {
                // The clock stops until they choose: see `step`.
                civ.pendingTechTier = civ.techTier
            } else {
                TechOption.choiceFor(civ.personality, civ.techTier)?.let { applyTech(civ, it) }
            }
        }
    }

    /**
     * The choices waiting on [civId], or empty when nothing is pending. While this is non-empty for
     * the player the simulation does not advance.
     */
    fun pendingTechChoices(civId: Int): List<TechOption> =
        civ(civId).pendingTechTier?.let { TechOption.forTier(it) } ?: emptyList()

    /**
     * True while the clock is stopped waiting on a decision only the player can make: a tech to
     * choose at a new tier, or a decade's trait point to spend.
     *
     * The trait point belongs here for the same reason the tech does, and it is the answer to why
     * rival civilisations appeared to develop faster than the player's. A rival spends its point the
     * day it earns it; the player's banked and waited, so for as much as a year of game time —
     * thirty-six seconds at 10x — four rivals were a point ahead of a player who had done nothing
     * wrong. Stopping the clock makes the two symmetric: nobody advances until everyone has spent.
     */
    val awaitingPlayer: Boolean
        get() {
            val player = civ(WorldConfig.PLAYER_CIV_ID)
            if (player.pendingTechTier != null) return true
            if (unattended) return false
            if (electionPending && campaigns[WorldConfig.PLAYER_CIV_ID]?.isNotEmpty() == true) return true
            return player.unspentTraitPoints > 0 && player.traits.improvable.isNotEmpty()
        }

    /** Growth points the player has earned and not yet spent. Zero when nothing is waiting. */
    fun pendingTraitPoints(civId: Int): Int = civ(civId).unspentTraitPoints

    /**
     * Takes [option] for [civId]. False if it was not on offer — a stale tap from a UI, or a save
     * from a build that offered something else.
     */
    fun chooseTech(civId: Int, option: TechOption): Boolean {
        val civ = civ(civId)
        val tier = civ.pendingTechTier ?: return false
        if (option.tier != tier) return false
        civ.pendingTechTier = null
        applyTech(civ, option)
        return true
    }

    private fun applyTech(civ: Civilization, option: TechOption) {
        civ.techChoices.add(option)
        refreshEffects(civ.id)
        chronicle.record(
            ChronicleEvent(
                day, ChronicleEventKind.TECH_TIER, civ.id,
                detail = "${civ.name} learns ${option.label}",
                value = option.tier,
            ),
        )
    }

    /**
     * Unrest, emigration and coups. A Premier who ignores what the town feels loses it: work
     * output falls, people leave, and past [Politics.UNREST_COUP_THRESHOLD] the highest-influence
     * citizen takes the office mid-term.
     */
    private fun updateUnrest() {
        // Weekly: this walks every citizen's felt needs, and doing it daily made it the single
        // most expensive thing in the tick. Unrest moves on the scale of seasons anyway, so the
        // per-step change is scaled up to keep the same pace.
        if (day % UNREST_REVIEW_INTERVAL_DAYS != 0L) return
        for (civ in civs) {
            val members = membersOf(civ.id)
            if (members.isEmpty()) continue
            val premier = premiers[civ.id]

            val needGap = if (premier == null) 0.0 else {
                val need = townNeeds(civ, members)
                val wanted = Agenda.of(need)
                premier.agenda.distanceTo(wanted)
            }
            val meanSurvival = members.sumOf { it.survival.toDouble() } / members.size
            repeat(UNREST_REVIEW_INTERVAL_DAYS.toInt()) {
                CouncilSystem.updateUnrest(civ, needGap, meanSurvival)
            }

            if (civ.unrest > Politics.UNREST_EMIGRATION_THRESHOLD) {
                for (citizen in members) {
                    if (citizen.isAdult && rng.chance(Politics.UNREST_EMIGRATION_DAILY_CHANCE * civ.unrest)) {
                        kill(citizen, DeathCause.EMIGRATION)
                    }
                }
                compactDead()
                refreshPopulationIndex()
            }

            if (civ.unrest > Politics.UNREST_COUP_THRESHOLD && rng.chance(Politics.UNREST_COUP_DAILY_CHANCE)) {
                stageCoup(civ, members)
            }
        }
    }

    private fun stageCoup(civ: Civilization, members: List<Citizen>) {
        val usurper = members
            .filter { it.ageYears >= Politics.VOTING_AGE_YEARS }
            .maxWithOrNull(compareBy<Citizen> { it.influence }.thenByDescending { -it.id }) ?: return

        civ.termCount++
        installPremier(
            civ,
            Premier(
                citizenId = usurper.id,
                name = usurper.fullName,
                // A usurper governs to the town's actual grievance — that is why they had support.
                agenda = Agenda.of(townNeeds(civ, members)),
                temperament = Temperament.AMBITIOUS,
                electedOnDay = day,
                termNumber = civ.termCount,
                byCoup = true,
            ),
        )
        civ.unrest *= 0.4
        chronicle.record(
            ChronicleEvent(day, ChronicleEventKind.COUP, civ.id, usurper.id, detail = premiers[civ.id]?.name),
        )
    }

    // ------------------------------------------------------------------ rivals

    /**
     * Diplomacy is resolved at season boundaries, not daily: four decision points a year, the same
     * cadence the Premier works to.
     *
     * Every civ runs this. The player's civ is not special — it can be extorted, traded with, and
     * invaded on exactly the same terms, and it makes its own demands the same way.
     */
    private fun runDiplomacy() {
        if (day % Time.DAYS_PER_SEASON != 0L || day == 0L) return

        relations.decay(RivalConfig.TENSION_DECAY_PER_SEASON)
        applyBorderFriction()

        for (actor in civs) {
            if (actor.isExtinct) continue
            val aggression = aggressionOf(actor)
            for (other in civs) {
                if (other.id == actor.id || other.isExtinct) continue
                if (relations.atWar(actor.id, other.id)) continue

                val tension = relations.tensionBetween(actor.id, other.id)
                when {
                    tension >= RivalConfig.TENSION_WAR_THRESHOLD &&
                        aggression > RivalConfig.WAR_AGGRESSION_MIN &&
                        !relations.inTruce(actor.id, other.id, day) -> declareWar(actor, other)

                    tension >= RivalConfig.TENSION_RAID_THRESHOLD &&
                        aggression > RivalConfig.RAID_AGGRESSION_MIN -> launchRaid(actor, other)

                    aggression >= RivalConfig.TRIBUTE_AGGRESSION_THRESHOLD ->
                        demandTribute(actor, other)

                    else -> attemptTrade(actor, other)
                }
            }
        }

        endExhaustedWars()
    }

    /** Aggression is derived from the civ's own condition, never scripted. */
    fun aggressionOf(civ: Civilization): Double {
        val members = membersOf(civ.id)
        if (members.isEmpty()) return 0.0
        val militaryShare = members.count { it.job == Job.SOLDIER }.toDouble() / members.size
        val consumption = members.sumOf { it.dailyFoodNeed() }
        val foodSecurity = DiplomacySystem.foodSecurityOf(civ, consumption)
        return DiplomacySystem.aggressionOf(civ, militaryShare, foodSecurity)
    }

    /** Civs working land close to each other rub along badly. */
    private fun applyBorderFriction() {
        val contested = HashSet<Long>()
        for (cell in 0 until world.cellCount) {
            val owner = world.ownerCivId[cell].toInt()
            if (owner < 0) continue
            val x = cell % world.width
            val y = cell / world.width
            val r = RivalConfig.BORDER_FRICTION_RADIUS
            var dy = -r
            while (dy <= r) {
                var dx = -r
                while (dx <= r) {
                    val nx = x + dx
                    val ny = y + dy
                    if (world.inBounds(nx, ny)) {
                        val neighbour = world.ownerCivId[world.index(nx, ny)].toInt()
                        if (neighbour >= 0 && neighbour != owner) {
                            contested.add(pairKey(owner, neighbour))
                        }
                    }
                    dx += r
                }
                dy += r
            }
        }
        for (key in contested) {
            val a = (key shr 32).toInt()
            val b = (key and 0xFFFFFFFFL).toInt()
            relations.raise(a, b, RivalConfig.TENSION_BORDER_FRICTION)
        }
    }

    private fun pairKey(a: Int, b: Int): Long {
        val lo = minOf(a, b).toLong()
        val hi = maxOf(a, b).toLong()
        return (lo shl 32) or hi
    }

    /**
     * Surplus-for-deficit: both sides end up better off, and the tension between them eases.
     *
     * Nobody trades with a civ that has been raiding them. Without that condition, hostile pairs
     * went on trading right through the raids and the resulting goodwill cancelled the resentment
     * out — tension could never climb to war, and 150-year runs contained none.
     */
    private fun attemptTrade(seller: Civilization, buyer: Civilization) {
        if (relations.tensionBetween(seller.id, buyer.id) >= RivalConfig.TENSION_RAID_THRESHOLD) return
        val members = membersOf(seller.id)
        if (members.isEmpty()) return
        val reserve = members.sumOf { it.dailyFoodNeed() } * RivalConfig.TRADE_SURPLUS_DAYS
        val food = DiplomacySystem.tradeableSurplus(seller, Resource.FOOD, reserve)
        if (food <= 1.0) return

        val price = food * RivalConfig.TRADE_PRICE
        if (buyer[Resource.WEALTH] < price) return

        seller.take(Resource.FOOD, food)
        buyer.add(Resource.FOOD, food)
        buyer.take(Resource.WEALTH, price)
        seller.add(Resource.WEALTH, price)

        relations.ease(seller.id, buyer.id, RivalConfig.TENSION_TRADE_RELIEF)
        relations.recordTrade(seller.id, buyer.id)
        chronicle.record(
            ChronicleEvent(
                day, ChronicleEventKind.TRADE, seller.id, detail = buyer.name, value = food.toInt(),
            ),
        )
    }

    /**
     * The player offers a neighbour a trade, out of season.
     *
     * Trade already happened on its own schedule between every pair that was not hostile, which made
     * it something the player watched rather than used. This is the same [attemptTrade] the rivals
     * run — deliberately, so a player-driven trade cannot be a better deal than a rival's — and it
     * can fail for all the same reasons: no surplus to sell, no coin to pay, or a neighbour angry
     * enough that nobody is trading with anybody. Influence is only spent when it actually happens,
     * because paying for a refusal would make the button a gamble rather than a lever.
     */
    fun offerTrade(civId: Int, otherCivId: Int): Boolean {
        if (civId == otherCivId) return false
        val us = civ(civId)
        val them = civ(otherCivId)
        if (us.isExtinct || them.isExtinct) return false
        if (us.influencePoints < Politics.COST_OFFER_TRADE) return false

        val tradesBefore = relations.tradeCount(civId, otherCivId)
        // Try both directions: whoever has the surplus sells it.
        attemptTrade(us, them)
        if (relations.tradeCount(civId, otherCivId) == tradesBefore) attemptTrade(them, us)
        if (relations.tradeCount(civId, otherCivId) == tradesBefore) return false

        return spendInfluence(civId, Politics.COST_OFFER_TRADE)
    }

    /**
     * The player forces a war their town did not ask for.
     *
     * The most consequential button in the game, and the one thing here that overrides the
     * simulation's own judgement rather than nudging it: aggression, posture and the truce all
     * decide wars on their own (AD-35), and this declares one regardless. It is priced accordingly
     * and refuses in exactly one case — a war already under way — because declaring a war twice is
     * meaningless rather than expensive.
     */
    fun forceWar(civId: Int, otherCivId: Int): Boolean {
        if (civId == otherCivId) return false
        val us = civ(civId)
        val them = civ(otherCivId)
        if (us.isExtinct || them.isExtinct) return false
        if (relations.atWar(civId, otherCivId)) return false
        if (!spendInfluence(civId, Politics.COST_FORCE_WAR)) return false

        relations.raise(civId, otherCivId, RivalConfig.TENSION_MAX)
        declareWar(us, them)
        return true
    }

    /** Pay up or take the tension. A civ submits when it is clearly the weaker party. */
    private fun demandTribute(demander: Civilization, target: Civilization) {
        if (strength[demander.id] <= 0.0) return
        val submits = DiplomacySystem.shouldSubmit(strength[target.id], strength[demander.id])

        if (submits) {
            val food = target[Resource.FOOD] * RivalConfig.TRIBUTE_FOOD_FRACTION
            val wealth = target[Resource.WEALTH] * RivalConfig.TRIBUTE_WEALTH_FRACTION
            demander.add(Resource.FOOD, target.take(Resource.FOOD, food))
            demander.add(Resource.WEALTH, target.take(Resource.WEALTH, wealth))
            relations.raise(demander.id, target.id, RivalConfig.TENSION_BORDER_FRICTION)
            chronicle.record(
                ChronicleEvent(
                    day, ChronicleEventKind.TRADE, demander.id,
                    detail = "TRIBUTE from ${target.name}", value = food.toInt(),
                ),
            )
        } else {
            relations.raise(demander.id, target.id, RivalConfig.TENSION_TRIBUTE_REFUSED)
        }
    }

    private fun declareWar(aggressor: Civilization, defender: Civilization) {
        relations.declareWar(aggressor.id, defender.id, day)
        aggressor.warsFought++
        defender.warsFought++
        chronicle.record(
            ChronicleEvent(day, ChronicleEventKind.WAR_DECLARED, aggressor.id, detail = defender.name),
        )
        muster(aggressor, defender, Army.Kind.WAR, RivalConfig.WAR_PARTY_SHARE)
    }

    private fun launchRaid(raider: Civilization, target: Civilization) {
        if (armies.any { it.civId == raider.id && it.targetCivId == target.id }) return
        if (muster(raider, target, Army.Kind.RAID, RivalConfig.RAID_PARTY_SHARE)) {
            relations.raise(raider.id, target.id, RivalConfig.TENSION_RAID_LAUNCHED)
            target.raidsSuffered++
            chronicle.record(
                ChronicleEvent(day, ChronicleEventKind.RAID, raider.id, detail = target.name),
            )
        }
    }

    /** Forms a force from a civ's standing soldiers and sends it at the enemy's home. */
    private fun muster(civ: Civilization, target: Civilization, kind: Army.Kind, share: Double): Boolean {
        val available = soldiersOf(civ.id).filter { soldier -> armies.none { soldier.id in it.members } }
        val size = (available.size * share).toInt()
        if (size < RivalConfig.MIN_PARTY_SIZE) return false

        val army = Army(
            id = nextArmyId++,
            civId = civ.id,
            targetCivId = target.id,
            targetCell = target.homeSite,
            kind = kind,
            startedOnDay = day,
        )
        for (soldier in available.take(size)) {
            army.members.add(soldier.id)
            soldier.enlisted = true
            soldier.workCell = target.homeSite
        }
        army.startingSize = army.members.size
        armies.add(army)
        return true
    }

    /** Wars end when both sides are tired of them. */
    private fun endExhaustedWars() {
        for (a in civs.indices) {
            for (b in a + 1 until civs.size) {
                if (!relations.atWar(a, b)) continue
                if (relations.tensionBetween(a, b) < RivalConfig.PEACE_TENSION ||
                    civs[a].isExtinct || civs[b].isExtinct
                ) {
                    relations.makePeace(a, b, day)
                            for (army in armies) {
                        val involved = (army.civId == a && army.targetCivId == b) ||
                            (army.civId == b && army.targetCivId == a)
                        if (involved) army.returning = true
                    }
                    chronicle.record(
                        ChronicleEvent(day, ChronicleEventKind.PEACE, a, detail = civs[b].name),
                    )
                }
            }
        }
    }

    /**
     * Armies walk. They are made of real citizens moving across the map cell by cell, so a war is
     * visible on screen as columns of pixels converging — the most dramatic thing the game draws.
     */
    private fun marchArmies() {
        if (armies.isEmpty()) return

        for (army in armies) {
            // Casualties and old age thin an army out; drop anyone who is no longer with us.
            army.members.retainAll { byId[it]?.alive == true }
            if (army.kind == Army.Kind.RAID && day - army.startedOnDay > RivalConfig.RAID_MAX_DAYS) {
                army.returning = true
            }
            if (army.isBroken()) army.returning = true

            // An army cannot walk through a wall, so while one stands it marches on the wall
            // instead of the town — which is also what makes a siege visible on the map.
            val wall = if (army.returning) null else standingWallAgainst(army)
            army.besieging = wall?.id
            if (wall == null) army.siegeDays = 0

            val destination = when {
                army.returning -> civ(army.civId).homeSite
                wall != null -> world.index(wall.x, wall.y)
                else -> army.targetCell
            }
            for (id in army.members) {
                val soldier = byId[id] ?: continue
                soldier.workCell = destination
            }
        }

        // Wars wear both sides down until someone sues for peace.
        for (a in civs.indices) {
            for (b in a + 1 until civs.size) {
                if (relations.atWar(a, b)) relations.ease(a, b, RivalConfig.WAR_WEARINESS_PER_DAY)
            }
        }

        // Disbanding returns soldiers to the workforce.
        val disbanded = armies.filter { it.members.isEmpty() || (it.returning && it.atHome()) }
        for (army in disbanded) {
            for (id in army.members) byId[id]?.let { it.enlisted = false; it.workCell = World.NONE }
        }
        armies.removeAll(disbanded)
    }

    /**
     * The defender's wall this army has to bring down first, or null when the way is open.
     *
     * "First" is the wall nearest the army, so a town with two walls is reduced one at a time and a
     * force that has already broken through does not turn round to attack the far side. Only
     * completed walls count: a half-built structure is inert everywhere else in the game, and a
     * half-built wall that stopped an army would be the cheapest defence on the map.
     */
    private fun standingWallAgainst(army: Army): Building? {
        val walls = buildingsOf(army.targetCivId).filter {
            it.type == BuildingType.WALL && it.isComplete && !it.isRubble
        }
        if (walls.isEmpty()) return null
        val leader = army.members.firstNotNullOfOrNull { byId[it] } ?: return walls.first()
        return walls.minBy { maxOf(abs(it.x - leader.x), abs(it.y - leader.y)) }
    }

    /**
     * A day of siege: the army works on the wall and takes fire while it does.
     *
     * Deliberately not a battle against the town's soldiers. The point of the mechanic the walls
     * now have is that an army in front of a wall is *not* in the town: it cannot kill farmers, it
     * cannot loot the granary, and every day it spends here is a day the defenders spend farming.
     */
    private fun besiege(army: Army, wall: Building) {
        val attackers = army.members.mapNotNull { byId[it] }
        if (attackers.isEmpty()) return
        val attackerCiv = civ(army.civId)
        val defenderCiv = civ(army.targetCivId)

        val attackStrength = DiplomacySystem.strengthOf(
            attackers, attackerCiv.traits, techMultiplier(attackerCiv), CivEffects.NONE,
        )
        army.siegeDays++

        // The defenders shoot back, at a fraction of what a storming assault would cost.
        val losses = (
            attackers.size * RivalConfig.COMBAT_DAILY_ATTRITION * RivalConfig.SIEGE_ATTACKER_ATTRITION_SCALE
            ).toInt()
        if (losses > 0) {
            for (casualty in attackers.take(losses)) kill(casualty, DeathCause.COMBAT)
            compactDead()
            refreshPopulationIndex()
        }

        if (wall.damage(attackStrength * RivalConfig.SIEGE_DAMAGE_PER_STRENGTH)) {
            BuildingSystem.remove(world, wall)
            allBuildings.remove(wall)
            buildingsByCiv[defenderCiv.id].remove(wall)
            refreshEffects(defenderCiv.id)
            army.besieging = null
            army.siegeDays = 0
            chronicle.record(
                ChronicleEvent(
                    day, ChronicleEventKind.RAID, army.civId,
                    detail = "breached ${defenderCiv.name}'s wall",
                ),
            )
        } else if (army.kind == Army.Kind.RAID && army.siegeDays >= RivalConfig.SIEGE_MAX_DAYS) {
            // A raiding party is not a siege engine. This is what walls are actually for.
            army.returning = true
            chronicle.record(
                ChronicleEvent(
                    day, ChronicleEventKind.RAID, army.civId,
                    detail = "turned back by ${defenderCiv.name}'s walls",
                ),
            )
        }
    }

    private fun Army.atHome(): Boolean {
        val home = civ(civId).homeSite
        val hx = home % world.width
        val hy = home / world.width
        return members.all { id ->
            val soldier = byId[id] ?: return@all true
            maxOf(abs(soldier.x - hx), abs(soldier.y - hy)) <= RivalConfig.ENGAGEMENT_RANGE
        }
    }

    /**
     * Battle: attrition over days with a random component, never a single dice roll. Walls count
     * for the defender, which is what makes them worth their stone.
     */
    private fun fightBattles() {
        if (armies.isEmpty()) return

        for (army in armies) {
            if (army.returning || army.members.isEmpty()) continue
            val defenderCiv = civ(army.targetCivId)
            if (defenderCiv.isExtinct) {
                army.returning = true
                continue
            }

            val attackers = army.members.mapNotNull { byId[it] }
            if (attackers.isEmpty()) continue

            // A wall in the way is fought before the town is. `marchArmies` has already pointed
            // the column at it; this is the day's work once they arrive.
            val wall = army.besieging?.let { id -> buildingsOf(defenderCiv.id).firstOrNull { it.id == id } }
            if (wall != null) {
                val inRange = attackers.any {
                    maxOf(abs(it.x - wall.x), abs(it.y - wall.y)) <= RivalConfig.ENGAGEMENT_RANGE
                }
                if (inRange) besiege(army, wall)
                continue
            }

            // Defenders are whoever is standing near the fighting.
            val defenders = membersOf(defenderCiv.id).filter { defender ->
                attackers.any { maxOf(abs(it.x - defender.x), abs(it.y - defender.y)) <= RivalConfig.ENGAGEMENT_RANGE }
            }
            if (defenders.isEmpty()) continue

            val defendingSoldiers = defenders.filter { it.job == Job.SOLDIER }
            val wallBonus = if (effects[defenderCiv.id].safetyBonus > 0.15) RivalConfig.WALL_DEFENCE_BONUS else 1.0

            val attackStrength = DiplomacySystem.strengthOf(
                attackers, civ(army.civId).traits, techMultiplier(civ(army.civId)), CivEffects.NONE,
            )
            val defenceStrength = DiplomacySystem.strengthOf(
                defendingSoldiers, defenderCiv.traits, techMultiplier(defenderCiv), effects[defenderCiv.id],
            ) * wallBonus

            val (attackerLosses, defenderLosses) = DiplomacySystem.resolveBattleDay(
                attackStrength, defenceStrength, attackers.size, defenders.size, rng,
            )

            for (casualty in attackers.take(attackerLosses)) kill(casualty, DeathCause.COMBAT)
            for (casualty in defenders.take(defenderLosses)) kill(casualty, DeathCause.COMBAT)
            if (attackerLosses + defenderLosses > 0) {
                compactDead()
                refreshPopulationIndex()
            }

            // A raid takes what it came for and leaves.
            if (army.kind == Army.Kind.RAID && defenderLosses > 0) {
                val stolen = defenderCiv.take(
                    Resource.FOOD,
                    defenderCiv[Resource.FOOD] * RivalConfig.RAID_FOOD_STOLEN_FRACTION,
                )
                civ(army.civId).add(Resource.FOOD, stolen)
                relations.raise(army.civId, defenderCiv.id, RivalConfig.TENSION_RAID_SUCCEEDED)
                army.returning = true
                chronicle.record(
                    ChronicleEvent(
                        day, ChronicleEventKind.RAID, army.civId,
                        detail = "stole from ${defenderCiv.name}", value = stolen.toInt(),
                    ),
                )
            }
        }
        armies.removeAll { it.members.isEmpty() }
    }

    /**
     * How many soldiers of each kind a civ fields, derived from what it has built.
     *
     * A soldier is one kind only — the best their town can equip them with — so the counts sum to
     * the soldier count and the breakdown is a partition rather than a tally of overlapping
     * qualities. The shares are fixed rather than rolled per citizen, so the same buildings always
     * produce the same army and nothing here touches the RNG stream.
     */
    fun unitBreakdown(civId: Int): Map<UnitKind, Int> {
        val soldiers = membersOf(civId).count { it.job == Job.SOLDIER }
        if (soldiers == 0) return emptyMap()

        val built = buildingsOf(civId).filter { it.isComplete }.map { it.type }.toSet()
        val hasBarracks = BuildingType.BARRACKS in built
        val hasTower = BuildingType.WATCHTOWER in built
        val hasArmoury = BuildingType.ARMOURY in built

        // Best equipment first, and each tier takes a share of what is left.
        val result = LinkedHashMap<UnitKind, Int>()
        var left = soldiers
        if (hasArmoury) {
            val n = (soldiers * RivalConfig.SHARE_MEN_AT_ARMS).toInt().coerceAtMost(left)
            if (n > 0) { result[UnitKind.MEN_AT_ARMS] = n; left -= n }
        }
        if (hasTower) {
            val n = (soldiers * RivalConfig.SHARE_ARCHERS).toInt().coerceAtMost(left)
            if (n > 0) { result[UnitKind.ARCHERS] = n; left -= n }
        }
        if (hasBarracks && left > 0) {
            result[UnitKind.INFANTRY] = left
            left = 0
        }
        if (left > 0) result[UnitKind.MILITIA] = left
        return result
    }

    /** How a civ's people are employed, grouped for the HUD. */
    fun jobBreakdown(civId: Int): Map<JobGroup, Int> {
        val counts = LinkedHashMap<JobGroup, Int>()
        for (group in JobGroup.entries) counts[group] = 0
        for (citizen in membersOf(civId)) {
            val group = JobGroup.of(citizen.job)
            counts[group] = (counts[group] ?: 0) + 1
        }
        return counts
    }

    /**
     * Every pair's standing, for the relations screen: tension, posture, trade and war.
     *
     * The whole matrix rather than only the player's row, because the interesting thing about a
     * five-civ map is that the others have opinions about each other — a player watching two rivals
     * go to war is watching an opportunity.
     */
    fun relationReports(): List<RelationReport> {
        val out = ArrayList<RelationReport>()
        for (a in civs.indices) {
            for (b in a + 1 until civs.size) {
                out.add(
                    RelationReport(
                        civA = a,
                        civB = b,
                        nameA = civs[a].name,
                        nameB = civs[b].name,
                        tension = relations.tensionBetween(a, b),
                        atWar = relations.atWar(a, b),
                        trades = relations.tradeCount(a, b),
                        inTruce = relations.inTruce(a, b, day),
                        bothAlive = !civs[a].isExtinct && !civs[b].isExtinct,
                    ),
                )
            }
        }
        return out
    }

    /** What the player knows about each rival, for the Rivals screen. */
    fun rivalReports(): List<RivalReport> = civs.filter { !it.isPlayer }.map { rival ->
        RivalReport(
            civId = rival.id,
            name = rival.name,
            personality = rival.personality,
            population = rival.population,
            militaryStrength = strength[rival.id],
            techTier = rival.techTier,
            tension = relations.tensionBetween(GameConfig.World.PLAYER_CIV_ID, rival.id),
            atWar = relations.atWar(GameConfig.World.PLAYER_CIV_ID, rival.id),
            trades = relations.tradeCount(GameConfig.World.PLAYER_CIV_ID, rival.id),
            aggression = aggressionOf(rival),
        )
    }

    // ------------------------------------------------------------------ the player's levers

    /**
     * Endorse a candidate during the campaign, adding
     * [Politics.ENDORSE_VOTE_WEIGHT_BONUS] to their standing with every voter.
     */
    fun endorse(civId: Int, candidateCitizenId: Int): Boolean {
        val candidates = campaigns[civId] ?: return false
        if (candidates.none { it.citizenId == candidateCitizenId }) return false
        if (!spendInfluence(civId, Politics.COST_ENDORSE)) return false
        endorsements[civId] = candidateCitizenId
        return true
    }

    /** Petition the sitting Premier to shift one agenda weight. Reverts after a year. */
    fun petition(civId: Int, category: BuildingCategory, delta: Double): Boolean {
        val premier = premiers[civId] ?: return false
        if (!spendInfluence(civId, Politics.COST_PETITION)) return false
        premier.applyPetition(category, delta)
        return true
    }

    /** Veto the Premier's next build order. One per year. */
    fun veto(civId: Int): Boolean {
        val civ = civ(civId)
        if (civ.vetoesUsedThisYear >= Politics.VETOES_PER_YEAR) return false
        if (!spendInfluence(civId, Politics.COST_VETO)) return false
        civ.vetoesUsedThisYear++
        vetoPending[civId] = true
        return true
    }

    /** Call an early election. Expensive, and the town may return the same Premier. */
    fun callReferendum(civId: Int): Boolean {
        if (!spendInfluence(civId, Politics.COST_REFERENDUM)) return false
        val members = membersOf(civId)
        if (members.isEmpty()) return false
        campaigns[civId] = CouncilSystem.chooseCandidates(members, rng)
        endorsements[civId] = null
        return true
    }

    private fun spendInfluence(civId: Int, cost: Int): Boolean {
        val civ = civ(civId)
        if (civ.influencePoints < cost) return false
        civ.influencePoints -= cost
        return true
    }

    /**
     * Jobs are reassigned weekly, not daily: reassignment costs skill, and a workforce that
     * reshuffles every tick would never learn its trades.
     */
    private fun assignJobsIfDue() {
        if (day % Economy.JOB_REASSIGN_INTERVAL_DAYS != 0L) return
        for (civ in civs) {
            val members = membersOf(civ.id)
            if (members.isEmpty()) continue
            EconomySystem.assignJobs(world, civ, members, workClaims, jobWeightsFor(civ))
            claimTerritory(civ, members)
        }
    }

    /**
     * The job weights a civ works to: the sitting Premier's agenda, translated into a workforce.
     * Before the first election a civ works to the default farm-heavy split.
     *
     * The farm/hunt split inside the food share stays fixed — see AD-25 in CLAUDE.md, where
     * deriving it from trait ratios was measured and made every allocation worse.
     */
    private fun jobWeightsFor(civ: Civilization): Map<Job, Double> {
        val premier = premiers[civ.id] ?: return Economy.DEFAULT_JOB_WEIGHTS
        val building = buildingsOf(civ.id).any { !it.isComplete }
        return CouncilSystem.jobWeightsFor(premier.agenda, building, civ.traits)
    }

    /** Worked land belongs to the civ that works it, which is what the map shows as territory. */
    private fun claimTerritory(civ: Civilization, members: List<Citizen>) {
        for (citizen in members) {
            val cell = citizen.workCell
            if (cell != World.NONE) world.ownerCivId[cell] = civ.id.toByte()
        }
    }

    private fun produceGoods() {
        val severity = normalisedSeasonSeverity()
        for (civ in civs) {
            val members = membersOf(civ.id)
            if (members.isEmpty()) continue
            EconomySystem.produce(
                world, civ, members, severity, effects[civ.id], config.skillGrowthMultiplier,
            )
        }
    }

    /** This civ's citizens, from the per-tick index. Read-only: callers must not mutate it. */
    private fun membersOf(civId: Int): List<Citizen> = membersByCiv[civId]

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

            // The real ration: Health lowers it and Elements removes the winter surcharge, which
            // is what puts those two traits on the food balance sheet at all. Job assignment reads
            // the same figure, or the feedback loop would be aiming at a demand that is not real.
            val severity = normalisedSeasonSeverity()
            val techDiscount = civ.techChoices.sumOf { it.effects.rationDiscount }.coerceIn(0.0, 0.5)
            val demand = members.sumOf { it.dailyFoodNeed(civ.traits, severity) } * (1.0 - techDiscount)
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

            // Spoilage: anything above storage capacity rots. Computed live rather than read from
            // the cached field, so a town that grew or shrank today is judged against what it can
            // actually hold today.
            val capacity = foodCapacityOf(civ.id)
            civ.foodStorageCapacity = capacity
            val stored = civ[Resource.FOOD]
            if (stored > capacity) {
                val excess = stored - capacity
                // Elements is shelter, and a granary shelters grain: a weathered people lose less
                // of what they cannot fit inside. This is the other door onto the sweep's 56%
                // spoilage figure — the one AD-78 left open after the workforce door proved to be
                // a growth cap.
                val rotted = excess * Economy.SPOILAGE_PER_DAY_OVER_CAPACITY *
                    civ.traits.spoilageMultiplier
                civ[Resource.FOOD] = stored - rotted
                // Booked here because spoilage writes the store directly rather than going through
                // `take`, and because it is not consumption: nobody got the good of it.
                civ.spoiled += rotted
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
            SurvivalConfig.W_SAFETY * safetyOf(citizen.civId) +
            SurvivalConfig.W_CARE * careAccessOf(citizen.civId) +
            SurvivalConfig.W_MORALE * citizen.morale

        score -= severity * (1.0 - traits.elementsShelter)
        score -= ageFrailty(citizen, traits)
        return score.coerceIn(SurvivalConfig.MIN, SurvivalConfig.MAX).toFloat()
    }

    private fun shelterQuality(citizen: Citizen): Double =
        if (citizen.homeBuildingId == null) {
            SurvivalConfig.SHELTER_QUALITY_HOMELESS
        } else {
            GameConfig.Buildings.SHELTER_QUALITY_HOUSED
        }

    /**
     * Walls and watchtowers above the baseline, less the shadow of the strongest rival with a
     * reason to attack. A town that has never built a wall, next to a militant neighbour, feels
     * it in the survival score every day.
     */
    private fun safetyOf(civId: Int): Double = DiplomacySystem.safetyFor(
        baseline = SurvivalConfig.SAFETY_BASELINE,
        wallsAndTowers = effects[civId].safetyBonus,
        ownStrength = strength[civId],
        worstThreatStrength = worstThreatTo(civId),
        atWar = relations.anyWar(civId),
    )

    /** The strongest rival that currently has cause to come for this civ. */
    private fun worstThreatTo(civId: Int): Double {
        var worst = 0.0
        for (other in civs) {
            if (other.id == civId || other.isExtinct) continue
            val hostile = relations.atWar(civId, other.id) ||
                relations.tensionBetween(civId, other.id) >= RivalConfig.TENSION_RAID_THRESHOLD
            if (hostile && strength[other.id] > worst) worst = strength[other.id]
        }
        return worst
    }

    /** Clinic and healer capacity per head. */
    private fun careAccessOf(civId: Int): Double {
        val population = civs[civId].population
        if (population <= 0) return SurvivalConfig.CARE_BASELINE
        val capacity = effects[civId].careCapacity + healersByCiv[civId] * Economy.HEALER_CARE_CAPACITY
        return (capacity / population).coerceIn(0.0, 1.0)
    }

    /** Zero until [SurvivalConfig.AGE_FRAILTY_ONSET_FRACTION] of lifespan, then rising to the cap. */
    private fun ageFrailty(citizen: Citizen, traits: TraitAllocation): Double {
        val onset = traits.lifespanDays * SurvivalConfig.AGE_FRAILTY_ONSET_FRACTION
        if (citizen.ageDays <= onset) return 0.0
        val span = traits.lifespanDays - onset
        val progress = ((citizen.ageDays - onset) / span).coerceIn(0.0, 1.0)
        return SurvivalConfig.AGE_FRAILTY_MAX * progress
    }

    /** Raw seasonal penalty in survival points, subtracted from the survival score. */
    private fun seasonSeverity(): Double = SurvivalConfig.SEASON_SEVERITY[season.ordinal]

    /** The same severity scaled to 0..1, which is what the yield multiplier expects. */
    private fun normalisedSeasonSeverity(): Double {
        val worst = SurvivalConfig.SEASON_SEVERITY.max()
        return if (worst <= 0.0) 0.0 else seasonSeverity() / worst
    }

    private fun applyDisease() {
        maybeStartEpidemics()

        for (citizen in living) {
            val traits = traitsOf(citizen)
            val resist = (traits.diseaseResist + effects[citizen.civId].diseaseResistBonus)
                .coerceIn(0.0, 0.95)

            // The background rate: a citizen falls ill now and then and usually recovers.
            if (rng.chance(Life.DISEASE_EVENT_BASE_CHANCE * (1.0 - resist))) {
                citizen.hp = max(0f, citizen.hp - Life.DISEASE_HP_DAMAGE.toFloat())
            }

            // An epidemic is a different thing: a large share of the town falls ill at once, and
            // whether they come through it is what a Health trait decides.
            if (civ(citizen.civId).epidemicDaysLeft > 0) {
                val care = careAccessOf(citizen.civId) * Life.EPIDEMIC_CARE_MITIGATION
                val chance = Life.EPIDEMIC_DAILY_INFECTION_CHANCE *
                    (1.0 - resist - care).coerceAtLeast(0.0)
                if (rng.chance(chance)) {
                    citizen.hp = max(0f, citizen.hp - Life.EPIDEMIC_HP_DAMAGE.toFloat())
                }
            }
        }
    }

    /**
     * Crowd disease, which is density-dependent: the more people live together, the more often an
     * outbreak arrives. That makes it the one pressure in the game that scales with success.
     */
    private fun maybeStartEpidemics() {
        for (civ in civs) {
            if (civ.epidemicDaysLeft > 0) {
                civ.epidemicDaysLeft--
                continue
            }
            if (civ.population < Life.EPIDEMIC_MIN_POPULATION) continue
            val crowding = civ.population / Life.EPIDEMIC_POPULATION_REFERENCE
            if (!rng.chance(Life.EPIDEMIC_DAILY_CHANCE_AT_REFERENCE * crowding)) continue

            civ.epidemicDaysLeft = Life.EPIDEMIC_DAYS
            civ.epidemicCount++
            chronicle.record(
                ChronicleEvent(
                    day, ChronicleEventKind.DISASTER, civ.id,
                    detail = "a sickness spreads through ${civ.name}",
                    value = civ.population,
                ),
            )
        }
    }

    /**
     * Winter kills the unsheltered. Elements is what a people has instead of a roof, and a roof is
     * what they have instead of Elements — either answers the same threat, which is what ties the
     * trait to the building layer rather than leaving it a private stat.
     */
    private fun exposureDeathChance(citizen: Citizen, traits: TraitAllocation): Double {
        val severity = normalisedSeasonSeverity()
        if (severity <= 0.0) return 0.0
        val shelter = traits.elementsShelter.coerceIn(0.0, 0.95)
        val roof = if (citizen.homeBuildingId != null) Life.EXPOSURE_HOUSED_MULTIPLIER else 1.0
        return Life.EXPOSURE_DAILY_DEATH_CHANCE * severity * (1.0 - shelter) * roof
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
        if (rng.chance(exposureDeathChance(citizen, traits))) return DeathCause.EXPOSURE
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
        citizen.enlisted = false
        world.occupantId[world.index(citizen.x, citizen.y)] = World.NONE
        EconomySystem.releaseClaim(citizen, workClaims)

        citizen.partnerId?.let { partnerId ->
            byId[partnerId]?.let { partner ->
                partner.partnerId = null
                partner.widowedOnDay = dayInt
            }
        }

        val civ = civ(citizen.civId)
        civ.totalDeaths++
        civ.deathsByCause[cause.ordinal]++
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

    /**
     * Two things beyond condition and housing decide how often a town has children: how healthy a
     * people are (Health, as a metabolic trait) and whether the town is somewhere worth raising
     * them (its Lifestyle buildings). Both are multipliers on the documented base rate, so a
     * Health-3 town with no plazas conceives at exactly the rate AD-24 measured.
     */
    private fun conceptionChance(citizen: Citizen): Double =
        Life.CONCEIVE_BASE * (citizen.survival / SurvivalConfig.MAX) * housingSlack(citizen.civId) *
            civ(citizen.civId).traits.fertilityMultiplier *
            (1.0 + effects[citizen.civId].fertilityBonus)

    /**
     * The share of housing capacity still free. A town with nowhere to put a child has fewer of
     * them; before any housing is built this sits at a floor rather than zero, or a colony could
     * never grow far enough to build its first house.
     */
    private fun housingSlack(civId: Int): Double {
        val capacity = effects[civId].housingCapacity
        if (capacity <= 0) return Life.HOUSING_SLACK_WITHOUT_HOUSING
        val free = (capacity - housedByCiv[civId]).toDouble() / capacity
        return free.coerceIn(Life.HOUSING_SLACK_WITHOUT_HOUSING, 1.0)
    }

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
        // Heredity: the child starts from its parents' average and gets a fresh draw on top, so a
        // people's constitution drifts across generations instead of being resampled every birth.
        val father = mother.partnerId?.let { citizenOrNull(it) }
        // The lineage: a child carries its father's family name where there is one, its mother's
        // otherwise, so surnames descend through the run instead of being redrawn every birth.
        child.familyId = father?.familyId ?: mother.familyId
        val inherited = if (father != null) (mother.vigour + father.vigour) / 2.0 else mother.vigour.toDouble()
        val mutation = rng.nextDouble(-TraitConfig.VIGOUR_MUTATION, TraitConfig.VIGOUR_MUTATION)
        // Pulled part of the way back toward the average, so a lineage improves without running
        // away: regression to the mean, which is what keeps one lucky founder from producing a
        // town of supermen three centuries later.
        val drawn = inherited * TraitConfig.VIGOUR_INHERITANCE +
            1.0 * (1.0 - TraitConfig.VIGOUR_INHERITANCE) + mutation
        child.vigour = drawn.coerceIn(TraitConfig.VIGOUR_MIN, TraitConfig.VIGOUR_MAX).toFloat()

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
            val steps = stepsToday(citizen)
            repeat(steps) {
                val target = citizen.workCell
                if (target != World.NONE) {
                    stepToward(citizen, target % world.width, target / world.width)
                } else if (rng.chance(Life.WANDER_CHANCE_PER_DAY)) {
                    wander(citizen)
                }
            }
        }
    }

    /** Whole cells moved today; the fractional part of move speed is a daily coin flip. */
    private fun stepsToday(citizen: Citizen): Int {
        val speed = traitsOf(citizen).moveSpeed
        val whole = speed.toInt()
        return whole + if (rng.chance(speed - whole)) 1 else 0
    }

    /**
     * One greedy step toward a target, with a sidestep when the direct route is blocked. No A*:
     * the map is open enough, and thousands of agents make a proper path search too expensive.
     */
    private fun stepToward(citizen: Citizen, targetX: Int, targetY: Int) {
        if (citizen.x == targetX && citizen.y == targetY) return
        val dx = (targetX - citizen.x).sign
        val dy = (targetY - citizen.y).sign
        if (tryMove(citizen, citizen.x + dx, citizen.y + dy)) return
        // Blocked diagonally: try the two axis-aligned steps, then give up for today.
        if (dx != 0 && tryMove(citizen, citizen.x + dx, citizen.y)) return
        if (dy != 0) tryMove(citizen, citizen.x, citizen.y + dy)
    }

    private fun wander(citizen: Citizen) {
        val direction = rng.nextInt(World.NEIGHBOUR_DX.size)
        tryMove(citizen, citizen.x + World.NEIGHBOUR_DX[direction], citizen.y + World.NEIGHBOUR_DY[direction])
    }

    /** Moves the citizen if the destination is walkable and free. One occupant per cell, always. */
    private fun tryMove(citizen: Citizen, nx: Int, ny: Int): Boolean {
        if (!world.inBounds(nx, ny)) return false
        val to = world.index(nx, ny)
        if (!world.isWalkable(to) || world.isOccupied(to)) return false
        world.occupantId[world.index(citizen.x, citizen.y)] = World.NONE
        world.occupantId[to] = citizen.id
        citizen.x = nx
        citizen.y = ny
        return true
    }

    private fun driftMoraleAndInfluence() {
        // The player's influence points accrue from plazas, temples and the town's mood.
        for (civ in civs) {
            val members = membersOf(civ.id)
            if (members.isEmpty()) continue
            val meanMorale = members.sumOf { it.morale.toDouble() } / members.size
            civ.influencePoints += Politics.INFLUENCE_POINTS_PER_DAY_BASE +
                effects[civ.id].influencePerDay +
                Politics.INFLUENCE_POINTS_MORALE_SCALE * meanMorale * Politics.INFLUENCE_POINTS_PER_DAY_BASE
        }

        for (citizen in living) {
            val target = Life.MORALE_BASELINE + effects[citizen.civId].moraleBonus
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

    internal fun updateCivStatistics() {
        val counts = IntArray(civs.size)
        for (citizen in living) counts[citizen.civId]++
        for (civ in civs) {
            civ.population = counts[civ.id]
            strength[civ.id] = DiplomacySystem.strengthOf(
                soldiersOf(civ.id), civ.traits, techMultiplier(civ), effects[civ.id],
            )
        }
    }

    private fun soldiersOf(civId: Int): List<Citizen> =
        membersOf(civId).filter { it.job == Job.SOLDIER }

    /** A civ's military strength, as the Rivals screen reports it. */
    fun strengthOf(civId: Int): Double = strength[civId]

    /**
     * The four ways a run ends. Checked in order of finality: being wiped out settles the question
     * whatever else was true that day.
     */
    private fun checkEndState() {
        if (endState != null) return
        val player = civs.firstOrNull { it.isPlayer } ?: return

        val ending = when {
            // Conquest is a collapse with a culprit: the player is gone and a rival holds the
            // ground they were founded on.
            player.isExtinct && homeSiteTakenFrom(player) -> EndState.CONQUEST
            player.isExtinct -> EndState.COLLAPSE
            player.techTier >= Meta.ASCENSION_TECH_TIER && player.population >= Meta.ASCENSION_POPULATION ->
                EndState.ASCENSION
            year >= Meta.ENDURANCE_YEARS -> EndState.ENDURANCE
            else -> return
        }

        endState = ending
        chronicle.record(ChronicleEvent(day, ChronicleEventKind.RUN_ENDED, player.id, detail = ending.name))
    }

    /** True if another civ now owns the cell the player was founded on. */
    private fun homeSiteTakenFrom(player: Civilization): Boolean {
        val owner = world.ownerCivId[player.homeSite].toInt()
        return owner >= 0 && owner != player.id
    }

    /**
     * How the run scored and what the player decided. Null while it is still running.
     *
     * Everything past the score is a *record* of choices the simulation was already keeping, which
     * is why building it costs a few list walks at the end of a run and nothing per tick. The
     * opening allocation is recovered from the final sheet minus the growth history rather than
     * stored separately: the two must agree, and deriving one from the other means they cannot
     * disagree.
     */
    fun summary(): RunSummary? {
        val ending = endState ?: return null
        val player = civs.first { it.isPlayer }

        val growth = HashMap<Trait, Int>()
        for (trait in Trait.entries) growth[trait] = 0
        for (trait in player.traitGrowthHistory) growth[trait] = (growth[trait] ?: 0) + 1

        val opening = IntArray(TraitConfig.COUNT) { player.traits.values[it] }
        for (trait in player.traitGrowthHistory) opening[trait.ordinal]--

        val byCategory = HashMap<BuildingCategory, Int>()
        for (category in BuildingCategory.entries) byCategory[category] = 0
        for (building in buildingsOf(player.id)) {
            if (building.isComplete) {
                byCategory[building.spec.category] = (byCategory[building.spec.category] ?: 0) + 1
            }
        }

        val byPlatform = HashMap<BuildingCategory, Int>()
        for (category in BuildingCategory.entries) byPlatform[category] = 0
        for (election in elections) {
            if (election.civId != player.id) continue
            val dominant = election.agenda.dominant
            byPlatform[dominant] = (byPlatform[dominant] ?: 0) + 1
        }

        val deaths = HashMap<DeathCause, Int>()
        for (cause in DeathCause.entries) {
            val count = player.deathsByCause[cause.ordinal]
            if (count > 0) deaths[cause] = count
        }

        val rivals = civs.filter { !it.isPlayer }
        return RunSummary(
            endState = ending,
            yearsSurvived = year,
            peakPopulation = player.peakPopulation,
            techTier = player.techTier,
            chroniclePointsEarned = Legacy.scoreRun(player.peakPopulation, year, player.techTier, ending),
            openingTraits = TraitAllocation.of(*opening),
            finalTraits = player.traits,
            traitGrowth = growth.filterValues { it > 0 },
            traitPointsAutoSpent = player.autoSpentTraitPoints,
            traitPointsUnspent = player.unspentTraitPoints,
            techsChosen = player.techChoices.map { it.label },
            buildingsByCategory = byCategory.filterValues { it > 0 },
            finalCharter = player.charter,
            premiersByPlatform = byPlatform.filterValues { it > 0 },
            termsServed = player.termCount,
            coups = chronicle.totalOf(ChronicleEventKind.COUP),
            deathsByCause = deaths,
            totalBirths = player.totalBirths,
            totalDeaths = player.totalDeaths,
            warsFought = player.warsFought,
            raidsSuffered = player.raidsSuffered,
            rivalsSurviving = rivals.count { !it.isExtinct },
            rivalsExtinct = rivals.count { it.isExtinct },
            rivals = rivals.map { rival ->
                RivalOutcome(
                    name = rival.name,
                    personality = rival.personality,
                    traits = rival.traits,
                    population = rival.population,
                    peakPopulation = rival.peakPopulation,
                    techTier = rival.techTier,
                    buildings = buildingsOf(rival.id).count { it.isComplete },
                    extinct = rival.isExtinct,
                    atWarWithPlayer = relations.atWar(player.id, rival.id),
                    warsWithPlayer = rival.warsFought,
                    tradesWithPlayer = relations.tradeCount(player.id, rival.id),
                )
            },
            produced = Resource.entries.associateWith { player.produced[it.ordinal] }
                .filterValues { it > 0.0 },
            consumed = Resource.entries.associateWith { player.consumed[it.ordinal] }
                .filterValues { it > 0.0 },
            spoiled = player.spoiled,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun traitsOf(citizen: Citizen): TraitAllocation = civ(citizen.civId).traits

    /** Global output multiplier from a civ's unlocked tech tiers. */
    private fun techMultiplier(civ: Civilization): Double =
        1.0 + GameConfig.Tech.MULTIPLIER_PER_TIER * civ.techTier

    private fun chebyshev(a: Citizen, b: Citizen): Int = max(abs(a.x - b.x), abs(a.y - b.y))

    // ------------------------------------------------------------------ saving and loading

    /**
     * Captures the whole run. Terrain is deliberately not captured — it regenerates exactly from
     * the seed, and [SaveGame.terrainHash] guards against a future change to generation silently
     * moving a player's town to a different island.
     */
    fun snapshot(savedAtEpochMillis: Long = 0L): SaveGame = SaveGame(
        seed = config.seed,
        terrainHash = world.terrain.contentHashCode(),
        tick = clock.tick,
        speedMultiplier = clock.speedMultiplier,
        rng = rng.snapshot().let { RngState(it.s0, it.s1, it.s2, it.s3) },
        config = RunConfigSave(
            traits = config.traits.values.toList(),
            colonyName = config.colony,
            startCell = config.startCell,
            colorIndex = config.colorIndex,
            settlers = config.settlers,
            civCount = config.civCount,
            skillGrowthMultiplier = config.skillGrowthMultiplier,
            startingInfluence = config.startingInfluence,
            fertilityFloor = config.fertilityFloor,
            startingTensionRelief = config.startingTensionRelief,
            startingBuildings = config.startingBuildings,
            offlineCapHours = config.offlineCapHours,
        ),
        endState = endState,
        nextCitizenId = nextCitizenId,
        nextBuildingId = nextBuildingId,
        nextArmyId = nextArmyId,
        world = WorldSave(
            width = world.width,
            height = world.height,
            fertility = world.fertility.copyOf(),
            wildGame = world.wildGame.copyOf(),
            ownerCivId = world.ownerCivId.copyOf(),
        ),
        civs = civs.map { civ ->
            CivSave(
                id = civ.id,
                name = civ.name,
                traits = civ.traits.values.toList(),
                personality = civ.personality,
                homeSite = civ.homeSite,
                stores = civ.stores.copyOf(),
                foodStorageCapacity = civ.foodStorageCapacity,
                techTier = civ.techTier,
                unrest = civ.unrest,
                unspentTraitPoints = civ.unspentTraitPoints,
                generationsAwarded = civ.generationsAwarded,
                oldestUnspentPointDay = civ.oldestUnspentPointDay,
                traitGrowthHistory = civ.traitGrowthHistory.map { it.name },
                autoSpentTraitPoints = civ.autoSpentTraitPoints,
                deathsByCause = civ.deathsByCause.toList(),
                warsFought = civ.warsFought,
                raidsSuffered = civ.raidsSuffered,
                produced = civ.produced.toList(),
                consumed = civ.consumed.toList(),
                spoiled = civ.spoiled,
                epidemicDaysLeft = civ.epidemicDaysLeft,
                epidemicCount = civ.epidemicCount,
                charter = civ.charter,
                techChoices = civ.techChoices.map { it.name },
                pendingTechTier = civ.pendingTechTier,
                influencePoints = civ.influencePoints,
                unpaidUpkeepDays = civ.unpaidUpkeepDays,
                vetoesUsedThisYear = civ.vetoesUsedThisYear,
                termCount = civ.termCount,
                peakPopulation = civ.peakPopulation,
                totalBirths = civ.totalBirths,
                totalDeaths = civ.totalDeaths,
            )
        },
        citizens = living.map { c ->
            CitizenSave(
                id = c.id, x = c.x, y = c.y, civId = c.civId, sex = c.sex, ageDays = c.ageDays,
                hp = c.hp, nutrition = c.nutrition, morale = c.morale, survival = c.survival,
                job = c.job, skill = c.skill, vigour = c.vigour,
                nameCode = c.nameCode, familyId = c.familyId, influence = c.influence,
                partnerId = c.partnerId,
                pregnantUntilDay = c.pregnantUntilDay, homeBuildingId = c.homeBuildingId,
                workCell = c.workCell, enlisted = c.enlisted, starvingDays = c.starvingDays,
                widowedOnDay = c.widowedOnDay, politicalBias = c.politicalBias,
                politicalBiasStrength = c.politicalBiasStrength,
            )
        },
        buildings = allBuildings.map { b ->
            BuildingSave(
                b.id, b.type, b.civId, b.x, b.y, b.buildProgress, b.isComplete, b.residents,
                integrity = b.integrity,
            )
        },
        premiers = civs.map { civ ->
            premiers[civ.id]?.let { premier ->
                PremierSave(
                    citizenId = premier.citizenId,
                    name = premier.name,
                    agenda = premier.agenda.toList(),
                    electedAgenda = (electedAgendas[civ.id] ?: premier.agenda).toList(),
                    temperament = premier.temperament,
                    electedOnDay = premier.electedOnDay,
                    termNumber = premier.termNumber,
                    byCoup = premier.byCoup,
                    built = premier.built.toList(),
                    petitionActive = premier.petitionActive,
                )
            }
        },
        campaigns = civs.map { civ ->
            campaigns[civ.id]?.map { c ->
                CandidateSave(c.citizenId, c.name, c.ageYears, c.job, c.agenda.toList(), c.temperament, c.votes)
            }
        },
        endorsements = civs.map { endorsements[it.id] },
        electionPending = electionPending,
        vetoPending = civs.map { vetoPending[it.id] },
        elections = elections.map { e ->
            ElectionSave(
                day = e.day, termNumber = e.termNumber, civId = e.civId, winnerName = e.winnerName,
                winnerCitizenId = e.winnerCitizenId, agenda = e.agenda.toList(),
                temperament = e.temperament, voteNames = e.voteCounts.map { it.first },
                voteCounts = e.voteCounts.map { it.second }, turnout = e.turnout,
            )
        },
        relations = relations.snapshot(),
        armies = armies.map { a ->
            ArmySave(a.id, a.civId, a.targetCivId, a.targetCell, a.kind, a.startedOnDay, a.members.toList(), a.startingSize, a.returning)
        },
        chronicle = chronicle.snapshot(),
        legacy = LegacySave(
            chroniclePoints = legacy.chroniclePoints,
            runsPlayed = legacy.runsPlayed,
            bestYears = legacy.bestYears,
            upgrades = legacy.snapshotLevels(),
        ),
        savedAtEpochMillis = savedAtEpochMillis,
    )

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
            /**
             * How many civilisations share the map. The full game is always
             * [WorldConfig.TOTAL_CIV_COUNT]; a solo run isolates the economy from the rivals,
             * which is what the balance tests and the M7 harness need in order to attribute a
             * collapse to the right cause.
             */
            civCount: Int = WorldConfig.TOTAL_CIV_COUNT,
        ): Simulation = newRun(
            RunConfig(seed = seed, traits = playerTraits, settlers = settlers, civCount = civCount),
        )

        /**
         * Starts a run from a complete configuration — the seed, the player's allocation, and
         * whatever their legacy has earned them. This is the only moment any of it is read.
         */
        fun newRun(config: RunConfig): Simulation {
            require(config.civCount in 1..WorldConfig.TOTAL_CIV_COUNT) {
                "unsupported civ count ${config.civCount}"
            }
            val rng = SimRandom(config.seed)
            val generated = WorldGenerator.generate(rng, playerSite = config.startCell)

            // A fertility floor from the Legacy is applied to the land itself, before anyone
            // works it, so it holds for the whole run rather than being re-applied per tick.
            if (config.fertilityFloor > 0f) {
                val world = generated.world
                for (i in 0 until world.cellCount) {
                    // Farmable ground is decided by terrain, not by the cell's current value:
                    // generation jitters some beach and marsh cells all the way down to zero, and
                    // a floor that skipped those would leave gaps in exactly the poor land it is
                    // meant to protect.
                    val farmable = GameConfig.Terrain.FERTILITY.getValue(world.terrainAt(i)) > 0.0
                    if (farmable && world.fertility[i] < config.fertilityFloor) {
                        world.fertility[i] = config.fertilityFloor
                    }
                }
            }

            // Rivals pick their own personalities first, then allocate their own points to suit
            // them (see RivalStrategist). The player's allocation is the player's alone.
            val rivalPersonalities = (1 until config.civCount).map {
                Personality.entries[rng.nextInt(Personality.entries.size)]
            }
            val rivalTraits = RivalStrategist.allocateAll(rivalPersonalities, rng)

            // The player names their own colony; the rivals take the first pool names it leaves
            // free, so nobody in the Chronicle shares a name with anybody else.
            val playerName = config.colony
            val rivalNames = ColonyName.rivalNames(playerName, config.civCount - 1)

            val civs = ArrayList<Civilization>(config.civCount)
            for (id in 0 until config.civCount) {
                val isPlayer = id == WorldConfig.PLAYER_CIV_ID
                civs.add(
                    Civilization(
                        id = id,
                        name = if (isPlayer) playerName else rivalNames[id - 1],
                        traits = if (isPlayer) config.traits else rivalTraits[id - 1],
                        personality = if (isPlayer) Personality.ISOLATIONIST else rivalPersonalities[id - 1],
                        homeSite = generated.civStartSites[id],
                    ),
                )
            }
            val simulation = Simulation(generated.world, civs, rng)
            simulation.config = config
            for (civ in civs) simulation.found(civ, config.settlers)

            // Rivals start better disposed toward a player whose legacy has earned it.
            if (config.startingTensionRelief > 0.0) {
                for (rival in civs) {
                    if (rival.isPlayer) continue
                    simulation.relations.ease(WorldConfig.PLAYER_CIV_ID, rival.id, config.startingTensionRelief)
                }
            }
            return simulation
        }

        /**
         * Rebuilds a run from a save.
         *
         * Terrain is regenerated from the seed rather than stored, and the hash is checked before
         * anything else: a change to world generation must fail loudly here rather than drop a
         * player's town onto a different island. Everything mutable is then written over the
         * generated world, and the derived grids — which cell holds which building, who is
         * standing where — are rebuilt from the buildings and citizens themselves rather than
         * saved, since storing them would only create a second source of truth.
         */
        fun restore(save: SaveGame): Simulation {
            val generated = WorldGenerator.generate(SimRandom(save.seed), playerSite = save.config.startCell)
            val world = generated.world
            if (world.terrain.contentHashCode() != save.terrainHash) {
                throw IncompatibleSaveException(
                    "world generation has changed since this save was written; the island would not be the same one",
                )
            }

            save.world.fertility.copyInto(world.fertility)
            save.world.wildGame.copyInto(world.wildGame)
            // Ownership is restored *after* buildings are placed, further down: placing a
            // building stamps its civ onto the cells it covers, which is right when it is built
            // but wrong on load — a cell a rival later worked would revert to whoever built on it.

            val civs = save.civs.map { c ->
                Civilization(
                    id = c.id,
                    name = c.name,
                    traits = TraitAllocation.of(*c.traits.toIntArray()),
                    personality = c.personality,
                    homeSite = c.homeSite,
                ).also { civ ->
                    c.stores.copyInto(civ.stores)
                    civ.foodStorageCapacity = c.foodStorageCapacity
                    civ.techTier = c.techTier
                    civ.unrest = c.unrest
                    civ.unspentTraitPoints = c.unspentTraitPoints
                    civ.generationsAwarded = c.generationsAwarded
                    civ.oldestUnspentPointDay = c.oldestUnspentPointDay
                    // A name this build does not know is dropped rather than throwing, exactly as
                    // an unknown tech is: the record is presentation, and losing one entry of it is
                    // never worth refusing to load a player's town.
                    for (name in c.traitGrowthHistory) {
                        Trait.entries.firstOrNull { it.name == name }?.let { civ.traitGrowthHistory.add(it) }
                    }
                    civ.autoSpentTraitPoints = c.autoSpentTraitPoints
                    civ.warsFought = c.warsFought
                    civ.raidsSuffered = c.raidsSuffered
                    // Sized from the save, so a resource added since the file was written cannot
                    // run off the end of the array.
                    for ((i, v) in c.produced.withIndex()) if (i < civ.produced.size) civ.produced[i] = v
                    for ((i, v) in c.consumed.withIndex()) if (i < civ.consumed.size) civ.consumed[i] = v
                    civ.spoiled = c.spoiled
                    // Sized from the save rather than copied wholesale: a cause added since the
                    // file was written must not run off the end of the array.
                    for ((i, count) in c.deathsByCause.withIndex()) {
                        if (i < civ.deathsByCause.size) civ.deathsByCause[i] = count
                    }
                    civ.epidemicDaysLeft = c.epidemicDaysLeft
                    civ.epidemicCount = c.epidemicCount
                    // Unknown names are dropped rather than throwing: a save from a build that
                    // offered a tech this one does not must still load.
                    for (name in c.techChoices) {
                        TechOption.entries.firstOrNull { it.name == name }?.let { civ.techChoices.add(it) }
                    }
                    civ.pendingTechTier = c.pendingTechTier
                    civ.charter = c.charter
                    civ.influencePoints = c.influencePoints
                    civ.unpaidUpkeepDays = c.unpaidUpkeepDays
                    civ.vetoesUsedThisYear = c.vetoesUsedThisYear
                    civ.termCount = c.termCount
                    civ.population = c.peakPopulation // seeds peakPopulation...
                    civ.population = 0 // ...then the real count is set below
                    civ.totalBirths = c.totalBirths
                    civ.totalDeaths = c.totalDeaths
                }
            }

            val rng = SimRandom.restore(
                SimRandom.State(save.rng.s0, save.rng.s1, save.rng.s2, save.rng.s3),
            )
            val clock = SimClock.restore(SimClock.State(save.tick, save.speedMultiplier))
            val simulation = Simulation(world, civs, rng, clock)

            simulation.config = RunConfig(
                seed = save.seed,
                traits = TraitAllocation.of(*save.config.traits.toIntArray()),
                colonyName = save.config.colonyName,
                startCell = save.config.startCell,
                colorIndex = save.config.colorIndex,
                settlers = save.config.settlers,
                civCount = save.config.civCount,
                skillGrowthMultiplier = save.config.skillGrowthMultiplier,
                startingInfluence = save.config.startingInfluence,
                fertilityFloor = save.config.fertilityFloor,
                startingTensionRelief = save.config.startingTensionRelief,
                startingBuildings = save.config.startingBuildings,
                offlineCapHours = save.config.offlineCapHours,
            )
            simulation.legacy = Legacy.restore(
                save.legacy.chroniclePoints, save.legacy.runsPlayed, save.legacy.bestYears, save.legacy.upgrades,
            )
            simulation.endState = save.endState
            simulation.nextCitizenId = save.nextCitizenId
            simulation.nextBuildingId = save.nextBuildingId
            simulation.nextArmyId = save.nextArmyId

            for (c in save.citizens) {
                val citizen = Citizen(
                    id = c.id, x = c.x, y = c.y, civId = c.civId, sex = c.sex, ageDays = c.ageDays,
                    hp = c.hp, nutrition = c.nutrition, morale = c.morale, survival = c.survival,
                    job = c.job, skill = c.skill, influence = c.influence, partnerId = c.partnerId,
                    pregnantUntilDay = c.pregnantUntilDay, homeBuildingId = c.homeBuildingId,
                ).apply {
                    vigour = c.vigour
                    nameCode = c.nameCode
                    familyId = c.familyId
                    workCell = c.workCell
                    enlisted = c.enlisted
                    starvingDays = c.starvingDays
                    widowedOnDay = c.widowedOnDay
                    politicalBias = c.politicalBias
                    politicalBiasStrength = c.politicalBiasStrength
                }
                simulation.living.add(citizen)
                simulation.byId[citizen.id] = citizen
                world.occupantId[world.index(citizen.x, citizen.y)] = citizen.id
                if (citizen.workCell != World.NONE && citizen.job in FIELD_CLAIM_JOBS) {
                    simulation.workClaims[citizen.workCell] = citizen.id
                }
            }

            for (b in save.buildings) {
                val building = Building(b.id, b.type, b.civId, b.x, b.y)
                building.restoreProgress(
                    b.buildProgress,
                    b.complete,
                    if (b.integrity < 0.0) building.spec.buildPointsRequired else b.integrity,
                )
                building.residents = b.residents
                simulation.allBuildings.add(building)
                simulation.buildingsByCiv[b.civId].add(building)
                BuildingSystem.place(world, building)
            }

            // Now that the building grid is populated, the saved ownership is the truth.
            save.world.ownerCivId.copyInto(world.ownerCivId)

            for ((civId, p) in save.premiers.withIndex()) {
                if (p == null) continue
                val premier = Premier(
                    citizenId = p.citizenId,
                    name = p.name,
                    agenda = Agenda.fromList(p.agenda),
                    temperament = p.temperament,
                    electedOnDay = p.electedOnDay,
                    termNumber = p.termNumber,
                    byCoup = p.byCoup,
                )
                premier.restoreState(p.built, p.petitionActive)
                simulation.premiers[civId] = premier
                simulation.electedAgendas[civId] = Agenda.fromList(p.electedAgenda)
            }

            for (e in save.elections) {
                simulation.elections.add(
                    ElectionResult(
                        day = e.day, termNumber = e.termNumber, civId = e.civId,
                        winnerName = e.winnerName, winnerCitizenId = e.winnerCitizenId,
                        agenda = Agenda.fromList(e.agenda), temperament = e.temperament,
                        voteCounts = e.voteNames.zip(e.voteCounts), turnout = e.turnout,
                    ),
                )
            }

            for ((civId, candidates) in save.campaigns.withIndex()) {
                if (candidates == null) continue
                simulation.campaigns[civId] = candidates.map { c ->
                    Candidate(
                        citizenId = c.citizenId,
                        name = c.name,
                        ageYears = c.ageYears,
                        job = c.job,
                        agenda = Agenda.fromList(c.agenda),
                        temperament = c.temperament,
                    ).also { it.votes = c.votes }
                }
            }
            simulation.electionPending = save.electionPending
            for ((civId, endorsed) in save.endorsements.withIndex()) {
                simulation.endorsements[civId] = endorsed
            }
            for ((civId, pending) in save.vetoPending.withIndex()) {
                simulation.vetoPending[civId] = pending
            }

            simulation.relationsRestoredFrom(save.relations)

            for (a in save.armies) {
                val army = Army(a.id, a.civId, a.targetCivId, a.targetCell, a.kind, a.startedOnDay)
                army.members.addAll(a.members)
                army.startingSize = a.startingSize
                army.returning = a.returning
                simulation.armies.add(army)
            }

            simulation.chronicle.restoreFrom(save.chronicle)

            // Derived state that is never saved, only rebuilt.
            simulation.refreshPopulationIndex()
            for (civ in civs) simulation.refreshEffects(civ.id)
            simulation.updateCivStatistics()
            for ((index, c) in save.civs.withIndex()) {
                // Restore the historical peak, which the live count would otherwise have clobbered.
                civs[index].restorePeakPopulation(c.peakPopulation)
            }
            return simulation
        }

        /** Housing is reassigned this often; doing it daily is wasted work. */
        private const val HOUSING_REVIEW_INTERVAL_DAYS = 30L

        /** Unrest is judged this often, for the same reason. */
        private const val UNREST_REVIEW_INTERVAL_DAYS = 7L

        /** Jobs whose work cell is an exclusive claim, and so must be re-registered on load. */
        private val FIELD_CLAIM_JOBS = setOf(Job.FARMER, Job.HUNTER, Job.GATHERER)

    }
}
