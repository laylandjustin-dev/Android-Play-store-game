@file:OptIn(ExperimentalJsExport::class)

package com.pixeltown.web

import com.pixeltown.sim.Agenda
import com.pixeltown.sim.Archetype
import com.pixeltown.sim.BuildingCategory
import com.pixeltown.sim.Citizen
import com.pixeltown.sim.ChronicleEventKind
import com.pixeltown.sim.CivColors
import com.pixeltown.sim.ColonyName
import com.pixeltown.sim.FrameRenderer
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.Palette
import com.pixeltown.sim.Resource
import com.pixeltown.sim.RunConfig
import com.pixeltown.sim.SimRandom
import com.pixeltown.sim.Simulation
import com.pixeltown.sim.Trait
import com.pixeltown.sim.TechOption
import com.pixeltown.sim.TraitAllocation
import com.pixeltown.sim.World
import com.pixeltown.sim.WorldGenerator
import com.pixeltown.sim.WorldRenderer
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * The browser's handle on the real simulation.
 *
 * This is the *only* JavaScript-facing code in the project, and it holds no game logic — every
 * tick, every election and every war comes from `:sim`, the same module the Android app uses,
 * compiled to JavaScript. There is no second implementation of the game to drift.
 *
 * The boundary is deliberately two calls wide: [pixels] hands over the frame the renderer painted,
 * and [state] hands over everything the HUD needs as one JSON string. Crossing the Kotlin/JS
 * boundary per citizen would be far more expensive than the simulation itself.
 */
