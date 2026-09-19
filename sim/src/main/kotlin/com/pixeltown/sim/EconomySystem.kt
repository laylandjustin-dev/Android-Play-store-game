package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Economy
import com.pixeltown.sim.GameConfig.Life
import com.pixeltown.sim.GameConfig.Terrain as TerrainConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Jobs, work cells, and production.
 *
 * Work is spatial: a farmer does not produce food from thin air, they walk to a fertile cell and
 * work *that* cell, draining its fertility. This is what makes over-farming a real failure mode
 * and what pushes a growing town outward across the map.
 *
 * Kept out of [Simulation] so the tick reads as a list of systems rather than a thousand-line
 * class. Every function here is a pure function of the state passed in plus the shared [SimRandom].
 */
internal object EconomySystem {

    // ------------------------------------------------------------------ job assignment

    /**
     * Reassigns a civ's workforce, once a week.
     *
     * Weights come from the Premier's agenda (M4); until then from [Economy.DEFAULT_JOB_WEIGHTS].
     * Hunger overrides politics: below [Economy.FOOD_CRISIS_DAYS_OF_STOCK] days of stock the
     * weights are discarded and the workforce is pushed onto food regardless of what the Premier
     * wants.
     *
     * Reassignment is not free — [Life.SKILL_REASSIGNMENT_RETENTION] of a worker's skill is lost
     * when their job changes, so a Premier who lurches between priorities wrecks the economy.
     */
    fun assignJobs(
        world: World,
        civ: Civilization,
        members: List<Citizen>,
        claims: HashMap<Int, Int>,
        weights: Map<Job, Double>,
    ) {
        // Soldiers in the field keep their job and their marching orders.
        val workers = members.filter { it.isAdult && !it.enlisted }
        if (workers.isEmpty()) return

        val dailyConsumption = members.sumOf { it.dailyFoodNeed(civ.traits) }
        val quotas = quotasFor(workers.size, foodWeighted(weights, civ.daysOfFood(dailyConsumption)))

        // Keep people in the job they already hold wherever the quota allows it: skill is
        // expensive to rebuild, so churn is only worth it where the quota actually demands it.
        val remaining = HashMap(quotas)
        val unassigned = ArrayList<Citizen>(workers.size)
        for (worker in workers) {
            val held = remaining[worker.job] ?: 0
            if (worker.job != Job.IDLE && held > 0) {
                remaining[worker.job] = held - 1
            } else {
                unassigned.add(worker)
            }
        }

        // Fill the gaps in a fixed job order so assignment never depends on map iteration order.
        val queue = ArrayDeque(unassigned)
        for (job in JOB_ORDER) {
            var slots = remaining[job] ?: 0
            while (slots > 0 && queue.isNotEmpty()) {
                reassign(queue.removeFirst(), job, claims)
                slots--
            }
            remaining[job] = slots
        }
        // Anyone left over has no work to do.
        while (queue.isNotEmpty()) reassign(queue.removeFirst(), Job.IDLE, claims)

        for (worker in workers) {
            if (worker.workCell == World.NONE || !stillWorkable(world, civ, worker)) {
                claimWorkCell(world, civ, worker, claims)
            }
        }
    }

    /** Converts weights into integer head counts that sum to exactly [workers]. */
    private fun quotasFor(workers: Int, weights: Map<Job, Double>): Map<Job, Int> {
        val total = weights.values.sum()
        if (total <= 0.0) return mapOf(Job.IDLE to workers)

        val quotas = LinkedHashMap<Job, Int>()
        var assigned = 0
        for (job in JOB_ORDER) {
            val share = (weights[job] ?: 0.0) / total
            val count = (workers * share).toInt()
            quotas[job] = count
            assigned += count
        }
        // Rounding leftovers go to the highest-weighted job, which is food in every sane agenda.
        val leftover = workers - assigned
        if (leftover > 0) {
            val top = JOB_ORDER.maxByOrNull { weights[it] ?: 0.0 } ?: Job.FARMER
            quotas[top] = (quotas[top] ?: 0) + leftover
        }
        return quotas
    }

