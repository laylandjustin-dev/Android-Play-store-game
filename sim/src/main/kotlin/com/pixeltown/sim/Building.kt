package com.pixeltown.sim

/**
 * What a building is, what it costs, and what it does.
 *
 * The catalogue itself lives in [GameConfig.Buildings] — this file defines the shape, not the
 * numbers. A building does nothing at all until it is finished: [Building.isComplete].
 */
enum class BuildingType {
    // Farms
    FIELD, GRANARY, IRRIGATION, MILL,

    // Health
    HUT, CLINIC, AQUEDUCT, HOSPITAL,

    // Military
    WATCHTOWER, BARRACKS, WALL, ARMOURY,

    // Tech
    WORKSHOP, LIBRARY, ACADEMY, OBSERVATORY,

    // Lifestyle
    HOUSING, PLAZA, TEMPLE, THEATRE,
}

/**
 * The immutable design of one kind of building: cost, footprint, and every effect it has.
 *
 * Effects are additive across a civ's completed buildings and aggregated once per tick into
 * [CivEffects], so no system ever walks the building list itself.
 */
data class BuildingSpec(
    val type: BuildingType,
    val category: BuildingCategory,
    /** Tech tier required before this can be built. 0 is available from the founding. */
    val tier: Int,
    val footprint: Int,
    val buildPointsRequired: Double,
    val woodCost: Double,
    val stoneCost: Double,
    val upkeepWealth: Double,

    /** People who can call this home. Drives shelter quality and housing slack. */
    val housingCapacity: Int = 0,
    /** Added to the civ's food storage capacity, above which food spoils. */
    val foodStorageBonus: Double = 0.0,
    /** Citizens whose care needs this building covers. */
    val careCapacity: Double = 0.0,
    /** Added to the morale of citizens within [range]. */
    val moraleBonus: Double = 0.0,
    /** Influence points per day for the player, and influence growth for nearby citizens. */
    val influenceBonus: Double = 0.0,
    /** Added to the civ's military strength. */
    val militaryStrength: Double = 0.0,
    /** Added to the safety term of the survival score, before rivals are considered. */
    val safetyBonus: Double = 0.0,
    /** Multiplier on scholar output. */
    val knowledgeMultiplier: Double = 0.0,
    /** Multiplier on farm yields. */
    val farmYieldBonus: Double = 0.0,
    /** Floor under the seasonal yield multiplier — irrigation keeps winter from biting. */
    val seasonFloor: Double = 0.0,
    /** Multiplier on builder output. */
    val buildSpeedBonus: Double = 0.0,
    /** Added to the people's disease resistance. */
    val diseaseResistBonus: Double = 0.0,
    /** Converts surplus food into wealth each day, at this rate. */
    val foodToWealth: Double = 0.0,
    /** How far the local effects (morale, care, shelter) reach, in cells. */
    val range: Int = 0,
) {
    val totalCost: Double get() = woodCost + stoneCost
}

/** One built — or half-built — structure on the map. */
class Building(
    val id: Int,
    val type: BuildingType,
    val civId: Int,
    val x: Int,
    val y: Int,
) {
    val spec: BuildingSpec get() = GameConfig.Buildings.spec(type)

    /** Builder output accumulated so far. A building does nothing until this reaches its cost. */
    var buildProgress: Double = 0.0

    var isComplete: Boolean = false
        private set

    /** Occupants, for housing. */
    var residents: Int = 0

    /** Restores a saved building's progress without re-running construction. */
    fun restoreProgress(progress: Double, complete: Boolean) {
        buildProgress = progress
        isComplete = complete
    }

    /** Adds builder output; returns true on the tick the building is finished. */
    fun addProgress(points: Double): Boolean {
        if (isComplete) return false
        buildProgress += points
        if (buildProgress >= spec.buildPointsRequired) {
            isComplete = true
            return true
        }
        return false
    }

    fun completionFraction(): Double =
        (buildProgress / spec.buildPointsRequired).coerceIn(0.0, 1.0)

    /** True if this building's footprint covers the given cell. */
    fun covers(cellX: Int, cellY: Int): Boolean {
        val size = spec.footprint
        return cellX >= x && cellX < x + size && cellY >= y && cellY < y + size
    }
}

/**
 * A civ's building effects, summed once per tick.
 *
 * Systems read this rather than walking the building list: the survival score alone would
 * otherwise do a building sweep per citizen per tick.
 */
data class CivEffects(
    val housingCapacity: Int = 0,
    val foodStorageBonus: Double = 0.0,
    val careCapacity: Double = 0.0,
    val moraleBonus: Double = 0.0,
    val influencePerDay: Double = 0.0,
    val militaryStrength: Double = 0.0,
    val safetyBonus: Double = 0.0,
    val knowledgeMultiplier: Double = 1.0,
    val farmYieldBonus: Double = 1.0,
    val seasonFloor: Double = 0.0,
    val buildSpeedBonus: Double = 1.0,
    val diseaseResistBonus: Double = 0.0,
    val foodToWealth: Double = 0.0,
    val upkeepWealth: Double = 0.0,
    val completedCount: Int = 0,
) {
    /**
     * These effects with a civ's chosen techs folded in.
     *
     * Techs deliberately land in the same struct buildings produce, because every system already
     * reads its civ's effects — so a new tech needs no new plumbing and no system can forget it.
     */
    fun withTech(choices: List<TechOption>): CivEffects {
        if (choices.isEmpty()) return this
        var farm = 0.0
        var know = 0.0
        var build = 0.0
        var storageFraction = 0.0
        var militaryFraction = 0.0
        var safety = 0.0
        var disease = 0.0
        var care = 0.0
        for (choice in choices) {
            val e = choice.effects
            farm += e.farmYield
            know += e.knowledge
            build += e.buildSpeed
            storageFraction += e.foodStorage
            militaryFraction += e.military
            safety += e.safety
            disease += e.diseaseResist
            care += e.care
        }
        return copy(
            farmYieldBonus = farmYieldBonus + farm,
            knowledgeMultiplier = knowledgeMultiplier + know,
            buildSpeedBonus = buildSpeedBonus + build,
            foodStorageBonus = foodStorageBonus +
                GameConfig.Economy.BASE_FOOD_STORAGE_CAPACITY * storageFraction,
            militaryStrength = militaryStrength * (1.0 + militaryFraction),
            safetyBonus = safetyBonus + safety,
            diseaseResistBonus = diseaseResistBonus + disease,
            careCapacity = careCapacity + care,
        )
    }

    companion object {
        val NONE = CivEffects()
    }
}
