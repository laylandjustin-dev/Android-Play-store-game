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
) {
    val isPlayer: Boolean get() = id == GameConfig.World.PLAYER_CIV_ID

    /** Stored resources, indexed by [Resource.ordinal]. */
    val stores = DoubleArray(Resource.entries.size)

    var foodStorageCapacity: Double = GameConfig.Economy.BASE_FOOD_STORAGE_CAPACITY

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

    operator fun get(resource: Resource): Double = stores[resource.ordinal]

    operator fun set(resource: Resource, value: Double) {
        stores[resource.ordinal] = value
    }

    fun add(resource: Resource, amount: Double) {
        stores[resource.ordinal] += amount
    }

    /** Removes up to [amount]; returns what was actually taken. */
    fun take(resource: Resource, amount: Double): Double {
        val taken = minOf(amount, stores[resource.ordinal])
        stores[resource.ordinal] -= taken
        return taken
    }

    /** Days of food left at the current population's consumption, used by the crisis rules. */
    fun daysOfFood(dailyConsumption: Double): Double =
        if (dailyConsumption <= 0.0) Double.MAX_VALUE else this[Resource.FOOD] / dailyConsumption
}