    /**
     * How much of the workforce goes to food, as a continuous response to how much food there is.
     *
     * This was a threshold: below ten days of stock the town threw 85% of its people at food, and
     * above it behaved as though nothing were wrong. Both halves were bad. Ten days is already a
     * death spiral — the town is rationing and the reassignment itself costs skill — and a people
     * whose farming cannot support the standard split simply never got there in time. Raising the
     * threshold instead made emergency the permanent state, which starved the town of builders and
     * gatherers and was worse again.
     *
     * A village does not have a crisis mode. It reads its stores and decides how many people it can
     * spare, every season, and that is a feedback loop rather than a switch. Full stores free
     * people for building and scholarship; empty ones put them back in the fields. What this buys
     * is build variety: a people with poor farming now lives permanently at a high food share —
     * small, slow and short of everything else, but *alive* — where before they were simply dead.
     */
    private fun foodWeighted(weights: Map<Job, Double>, daysOfStock: Double): Map<Job, Double> {
        val comfort = Economy.FOOD_COMFORTABLE_DAYS_OF_STOCK
        val ease = (daysOfStock / comfort).coerceIn(0.0, 1.0)
        val foodShare = Economy.CRISIS_FOOD_WORKER_SHARE +
            (Economy.MIN_FOOD_WORKER_SHARE - Economy.CRISIS_FOOD_WORKER_SHARE) * ease
        val farmBias = (weights[Job.FARMER] ?: 1.0) + 0.001
        val huntBias = (weights[Job.HUNTER] ?: 1.0) + 0.001
        val foodTotal = farmBias + huntBias
        val rest = 1.0 - foodShare
        val otherTotal = weights.filterKeys { it != Job.FARMER && it != Job.HUNTER }.values.sum()
        return buildMap {
            put(Job.FARMER, foodShare * farmBias / foodTotal)
            put(Job.HUNTER, foodShare * huntBias / foodTotal)
            for ((job, weight) in weights) {
                if (job == Job.FARMER || job == Job.HUNTER) continue
                put(job, if (otherTotal > 0) rest * weight / otherTotal else 0.0)
            }
        }
    }

    private fun reassign(citizen: Citizen, job: Job, claims: HashMap<Int, Int>) {
        if (citizen.job == job) return
        citizen.job = job
        citizen.skill *= Life.SKILL_REASSIGNMENT_RETENTION.toFloat()
        releaseClaim(citizen, claims)
    }

    // ------------------------------------------------------------------ work cells

    /** True if the citizen's current work cell still suits their job. */
    private fun stillWorkable(world: World, civ: Civilization, citizen: Citizen): Boolean {
        val cell = citizen.workCell
        if (cell == World.NONE) return false
        return when (citizen.job) {
            Job.FARMER -> world.fertility[cell] > TerrainConfig.FERTILITY_ABANDON_THRESHOLD
            Job.HUNTER -> world.wildGame[cell] > TerrainConfig.FERTILITY_ABANDON_THRESHOLD
            Job.GATHERER -> gatherValue(world, cell) > 0f && !gatheringTheWrongThing(world, cell, civ)
            else -> true
        }
    }