@JsExport
class WebGame(
    seed: Double,
    speed: Int,
    health: Int,
    hunting: Int,
    elements: Int,
    farming: Int,
    colonyName: String = ColonyName.DEFAULT,
    /** Cell the player picked on the map preview, or -1 to take the generator's choice. */
    startCell: Int = -1,
    colorIndex: Int = 0,
) {
    private val simulation = Simulation.newRun(
        RunConfig(
            seed = seed.toLong(),
            traits = TraitAllocation(speed, health, hunting, elements, farming),
            colonyName = colonyName,
            startCell = startCell.takeIf { it >= 0 },
            colorIndex = colorIndex,
        ),
    )

    /** What the colony ended up called — the typed name, cleaned by `:sim`, or the default. */
    val colony: String = simulation.civ(GameConfig.World.PLAYER_CIV_ID).name
    private val renderer = FrameRenderer(simulation.world)
    private val buffer = IntArray(simulation.world.cellCount)
    /**
     * The last day whose events have been handed to the interface.
     *
     * Starts before day zero so the founding events are reported once, and the filter below is
     * strictly greater — reading the state twice on the same day used to resend that day's events
     * and the feed showed everything twice.
     */
    private var reportedUpTo = -1L

    val width: Int = simulation.world.width
    val height: Int = simulation.world.height

    /** Runs whole days. Returns how many actually ran — a finished run stops advancing. */
    fun step(days: Int): Int {
        var ran = 0
        // Stops on a pending decision as well as on the end of the run, and counts days the
        // simulation actually took. Counting loop iterations instead reported a full year run
        // while the world sat frozen at a tech choice, which is exactly the lie the caller uses
        // this number to avoid.
        while (ran < days && simulation.endState == null && !simulation.awaitingPlayer) {
            simulation.step()
            ran++
        }
        return ran
    }

    /**
     * Highlight mode: dims the rival civilisations so the player's own people, territory and home
     * are unmistakable. Set from the interface; the default view keeps every civ fully visible.
     */
    var focusPlayer: Boolean = false

    /** The current frame as ARGB pixels, one per world cell. */
    fun pixels(): IntArray {
        renderer.render(simulation, buffer, ownershipTint = 0.18f, focusPlayer = focusPlayer)
        return buffer
    }

    /**
     * Spends one of the earned decade points. Returns true if it landed; false means the request
     * was not legal (nothing banked, or that trait is already at its ceiling).
     */
    /**
     * True while the simulation is refusing to advance because a decision is open. Read every tick
     * by the loop, so it is a plain property rather than something to parse out of [state].
     */
    val awaitingPlayer: Boolean get() = simulation.awaitingPlayer

    /**
     * The tech choices waiting on the player, as JSON, or `[]`. While this is non-empty [step] runs
     * no days at all — the simulation itself refuses, so a UI that forgot to stop cannot skip it.
     */
    fun pendingTech(): String {
        val options = simulation.pendingTechChoices(GameConfig.World.PLAYER_CIV_ID)
        return options.joinToString(",", "[", "]") {
            "{\"id\":\"${it.name}\",\"label\":\"${escape(it.label)}\"," +
                "\"blurb\":\"${escape(it.blurb)}\",\"tier\":${it.tier}}"
        }
    }

    /**
     * The person standing on a cell, as JSON, or `null`.
     *
     * The game's premise is that every person is one pixel; until now there was no way to look at
     * one. Search widens by a ring or two because a finger on a phone is wider than a world cell.
     */
    fun inspect(cell: Int, civOnly: Boolean = false): String {
        val world = simulation.world
        if (cell < 0 || cell >= world.cellCount) return "null"
        val x0 = cell % world.width
        val y0 = cell / world.width

        for (radius in 0..2) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (radius > 0 && kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != radius) continue
                    val x = x0 + dx
                    val y = y0 + dy
                    if (!world.inBounds(x, y)) continue
                    val id = world.occupantId[world.index(x, y)]
                    if (id == World.NONE) continue
                    val citizen = simulation.citizenOrNull(id) ?: continue
                    if (civOnly && citizen.civId != GameConfig.World.PLAYER_CIV_ID) continue
                    return describe(citizen)
                }
            }
        }
        return "null"
    }

    private fun describe(citizen: Citizen): String {
        val civ = simulation.civ(citizen.civId)
        val traits = civ.traits
        val partner = citizen.partnerId?.let { simulation.citizenOrNull(it) }
        return buildString {
            append("{\"id\":").append(citizen.id)
            append(",\"civ\":\"").append(escape(civ.name)).append('"')
            append(",\"mine\":").append(citizen.civId == GameConfig.World.PLAYER_CIV_ID)
            append(",\"colour\":\"").append(hex(simulation.colors[citizen.civId])).append('"')
            append(",\"age\":").append(citizen.ageYears)
            append(",\"sex\":\"").append(citizen.sex.name.lowercase()).append('"')
            append(",\"job\":\"").append(citizen.job.name.lowercase()).append('"')
            append(",\"skill\":").append((citizen.skill * 100).toInt())
            append(",\"vigour\":").append((citizen.vigour * 100).toInt())
            append(",\"strength\":").append((citizen.strength(traits) * 100).toInt())
            append(",\"effectiveness\":").append((citizen.effectiveness() * 100).toInt())
            append(",\"health\":").append((citizen.hp / traits.maxHp * 100).toInt())
            append(",\"fed\":").append((citizen.nutrition * 100).toInt())
            append(",\"morale\":").append((citizen.morale * 100).toInt())
            append(",\"survival\":").append(citizen.survival.toInt())
            append(",\"housed\":").append(citizen.homeBuildingId != null)
            append(",\"enlisted\":").append(citizen.enlisted)
            append(",\"pregnant\":").append(citizen.isPregnant)
            append(",\"partner\":").append(partner?.let { "${it.id}" } ?: "null")
            append(",\"leaning\":\"").append(citizen.politicalBias.name.lowercase()).append('"')
            append('}')
        }
    }

    /** Leaves a standing instruction every future Premier weights toward. Empty string clears it. */
    fun setCharter(category: String): Boolean {
        val target = BuildingCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
        return simulation.setCharter(GameConfig.World.PLAYER_CIV_ID, target)
    }

    /** Takes one of them. False if it was not on offer. */
    fun chooseTech(id: String): Boolean {
        val option = TechOption.entries.firstOrNull { it.name == id } ?: return false
        return simulation.chooseTech(GameConfig.World.PLAYER_CIV_ID, option)
    }

    fun spendTraitPoint(trait: String): Boolean {
        val which = Trait.entries.firstOrNull { it.name.equals(trait, ignoreCase = true) } ?: return false
        return simulation.spendTraitPoint(GameConfig.World.PLAYER_CIV_ID, which)
    }

    /** Everything the interface needs, as one JSON string. */
    fun state(): String {
        val player = simulation.civ(GameConfig.World.PLAYER_CIV_ID)
        val premier = simulation.premierOf(GameConfig.World.PLAYER_CIV_ID)
        val campaign = simulation.campaignFor(GameConfig.World.PLAYER_CIV_ID)
        val sb = StringBuilder(1024)

        sb.append("{\"colony\":\"").append(escape(colony)).append('"')
        sb.append(",\"year\":").append(simulation.year)
        sb.append(",\"day\":").append(simulation.day % GameConfig.Time.DAYS_PER_YEAR)
        sb.append(",\"season\":\"").append(simulation.season.name.lowercase()).append('"')
        sb.append(",\"pop\":").append(player.population)
        sb.append(",\"food\":").append(player[Resource.FOOD].toInt())
        sb.append(",\"wood\":").append(player[Resource.WOOD].toInt())
        sb.append(",\"stone\":").append(player[Resource.STONE].toInt())
        sb.append(",\"knowledge\":").append(player[Resource.KNOWLEDGE].toInt())
        sb.append(",\"wealth\":").append(player[Resource.WEALTH].toInt())
        sb.append(",\"tier\":").append(player.techTier)
        sb.append(",\"unrest\":").append(round2(player.unrest))
        sb.append(",\"influence\":").append(player.influencePoints.toInt())
        sb.append(",\"growthPoints\":").append(player.unspentTraitPoints)
        sb.append(",\"epidemic\":").append(player.epidemicDaysLeft > 0)
        sb.append(",\"awaiting\":").append(simulation.awaitingPlayer)
        sb.append(",\"charter\":").append(player.charter?.let { "\"${it.name.lowercase()}\"" } ?: "null")
        sb.append(",\"techs\":[")
        for ((i, choice) in player.techChoices.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"').append(escape(choice.label)).append('"')
        }
        sb.append(']')
        sb.append(",\"archetype\":\"").append(Archetype.of(player.traits).label).append('"')
        sb.append(",\"traits\":{")
        for ((i, trait) in Trait.entries.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"').append(trait.name.lowercase()).append("\":").append(player.traits[trait])
        }
        sb.append('}')
        sb.append(",\"buildings\":").append(simulation.buildingsOf(0).count { it.isComplete })
        sb.append(",\"armies\":").append(simulation.armiesInField.count { it.civId == 0 })
        sb.append(",\"end\":").append(simulation.endState?.let { "\"${it.name}\"" } ?: "null")

        simulation.summary()?.let { summary ->
            sb.append(",\"summary\":{\"years\":").append(summary.yearsSurvived)
            sb.append(",\"peak\":").append(summary.peakPopulation)
            sb.append(",\"tier\":").append(summary.techTier)
            sb.append(",\"points\":").append(summary.chroniclePointsEarned).append('}')
        }

        sb.append(",\"premier\":")
        if (premier == null) {
            sb.append("null")
        } else {
            sb.append("{\"name\":\"").append(escape(premier.name)).append('"')
            sb.append(",\"temperament\":\"").append(premier.temperament.name.lowercase()).append('"')
            sb.append(",\"agenda\":\"").append(premier.agenda.dominant.name.lowercase()).append('"')
            sb.append(",\"weights\":[")
            for ((index, category) in BuildingCategory.entries.withIndex()) {
                if (index > 0) sb.append(',')
                sb.append(round2(premier.agenda[category]))
            }
            sb.append("]}")
        }

        sb.append(",\"campaign\":")
        if (campaign == null) {
            sb.append("null")
        } else {
            sb.append('[')
            for ((index, candidate) in campaign.withIndex()) {
                if (index > 0) sb.append(',')
                sb.append("{\"id\":").append(candidate.citizenId)
                sb.append(",\"name\":\"").append(escape(candidate.name)).append('"')
                sb.append(",\"age\":").append(candidate.ageYears)
                sb.append(",\"agenda\":\"").append(candidate.agenda.dominant.name.lowercase()).append('"')
                sb.append(",\"temperament\":\"").append(candidate.temperament.name.lowercase()).append('"')
                sb.append(",\"pitch\":\"").append(escape(candidate.pitch)).append("\"}")
            }
            sb.append(']')
        }

        sb.append(",\"rivals\":[")
        for ((index, report) in simulation.rivalReports().withIndex()) {
            if (index > 0) sb.append(',')
            sb.append("{\"name\":\"").append(escape(report.name)).append('"')
            sb.append(",\"personality\":\"").append(report.personality.name.lowercase()).append('"')
            sb.append(",\"archetype\":\"")
                .append(Archetype.of(simulation.civ(report.civId).traits).label).append('"')
            sb.append(",\"pop\":").append(report.population)
            sb.append(",\"tension\":").append(round2(report.tension))
            sb.append(",\"war\":").append(report.atWar)
            // The run's own palette, not the stock table: rivals are recoloured around the
            // player's pick (AD-52), so Palette.civColor here showed the colour they used to be.
            sb.append(",\"colour\":\"").append(hex(simulation.colors[report.civId])).append("\"}")
        }
        sb.append(']')

        sb.append(",\"events\":[")
        val fresh = simulation.chronicle.since(reportedUpTo + 1)
            .filter { it.tick > reportedUpTo && it.kind in NOTABLE }
            .takeLast(14)
        for ((index, event) in fresh.withIndex()) {
            if (index > 0) sb.append(',')
            sb.append("{\"kind\":\"").append(event.kind.name).append('"')
            sb.append(",\"civ\":").append(event.civId)
            sb.append(",\"year\":").append(event.tick / GameConfig.Time.DAYS_PER_YEAR)
            sb.append(",\"detail\":\"").append(escape(event.detail ?: "")).append("\"}")
        }
        sb.append(']')
        reportedUpTo = simulation.day

        sb.append('}')
        return sb.toString()
    }

    // ---- the player's levers ----

    fun endorse(candidateId: Int): Boolean = simulation.endorse(GameConfig.World.PLAYER_CIV_ID, candidateId)

    fun petition(category: String, delta: Double): Boolean {
        val target = BuildingCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
            ?: return false
        return simulation.petition(GameConfig.World.PLAYER_CIV_ID, target, delta)
    }

    fun veto(): Boolean = simulation.veto(GameConfig.World.PLAYER_CIV_ID)

    fun referendum(): Boolean = simulation.callReferendum(GameConfig.World.PLAYER_CIV_ID)

    private fun round2(value: Double): String {
        val scaled = (value * 100).toInt()
        return "${scaled / 100}.${(if (scaled < 0) -scaled else scaled) % 100}"
    }

    private fun escape(text: String): String = text.replace("\\", "").replace("\"", "'")

    private fun hex(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        val digits = "0123456789ABCDEF"
        val out = StringBuilder("#")
        for (shift in 20 downTo 0 step 4) out.append(digits[(rgb shr shift) and 0xF])
        return out.toString()
    }

    private companion object {
        val NOTABLE = setOf(
            ChronicleEventKind.ELECTION, ChronicleEventKind.WAR_DECLARED, ChronicleEventKind.PEACE,
            ChronicleEventKind.RAID, ChronicleEventKind.COUP, ChronicleEventKind.TECH_TIER,
            ChronicleEventKind.BUILDING_COMPLETED, ChronicleEventKind.RUN_ENDED,
            ChronicleEventKind.FOUNDING,
        )
    }
}

