package com.pixeltown.sim

/**
 * A civilisation's shared state: its people's traits, its stores, and its politics.
 *
 * Citizens belong to a civ by id; the civ holds everything that is owned collectively. The player
 * is civ 0. Rivals use this same class — they are not a separate, cheaper model.
 */
class Civilization(
    val id: Int,
    val name: String,
    /**
     * The civ's current traits. Replaced, not mutated, when a decade's growth point is spent —
     * every derived stat is computed in [TraitAllocation]'s constructor, and nothing caches it.
     */
    var traits: TraitAllocation,
    val personality: Personality,
    /** Cell index this civ was founded on. */
    val homeSite: Int,
    /**
     * What kind of people this is, from the allocation it was *founded* with.
     *
     * Taken once and never recomputed: a civ earns a trait point every decade (AD-50), so deriving
     * this from current traits would let a people stop being Stalkers halfway through a run. A
     * nation is what it was founded as.
     */
    val archetype: CivArchetype = CivArchetype.of(traits),
) {
    val isPlayer: Boolean get() = id == GameConfig.World.PLAYER_CIV_ID

    /** Stored resources, indexed by [Resource.ordinal]. */
    val stores = DoubleArray(Resource.entries.size)

    var foodStorageCapacity: Double = GameConfig.Economy.MIN_FOOD_STORAGE_CAPACITY

    var techTier: Int = 0

    var unrest: Double = 0.0

    /**
     * Trait points earned by living and not yet spent (one per
     * [GameConfig.Traits.GENERATION_INTERVAL_YEARS] years). The player's accumulate until they
     * choose; a rival spends its own the day it earns it.
     */
    var unspentTraitPoints: Int = 0

    /** Growth awards this civ has received, so an award is never paid twice for the same decade. */
    var generationsAwarded: Int = 0

    /** The day the oldest unspent point was earned, for the player's auto-spend grace period. */
    var oldestUnspentPointDay: Long = 0L

    /**
     * Every decade point this people has spent, in the order they spent them.
     *
     * Kept because the opening allocation and the final trait sheet do not tell the player what
     * they actually decided: a Farming-8 people could have opened there or arrived there over two
     * centuries, and those are different runs. The end-of-run breakdown reads this.
     */
    val traitGrowthHistory: MutableList<Trait> = mutableListOf()

    /** How many of those points the town spent for itself because nobody chose in time. */
    var autoSpentTraitPoints: Int = 0

    /**
     * The standing instruction the player has left their town, or null.
     *
     * Unlike a petition this survives the Premier who was in office when it was made: every future
     * agenda is weighted toward it until the player says otherwise. See
     * `GameConfig.Politics.CHARTER_WEIGHT`.
     */
    var charter: BuildingCategory? = null

    /** Techs this civ has chosen, one per tier reached. Permanent for the run. */
    val techChoices: MutableList<TechOption> = mutableListOf()

    /**
     * A tier reached whose choice has not been made yet, or null.
     *
     * For the player this stops the clock: reaching a tier is a decision, and a decision the game
     * takes for you while you watch is not one. Rivals never hold one open.
     */
    var pendingTechTier: Int? = null

    /** Days left in the current epidemic, 0 when the town is well. */
    var epidemicDaysLeft: Int = 0

    /** Epidemics this civ has lived through, for the Ledger and the return report. */
    var epidemicCount: Int = 0

    /** Influence points the player spends on the council. Accrues for rivals too, unused for now. */
    var influencePoints: Double = 0.0

    /** Consecutive days this civ has failed to pay its building upkeep. */
    var unpaidUpkeepDays: Int = 0

    /** Vetoes the player has spent this year, reset at each election. */
    var vetoesUsedThisYear: Int = 0

    /** How many terms this civ has elected. */
    var termCount: Int = 0

    // Run statistics, kept here so the end-of-run scoring never has to re-walk history.
    var peakPopulation: Int = 0
    var totalBirths: Int = 0
    var totalDeaths: Int = 0

    /**
     * What killed this people, by cause. Per civ rather than read off the Chronicle: the Chronicle
     * is a ring buffer shared by all five civilisations, so it can answer "how did people die on
     * this map" but never "how did *my* town die", which is the question the end-of-run breakdown
     * is asking.
     */
    val deathsByCause = IntArray(DeathCause.entries.size)

    /** Wars this civ has been party to, and raids launched against it. For the end-of-run record. */
    var warsFought: Int = 0
    var raidsSuffered: Int = 0

    var population: Int = 0
        set(value) {
            field = value
            if (value > peakPopulation) peakPopulation = value
        }

    val isExtinct: Boolean get() = population == 0

    /**
     * Restores the historical peak from a save. Assigning [population] on load would otherwise
     * reset the peak to whatever the town happens to be now, losing the run's high-water mark —
     * which the end-of-run score is calculated from.
     */
    fun restorePeakPopulation(peak: Int) {
        peakPopulation = peak
    }

    /**
     * Everything this civ has ever produced and everything it has ever spent, per resource.
     *
     * Counted in [add] and [take] rather than at each call site, because those two are the only
     * doors into the store and a ledger with a door it does not watch is worse than no ledger. The
     * end-of-run breakdown reads it; nothing in the simulation does, which is what keeps it honest.
     *
     * `set` is deliberately *not* counted: it is used by the save codec to restore a store, and by
     * spoilage to write a reduced figure, neither of which is a town producing or spending anything.
     * Spoilage is tracked separately as [spoiled].
     */
    val produced = DoubleArray(Resource.entries.size)
    val consumed = DoubleArray(Resource.entries.size)

    /** Food that rotted before anyone could eat it. Not "consumed": nobody got the good of it. */
    var spoiled: Double = 0.0

    /**
     * Mean fertility of the cells this civ's farmers actually worked on the last tick, or
     * [Terrain.PRISTINE_WORKED_FERTILITY] while nobody is farming.
     *
     * A town's answer to "how many of us should be in the fields" has to answer to whether the
     * fields are still worth working. Before this, the farm share was fixed at 70% of food workers
     * whatever the land was doing, so a people who could not restore its soil — anything at base
     * Farming, against a drain above base recovery — farmed the same ground until mean fertility
     * fell from 0.74 to 0.41 and then starved beside an untouched range. Health and Speed builds
     * died in year 1 of a problem neither trait has anything to do with.
     *
     * Accumulated in the production loop, which already visits every worker's cell, so it costs a
     * running sum rather than a sweep — see AD-31.
     */
    var meanWorkedFertility: Double = GameConfig.Terrain.PRISTINE_WORKED_FERTILITY

    operator fun get(resource: Resource): Double = stores[resource.ordinal]

    operator fun set(resource: Resource, value: Double) {
        stores[resource.ordinal] = value
    }

    fun add(resource: Resource, amount: Double) {
        if (amount <= 0.0) return
        stores[resource.ordinal] += amount
        produced[resource.ordinal] += amount
    }

    /** Removes up to [amount]; returns what was actually taken. */
    fun take(resource: Resource, amount: Double): Double {
        val taken = minOf(amount, stores[resource.ordinal])
        stores[resource.ordinal] -= taken
        consumed[resource.ordinal] += taken
        return taken
    }

    /** Days of food left at the current population's consumption, used by the crisis rules. */
    fun daysOfFood(dailyConsumption: Double): Double =
        if (dailyConsumption <= 0.0) Double.MAX_VALUE else this[Resource.FOOD] / dailyConsumption
}
