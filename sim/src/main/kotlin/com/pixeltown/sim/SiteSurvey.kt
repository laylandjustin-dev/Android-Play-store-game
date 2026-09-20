package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.World as WorldConfig
import kotlin.math.roundToInt

/**
 * What the land around a cell is worth, so the player can see it before they land on it.
 *
 * This exists because of the single worst thing the map screen did: it offered every buildable
 * mainland cell as a legal place to found a colony, and the difference between the best and the
 * worst was a factor of six in nearby food. The generator scores its own five candidates and takes
 * the best separated ones, so all four rivals began on prime land while the player could tap a spot
 * that could not feed fifty-five people — and nothing on screen said so.
 *
 * Forbidding the bad cells was the other option and it was rejected twice over. AD-51 already
 * settles the principle: a UI that lets the player tap anywhere and then quietly overrules them is
 * worse than no choice at all. And the data does not support a clean threshold — a site at 0.51 of
 * the map's best score thrives where one at 0.55 starves, because what decides it is how many
 * farmers physically *reach* a field, not the sum of fertility in a radius. So the honest fix is to
 * show the player what the generator can see, in the units the game is actually played in — days of
 * food, not a score — and let them choose.
 *
 * Pure arithmetic over the world grid: no RNG, nothing cached, so a survey never disagrees with the
 * run that follows it.
 */
data class SiteSurvey(
    val cell: Int,
    val legal: Boolean,
    /** Sum of soil fertility within [WorldConfig.CIV_START_SCORE_RADIUS]. */
    val farmland: Double,
    /** Sum of wild game in the same window — what hunters have to work with. */
    val game: Double,
    /** Cells of forest and of hill/mountain, i.e. where timber and stone come from. */
    val timber: Int,
    val stone: Int,
    /** Fresh water within the window: a river cell adjacent to the site is worth having. */
    val freshWater: Int,
    /** The generator's own site score, and this cell's share of the best score on the map. */
    val score: Double,
    val shareOfBest: Double,
) {
    /**
     * Roughly how many people this land can feed, at the documented farm output.
     *
     * The single most useful number on the screen, because it is directly comparable to the
     * fifty-five settlers stepping off the boat. Deliberately approximate and deliberately stated
     * as such in the UI: it assumes a competent farmer on every fertile cell in range, which no real
     * colony achieves, so it is an upper bound rather than a forecast.
     */
    fun feedsAbout(traits: TraitAllocation): Int {
        val perFarmedCell = traits.farmYield * GameConfig.Economy.FARM_OUTPUT_SCALE *
            GameConfig.Economy.SKILL_OUTPUT_FLOOR
        return (farmland * perFarmedCell / GameConfig.Economy.FOOD_PER_ADULT_PER_DAY).roundToInt()
    }

    /** A one-word verdict, for a label the player reads at a glance. */
    val rating: String
        get() = when {
            !legal -> "unbuildable"
            shareOfBest >= 0.85 -> "prime"
            shareOfBest >= 0.70 -> "good"
            shareOfBest >= 0.55 -> "fair"
            shareOfBest >= 0.40 -> "poor"
            else -> "bleak"
        }

    companion object {

        /**
         * Surveys [cell] against [bestScore] — the best score anywhere on this map, which is what
         * makes the rating comparable between seeds rather than between maps of different richness.
         */
        fun of(world: World, cell: Int, legal: Boolean, bestScore: Double): SiteSurvey {
            val r = WorldConfig.CIV_START_SCORE_RADIUS
            val x = cell % world.width
            val y = cell / world.width
            var farmland = 0.0
            var game = 0.0
            var timber = 0
            var stone = 0
            var water = 0
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val nx = x + dx
                    val ny = y + dy
                    if (!world.inBounds(nx, ny)) continue
                    val i = world.index(nx, ny)
                    farmland += world.fertility[i]
                    game += world.wildGame[i]
                    when (world.terrainAt(i)) {
                        TerrainType.FOREST -> timber++
                        TerrainType.HILL, TerrainType.MOUNTAIN -> stone++
                        TerrainType.RIVER -> water++
                        else -> Unit
                    }
                }
            }
            val score = farmland + 0.5 * game
            return SiteSurvey(
                cell = cell,
                legal = legal,
                farmland = farmland,
                game = game,
                timber = timber,
                stone = stone,
                freshWater = water,
                score = score,
                shareOfBest = if (bestScore <= 0.0) 0.0 else (score / bestScore).coerceIn(0.0, 1.0),
            )
        }

        /**
         * The best site score anywhere on this map, over the cells a colony could legally use.
         *
         * Walks the grid once; a caller surveying many cells computes it once and passes it in.
         */
        fun bestScore(world: World, legal: BooleanArray): Double {
            var best = 0.0
            for (i in 0 until world.cellCount) {
                if (!legal[i]) continue
                val survey = of(world, i, true, 1.0)
                if (survey.score > best) best = survey.score
            }
            return best
        }
    }
}
