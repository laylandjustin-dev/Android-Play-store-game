package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Buildings as BuildingConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Siting, construction, upkeep, housing, and the aggregation of building effects.
 *
 * A building is inert until finished: builder output accumulates into [Building.buildProgress] and
 * only on completion does the structure start contributing to [CivEffects].
 */
internal object BuildingSystem {

    /**
     * Finds a site for a [footprint]-sized building near the civ's home: buildable ground, clear
     * of other buildings, with a gap between structures so a town does not become a solid block.
     */
    fun findSite(world: World, civ: Civilization, footprint: Int): Int? {
        val homeX = civ.homeSite % world.width
        val homeY = civ.homeSite / world.width

        // Nearest-first ring search, so towns grow outward from their centre. The reach grows
        // with the population — a town of a thousand is not built inside fourteen cells.
        val reach = (BuildingConfig.BASE_SITE_DISTANCE +
            civ.population * BuildingConfig.SITE_DISTANCE_PER_CITIZEN).toInt()
            .coerceIn(BuildingConfig.BASE_SITE_DISTANCE, BuildingConfig.MAX_SITE_DISTANCE)

        for (radius in 2..reach) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (max(abs(dx), abs(dy)) != radius) continue
                    val x = homeX + dx
                    val y = homeY + dy
                    if (fits(world, x, y, footprint)) return world.index(x, y)
                }
            }
        }
        return null
    }

    /**
     * Whether a footprint fits here, including the gap between structures.
     *
     * Checked against the world grid, which already records every building's footprint, rather
     * than by scanning the civ's building list: the list version was O(cells x buildings) per
     * build order and dominated the tick once towns held hundreds of structures.
     */
    private fun fits(world: World, x: Int, y: Int, footprint: Int): Boolean {
        val gap = BuildingConfig.SITE_SPACING
        for (dy in -gap until footprint + gap) {
            for (dx in -gap until footprint + gap) {
                val cx = x + dx
                val cy = y + dy
                val inFootprint = dx in 0 until footprint && dy in 0 until footprint
                if (!world.inBounds(cx, cy)) {
                    if (inFootprint) return false else continue
                }
                val cell = world.index(cx, cy)
                // The footprint itself must be buildable; the surrounding gap need only be clear.
                if (inFootprint && !world.isBuildable(cell)) return false
                if (world.buildingId[cell] != World.NONE) return false
            }
        }
        return true
    }

    /** Stamps a building's footprint into the world grid and claims the land for its civ. */
    fun place(world: World, building: Building) {
        val footprint = building.spec.footprint
        for (dy in 0 until footprint) {
            for (dx in 0 until footprint) {
                val cx = building.x + dx
                val cy = building.y + dy
                if (!world.inBounds(cx, cy)) continue
                val cell = world.index(cx, cy)
                world.buildingId[cell] = building.id
                world.ownerCivId[cell] = building.civId.toByte()
            }
        }
    }

    /** Frees a building's footprint when it falls to ruin. */
    fun remove(world: World, building: Building) {
        val footprint = building.spec.footprint
        for (dy in 0 until footprint) {
            for (dx in 0 until footprint) {
                val cx = building.x + dx
                val cy = building.y + dy
                if (!world.inBounds(cx, cy)) continue
                val cell = world.index(cx, cy)
                if (world.buildingId[cell] == building.id) world.buildingId[cell] = World.NONE
            }
        }
    }

    /**
     * Applies one day of builder output to a civ's unfinished buildings, oldest first, so a town
     * finishes what it started instead of leaving a field of foundations.
     *
     * Returns the buildings completed today.
     */
    fun advanceConstruction(
        builders: List<Citizen>,
        buildings: List<Building>,
        effects: CivEffects,
        traits: TraitAllocation,
        techMultiplier: Double,
    ): List<Building> {
        val unfinished = buildings.filter { !it.isComplete }
        if (unfinished.isEmpty() || builders.isEmpty()) return emptyList()

        var points = 0.0
        for (builder in builders) {
            val competence = GameConfig.Economy.SKILL_OUTPUT_FLOOR +
                (1.0 - GameConfig.Economy.SKILL_OUTPUT_FLOOR) * builder.skill
            points += GameConfig.Economy.BUILDER_OUTPUT * competence * traits.workMultiplier *
                techMultiplier * effects.buildSpeedBonus
        }

        val completed = ArrayList<Building>()
        val target = unfinished.first()
        if (target.addProgress(points)) completed.add(target)
        return completed
    }

    /**
     * Sums the effects of a civ's completed buildings. Called once per tick per civ; systems read
     * the result rather than walking the building list themselves.
     */
    fun aggregate(buildings: List<Building>): CivEffects {
        var housing = 0
        var storage = 0.0
        var care = 0.0
        var morale = 0.0
        var influence = 0.0
        var military = 0.0
        var safety = 0.0
        var knowledge = 1.0
        var farmYield = 1.0
        var seasonFloor = 0.0
        var buildSpeed = 1.0
        var disease = 0.0
        var foodToWealth = 0.0
        var upkeep = 0.0
        var count = 0

        for (building in buildings) {
            if (!building.isComplete) continue
            val spec = building.spec
            housing += spec.housingCapacity
            storage += spec.foodStorageBonus
            care += spec.careCapacity
            morale += spec.moraleBonus
            influence += spec.influenceBonus
            military += spec.militaryStrength
            safety += spec.safetyBonus
            knowledge += spec.knowledgeMultiplier
            farmYield += spec.farmYieldBonus
            seasonFloor = max(seasonFloor, spec.seasonFloor)
            buildSpeed += spec.buildSpeedBonus
            disease += spec.diseaseResistBonus
            foodToWealth += spec.foodToWealth
            upkeep += spec.upkeepWealth
            count++
        }

        return CivEffects(
            housingCapacity = housing,
            foodStorageBonus = storage,
            careCapacity = care,
            moraleBonus = min(morale, MAX_MORALE_BONUS),
            influencePerDay = influence,
            militaryStrength = military,
            safetyBonus = min(safety, MAX_SAFETY_BONUS),
            knowledgeMultiplier = min(knowledge, MAX_KNOWLEDGE_MULTIPLIER),
            farmYieldBonus = min(farmYield, MAX_FARM_YIELD_BONUS),
            seasonFloor = seasonFloor,
            buildSpeedBonus = min(buildSpeed, MAX_BUILD_SPEED_BONUS),
            diseaseResistBonus = disease,
            foodToWealth = foodToWealth,
            upkeepWealth = upkeep,
            completedCount = count,
        )
    }

    /**
     * Effects that would otherwise compound without limit as a town sprawls. Nine libraries in a
     * fifty-year run multiplied knowledge output by five and put a colony at tech tier 6 — which
     * the design wants most runs never to reach.
     */
    private const val MAX_MORALE_BONUS = 0.35
    private const val MAX_SAFETY_BONUS = 0.5
    private const val MAX_KNOWLEDGE_MULTIPLIER = 2.5
    private const val MAX_FARM_YIELD_BONUS = 2.0
    private const val MAX_BUILD_SPEED_BONUS = 2.0
}