/**
 * The island, before anyone lands on it, so the player can choose where to start.
 *
 * Generated from the same seed the run will use, so what the player taps on is exactly the map
 * they get. It is a second generation pass rather than a held-open [Simulation] — generation is a
 * pure function of the seed and costs a few milliseconds, and keeping a half-built run alive
 * across the allocation screen would be a far better way to get the two out of step.
 */
@JsExport
class WebPreview(seed: Double, private val colorIndex: Int = 0) {

    private val generated = WorldGenerator.generate(SimRandom(seed.toLong()))
    private val legal = WorldGenerator.legalStartSites(generated.world)
    private val terrain = IntArray(generated.world.cellCount).also {
        WorldRenderer.renderTerrain(generated.world, it)
    }
    private val buffer = IntArray(generated.world.cellCount)

    val width: Int = generated.world.width
    val height: Int = generated.world.height

    /** Where the generator would put the player if they do not choose. */
    val suggestedCell: Int = generated.civStartSites[GameConfig.World.PLAYER_CIV_ID]

    /** True if a colony could actually live there: buildable ground on the main landmass. */
    fun isLegal(cell: Int): Boolean = cell in legal.indices && legal[cell]

    /**
     * The island, with [selectedCell] ringed in the player's colour (-1 for none). Illegal ground
     * is darkened, so where the player may land is visible rather than something they discover by
     * tapping.
     */
    fun pixels(selectedCell: Int): IntArray {
        val colors = CivColors.forPlayerChoice(colorIndex)
        for (i in terrain.indices) {
            buffer[i] = if (legal[i]) terrain[i] else Palette.scaleBrightness(terrain[i], ILLEGAL_DIM)
        }
        if (isLegal(selectedCell)) {
            WorldRenderer.drawHomeMarker(
                generated.world, buffer, selectedCell, GameConfig.World.PLAYER_CIV_ID, colors = colors,
            )
            buffer[selectedCell] = colors[GameConfig.World.PLAYER_CIV_ID]
        }
        return buffer
    }