    /**
     * Finds the best unclaimed cell for this worker's job, preferring cells close to home so a
     * town works outward from its centre rather than scattering across the island.
     */
    private fun claimWorkCell(world: World, civ: Civilization, citizen: Citizen, claims: HashMap<Int, Int>) {
        releaseClaim(citizen, claims)
        if (citizen.job !in FIELD_JOBS) {
            // Indoor work happens where the citizen stands.
            citizen.workCell = World.NONE
            return
        }

        // Search around the worker, not around the town centre.
        val originX = citizen.x
        val originY = citizen.y
        var best = World.NONE
        var bestScore = 0.0

        val radius = Economy.WORK_SEARCH_RADIUS
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val x = originX + dx
                val y = originY + dy
                if (!world.inBounds(x, y)) continue
                val cell = world.index(x, y)
                if (claims.containsKey(cell)) continue
                if (!world.isWalkable(cell)) continue

                val value = when (citizen.job) {
                    Job.FARMER -> world.fertility[cell].toDouble()
                    Job.HUNTER -> world.wildGame[cell].toDouble()
                    Job.GATHERER -> gatherValue(world, cell).toDouble() * gatherPreference(world, cell, civ)
                    else -> 0.0
                }
                if (value <= TerrainConfig.FERTILITY_ABANDON_THRESHOLD) continue

                // Distance penalty: a slightly worse cell nearby beats a better one far away.
                val distance = max(abs(dx), abs(dy))
                val score = value / (1.0 + distance * Economy.WORK_DISTANCE_PENALTY)
                if (score > bestScore) {
                    bestScore = score
                    best = cell
                }
            }
        }

        citizen.workCell = best
        if (best != World.NONE) claims[best] = citizen.id
    }

    fun releaseClaim(citizen: Citizen, claims: HashMap<Int, Int>) {
        val cell = citizen.workCell
        if (cell != World.NONE && claims[cell] == citizen.id) claims.remove(cell)
        citizen.workCell = World.NONE
    }

    /** True if [a] is the same cell as [b] or one of its eight neighbours. */
    private fun adjacentOrSame(world: World, a: Int, b: Int): Boolean {
        val dx = abs(a % world.width - b % world.width)
        val dy = abs(a / world.width - b / world.width)
        return dx <= 1 && dy <= 1
    }

    /**
     * Gatherers work toward what the town is short of.
     *
     * Without this, forest and hill score almost identically and the distance penalty decides:
     * a town ringed by hills quarried 1,250 stone and gathered 18 wood, and since almost every
     * building costs wood, the Premier could never afford to order anything at all.
     */
    private fun gatherPreference(world: World, cell: Int, civ: Civilization): Double {
        val wood = civ[Resource.WOOD]
        val stone = civ[Resource.STONE]
        return when (world.terrainAt(cell)) {
            TerrainType.FOREST -> if (wood <= stone) SCARCITY_PREFERENCE else 1.0
            TerrainType.HILL -> if (stone < wood) SCARCITY_PREFERENCE else 1.0
            else -> 1.0
        }
    }

    /** True when a gatherer is stockpiling the resource the town already has far too much of. */
    private fun gatheringTheWrongThing(world: World, cell: Int, civ: Civilization): Boolean {
        val wood = civ[Resource.WOOD]
        val stone = civ[Resource.STONE]
        return when (world.terrainAt(cell)) {
            TerrainType.FOREST -> wood > stone * GLUT_RATIO && stone > 0.0
            TerrainType.HILL -> stone > wood * GLUT_RATIO && wood > 0.0
            else -> false
        }
    }

    /** How much a scarce resource outweighs an abundant one when choosing where to gather. */
    private const val SCARCITY_PREFERENCE = 2.0

    /** A gatherer abandons a cell once its resource outstrips the other by this much. */
    private const val GLUT_RATIO = 4.0

    /** Wood from forest, stone from hill and mountain-adjacent ground. */
    private fun gatherValue(world: World, cell: Int): Float = when (world.terrainAt(cell)) {
        TerrainType.FOREST -> 1.0f
        TerrainType.HILL -> 0.9f
        TerrainType.MARSH -> 0.3f
        else -> 0f
    }

    // ------------------------------------------------------------------ production

    /**
     * One day of work for one civ. Workers only produce if they are standing on their work cell,
     * so walking to the fields costs real time.
     */
    fun produce(
        world: World,
        civ: Civilization,
        members: List<Citizen>,
        severity: Double,
        effects: CivEffects,
        skillGrowthMultiplier: Double = 1.0,
    ) {
        val traits = civ.traits
        // Irrigation puts a floor under the seasonal swing: winter still bites, but less.
        val seasonMod = max(traits.seasonalYieldMultiplier(severity), effects.seasonFloor)
        val techMultiplier = 1.0 + GameConfig.Tech.MULTIPLIER_PER_TIER * civ.techTier
        val unrestPenalty = 1.0 - GameConfig.Politics.UNREST_WORK_PENALTY_AT_MAX * civ.unrest

        var food = 0.0
        var wood = 0.0
        var stone = 0.0
        var knowledge = 0.0
        var wealth = 0.0

        for (citizen in members) {
            if (citizen.isChild || citizen.job == Job.IDLE) continue
            val effort = competence(citizen) * traits.workMultiplier * techMultiplier * unrestPenalty
            if (effort <= 0.0) continue

            val standingOn = world.index(citizen.x, citizen.y)
            // A worker counts as at work when standing on their cell or beside it. Requiring the
            // exact cell deadlocked workers whose plot was occupied by a passer-by.
            val cell = if (citizen.workCell == World.NONE) standingOn else citizen.workCell
            val atWork = citizen.workCell == World.NONE || adjacentOrSame(world, standingOn, citizen.workCell)

            when (citizen.job) {
                Job.FARMER -> if (atWork) {
                    val fertility = world.fertility[cell].toDouble()
                    food += fertility * traits.farmYield * effort * seasonMod *
                        Economy.FARM_OUTPUT_SCALE * effects.farmYieldBonus
                    world.fertility[cell] = max(
                        0f,
                        world.fertility[cell] - TerrainConfig.FERTILITY_DRAIN_PER_FARM_DAY.toFloat(),
                    )
                }
                Job.HUNTER -> if (atWork) {
                    val game = world.wildGame[cell].toDouble()
                    food += game * traits.huntYield * effort * Economy.HUNT_OUTPUT_SCALE
                    // Hunting depletes the cell in proportion to what was taken.
                    world.wildGame[cell] = max(0f, world.wildGame[cell] - (game * TerrainConfig.HUNT_DEPLETION_PER_DAY).toFloat())
                }
                Job.GATHERER -> if (atWork) {
                    val output = Economy.GATHERER_OUTPUT * effort
                    if (world.terrainAt(cell) == TerrainType.FOREST) wood += output else stone += output
                }
                Job.SCHOLAR -> knowledge += Economy.SCHOLAR_OUTPUT * Economy.KNOWLEDGE_OUTPUT_SCALE *
                    effort * effects.knowledgeMultiplier
                Job.ARTISAN -> wealth += Economy.ARTISAN_OUTPUT * effort
                // Builders consume their output into construction (M4); healers raise care
                // access; soldiers contribute strength, not goods.
                Job.BUILDER, Job.HEALER, Job.SOLDIER, Job.CHILD, Job.IDLE -> Unit
            }

            growSkill(citizen, skillGrowthMultiplier)
        }

        // Mills turn surplus grain into money.
        if (effects.foodToWealth > 0.0) {
            val dailyNeed = members.sumOf { it.dailyFoodNeed(civ.traits) }
            val surplus = max(0.0, civ[Resource.FOOD] - dailyNeed * 30)
            // Capped at a day's consumption: a mill is a building, not a money printer. Without
            // this a fifty-year run banked 1.6 million wealth.
            val converted = min(surplus * effects.foodToWealth, dailyNeed * MILL_DAILY_CONVERSION_CAP)
            civ.take(Resource.FOOD, converted)
            wealth += converted
        }

        civ.add(Resource.FOOD, food)
        civ.add(Resource.WOOD, wood)
        civ.add(Resource.STONE, stone)
        civ.add(Resource.KNOWLEDGE, knowledge)
        civ.add(Resource.WEALTH, wealth)
    }

    /**
     * What one worker gets done: what they have learned, what they are, and how their body is
     * doing today. See `Citizen.effectiveness`.
     */
    private fun competence(citizen: Citizen): Double = citizen.effectiveness()

    private fun growSkill(citizen: Citizen, growthMultiplier: Double) {
        val perDay = growthMultiplier / (Life.SKILL_YEARS_TO_MASTERY * GameConfig.Time.DAYS_PER_YEAR)
        citizen.skill = min(1f, citizen.skill + perDay.toFloat())
    }

    // ------------------------------------------------------------------ the land

    /**
     * Soil recovers and game regrows, both toward the cell's terrain baseline. Recovery scales
     * with the owning civ's Farming trait, which is why a farming people can work land harder
     * without exhausting it.
     */
    fun regenerateLand(world: World, civs: List<Civilization>) {
        val recoveryByCiv = DoubleArray(civs.size) { civs[it].traits.fertilityRecoveryPerDay }
        val defaultRecovery = GameConfig.Traits.FERTILITY_RECOVERY_BASE

        // Indexed lookups, not map lookups: this loop runs over every cell in the world on every
        // tick, and two hash lookups per cell dominated the whole simulation in the web build.
        val fertilityCaps = TerrainConfig.FERTILITY_BY_TERRAIN
        val gameCaps = TerrainConfig.WILD_GAME_BY_TERRAIN
        val regen = TerrainConfig.WILD_GAME_REGEN_PER_DAY.toFloat()

        for (cell in 0 until world.cellCount) {
            val terrain = world.terrain[cell].toInt()

            val fertilityCap = fertilityCaps[terrain]
            val fertility = world.fertility[cell]
            if (fertilityCap > 0f && fertility < fertilityCap) {
                val owner = world.ownerCivId[cell].toInt()
                val rate = if (owner >= 0) recoveryByCiv[owner] else defaultRecovery
                world.fertility[cell] = min(fertilityCap, fertility + rate.toFloat())
            }

            val gameCap = gameCaps[terrain]
            val game = world.wildGame[cell]
            if (gameCap > 0f && game < gameCap) {
                world.wildGame[cell] = min(gameCap, game + gameCap * regen)
            }
        }
    }

    /** Jobs that send a citizen out to a specific cell. */
    private val FIELD_JOBS = setOf(Job.FARMER, Job.HUNTER, Job.GATHERER)

    /**
     * A mill may convert at most this multiple of the town's daily consumption into wealth each
     * day. Uncapped, a fifty-year run banked 1.6 million wealth against an upkeep bill of six a
     * day. Wealth gets its real sinks — trade, tribute, war — at M5.
     */
    private const val MILL_DAILY_CONVERSION_CAP = 0.10

    /** Fixed order so job assignment never depends on map iteration order. */
    private val JOB_ORDER = listOf(
        Job.FARMER, Job.HUNTER, Job.GATHERER, Job.BUILDER,
        Job.SCHOLAR, Job.HEALER, Job.ARTISAN, Job.SOLDIER,
    )
}