    private companion object {
        const val ILLEGAL_DIM = 0.72f
    }
}

/** The colours a player may choose from, as CSS hex, in the order the picker shows them. */
@JsExport
fun playerColours(): String =
    Palette.PLAYER_CHOICES.joinToString(",", "[", "]") { "\"${hexOf(it)}\"" }

private fun hexOf(argb: Int): String {
    val hex = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
    return "#$hex"
}

/** What a build is called and what it is known for — the same names the map's markers stand for. */
@JsExport
fun describeBuild(speed: Int, health: Int, hunting: Int, elements: Int, farming: Int): String {
    val archetype = Archetype.of(TraitAllocation(speed, health, hunting, elements, farming))
    return "{\"label\":\"${archetype.label}\",\"blurb\":\"${archetype.blurb}\"," +
        "\"shape\":\"${archetype.shape.name.lowercase()}\"}"
}

/** The derived numbers the allocation screen previews, without starting a run. */
@JsExport
fun previewTraits(speed: Int, health: Int, hunting: Int, elements: Int, farming: Int): String {
    val traits = TraitAllocation(speed, health, hunting, elements, farming)
    return "{\"work\":${fixed(traits.workMultiplier)}," +
        "\"lifespan\":${traits.lifespanYears.toInt()}," +
        "\"hunt\":${fixed(traits.huntYield)}," +
        "\"farm\":${fixed(traits.farmYield)}," +
        "\"shelter\":${fixed(traits.elementsShelter)}," +
        "\"winter\":${fixed(traits.seasonalYieldMultiplier(1.0))}}"
}

private fun fixed(value: Double): String {
    val scaled = (value * 100).toInt()
    val whole = scaled / 100
    val part = (if (scaled < 0) -scaled else scaled) % 100
    return "$whole.${part.toString().padStart(2, '0')}"
}
