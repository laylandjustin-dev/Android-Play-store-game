@file:OptIn(ExperimentalJsExport::class)

package com.pixeltown.web

import com.pixeltown.sim.Agenda
import com.pixeltown.sim.Archetype
import com.pixeltown.sim.BuildingCategory
import com.pixeltown.sim.BuildingShape
import com.pixeltown.sim.BuildingType
import com.pixeltown.sim.Citizen
import com.pixeltown.sim.ChronicleEventKind
import com.pixeltown.sim.CivColors
import com.pixeltown.sim.ColonyName
import com.pixeltown.sim.FrameRenderer
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.Legacy
import com.pixeltown.sim.LegacyUpgrade
import com.pixeltown.sim.Palette
import com.pixeltown.sim.Resource
import com.pixeltown.sim.RunConfig
import com.pixeltown.sim.SimRandom
import com.pixeltown.sim.SiteSurvey
import com.pixeltown.sim.Simulation
import com.pixeltown.sim.Trait
import com.pixeltown.sim.TechOption
import com.pixeltown.sim.TraitAllocation
import com.pixeltown.sim.TraitEffect
import com.pixeltown.sim.TraitEffects
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
    /** Defaulted, so a shell built before the sixth trait existed still constructs a run. */
    gathering: Int = GameConfig.Traits.BASE_VALUE,
    colonyName: String = ColonyName.DEFAULT,
    /** Cell the player picked on the map preview, or -1 to take the generator's choice. */
    startCell: Int = -1,
    colorIndex: Int = 0,
) {
    private val simulation = Simulation.newRun(
        RunConfig(
            seed = seed.toLong(),
            traits = TraitAllocation(speed, health, hunting, elements, farming, gathering),
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

    /** The player offers a neighbour a trade, out of season. False if nothing came of it. */
    fun offerTrade(civId: Int): Boolean =
        simulation.offerTrade(GameConfig.World.PLAYER_CIV_ID, civId)

    /** The player forces a war their town did not ask for. The most expensive lever on the board. */
    fun forceWar(civId: Int): Boolean =
        simulation.forceWar(GameConfig.World.PLAYER_CIV_ID, civId)

    /** Every pair's standing: tension, posture, trade and war — the whole matrix, not just ours. */
    fun relations(): String = simulation.relationReports().joinToString(",", "[", "]") { r ->
        "{\"a\":${r.civA},\"b\":${r.civB}" +
            ",\"nameA\":\"${escape(r.nameA)}\"" +
            ",\"nameB\":\"${escape(r.nameB)}\"" +
            ",\"tension\":${round2(r.tension)}" +
            ",\"posture\":\"${r.posture}\"" +
            ",\"atWar\":${r.atWar}" +
            ",\"trades\":${r.trades}" +
            ",\"alive\":${r.bothAlive}" +
            ",\"mine\":${r.civA == GameConfig.World.PLAYER_CIV_ID || r.civB == GameConfig.World.PLAYER_CIV_ID}}"
    }

    /** True while the player's election is waiting to be looked at. */
    val electionPending: Boolean get() = simulation.electionPending

    /**
     * Dismisses the election pause, whether the player endorsed anybody or not.
     *
     * Skipping is a legitimate answer to an election and costs nothing — the whole point of the
     * pause is that the slate can be *read*, not that it must be acted on.
     */
    fun acknowledgeElection(): Boolean = simulation.acknowledgeElection()

    /**
     * What one more point in [trait] would change, with percentages.
     *
     * Every row is computed by building the allocation one point higher and reading the same
     * `TraitAllocation` the simulation runs on, so this can never drift from the game the way a
     * hand-written blurb does.
     */
    fun traitEffects(trait: String): String {
        val which = Trait.entries.firstOrNull { it.name.equals(trait, ignoreCase = true) }
            ?: return "[]"
        return effectsJson(TraitEffects.of(simulation.civ(GameConfig.World.PLAYER_CIV_ID).traits, which))
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
        sb.append(",\"electionPending\":").append(simulation.electionPending)
        sb.append(",\"endorsed\":").append(
            simulation.endorsementFor(GameConfig.World.PLAYER_CIV_ID)?.toString() ?: "null",
        )
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
        // Where a town's effort actually goes. "1,240 people" is a number a player can read and not
        // one they can act on.
        sb.append(",\"jobs\":").append(
            shareJson(simulation.jobBreakdown(GameConfig.World.PLAYER_CIV_ID).entries.map {
                it.key.label to it.value
            }),
        )
        sb.append(",\"units\":").append(
            shareJson(simulation.unitBreakdown(GameConfig.World.PLAYER_CIV_ID).entries.map {
                it.key.label to it.value
            }),
        )
        sb.append(",\"buildings\":").append(simulation.buildingsOf(0).count { it.isComplete })
        sb.append(",\"armies\":").append(simulation.armiesInField.count { it.civId == 0 })
        sb.append(",\"end\":").append(simulation.endState?.let { "\"${it.name}\"" } ?: "null")

        simulation.summary()?.let { summary ->
            sb.append(",\"summary\":{\"years\":").append(summary.yearsSurvived)
            sb.append(",\"peak\":").append(summary.peakPopulation)
            sb.append(",\"tier\":").append(summary.techTier)
            sb.append(",\"points\":").append(summary.chroniclePointsEarned)
            // The breakdown: what the player actually decided over the run, not just how it scored.
            sb.append(",\"opening\":").append(traitsJson(summary.openingTraits))
            sb.append(",\"final\":").append(traitsJson(summary.finalTraits))
            sb.append(",\"growth\":").append(
                shareJson(summary.traitGrowthShare().map { it.first.name.lowercase() to it.second }),
            )
            sb.append(",\"growthCounts\":").append(
                shareJson(Trait.entries.mapNotNull { t ->
                    summary.traitGrowth[t]?.let { t.name.lowercase() to it }
                }),
            )
            sb.append(",\"autoSpent\":").append(summary.traitPointsAutoSpent)
            sb.append(",\"unspent\":").append(summary.traitPointsUnspent)
            sb.append(",\"attention\":").append(summary.attentionShare)
            sb.append(",\"techs\":").append(
                summary.techsChosen.joinToString(",", "[", "]") { "\"${escape(it)}\"" },
            )
            sb.append(",\"built\":").append(
                shareJson(summary.buildingShare().map { it.first.name.lowercase() to it.second }),
            )
            sb.append(",\"builtCounts\":").append(
                shareJson(BuildingCategory.entries.mapNotNull { c ->
                    summary.buildingsByCategory[c]?.let { c.name.lowercase() to it }
                }),
            )
            sb.append(",\"charter\":").append(
                summary.finalCharter?.let { "\"${it.name.lowercase()}\"" } ?: "null",
            )
            sb.append(",\"platforms\":").append(
                shareJson(summary.platformShare().map { it.first.name.lowercase() to it.second }),
            )
            sb.append(",\"terms\":").append(summary.termsServed)
            sb.append(",\"coups\":").append(summary.coups)
            sb.append(",\"deaths\":").append(
                shareJson(summary.deathShare().map { it.first.name.lowercase() to it.second }),
            )
            sb.append(",\"births\":").append(summary.totalBirths)
            sb.append(",\"died\":").append(summary.totalDeaths)
            sb.append(",\"wars\":").append(summary.warsFought)
            sb.append(",\"raids\":").append(summary.raidsSuffered)
            sb.append(",\"rivalsLeft\":").append(summary.rivalsSurviving)
            sb.append(",\"rivalsGone\":").append(summary.rivalsExtinct)
            // What everyone else was built for and how it went for them — without this the player's
            // own numbers have no yardstick.
            sb.append(",\"rivalDetail\":[")
            for ((i, rival) in summary.rivals.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append("{\"name\":\"").append(escape(rival.name)).append('"')
                sb.append(",\"personality\":\"").append(rival.personality.name.lowercase()).append('"')
                sb.append(",\"archetype\":\"").append(escape(Archetype.of(rival.traits).label)).append('"')
                sb.append(",\"traits\":").append(traitsJson(rival.traits))
                sb.append(",\"pop\":").append(rival.population)
                sb.append(",\"peak\":").append(rival.peakPopulation)
                sb.append(",\"tier\":").append(rival.techTier)
                sb.append(",\"buildings\":").append(rival.buildings)
                sb.append(",\"extinct\":").append(rival.extinct)
                sb.append(",\"atWar\":").append(rival.atWarWithPlayer)
                sb.append(",\"wars\":").append(rival.warsWithPlayer)
                sb.append(",\"trades\":").append(rival.tradesWithPlayer)
                sb.append('}')
            }
            sb.append(']')
            // The resource ledger: everything the town ever made and everything it ever spent.
            sb.append(",\"produced\":").append(
                shareJson(Resource.entries.mapNotNull { r ->
                    summary.produced[r]?.let { r.name.lowercase() to it.toInt() }
                }),
            )
            sb.append(",\"consumed\":").append(
                shareJson(Resource.entries.mapNotNull { r ->
                    summary.consumed[r]?.let { r.name.lowercase() to it.toInt() }
                }),
            )
            sb.append(",\"spoiled\":").append(summary.spoiled.toInt())
            sb.append('}')
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
            sb.append("{\"civId\":").append(report.civId)
            sb.append(",\"name\":\"").append(escape(report.name)).append('"')
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

    private fun escape(text: String): String = escapeJson(text)

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
    private fun traitsJson(traits: TraitAllocation): String =
        Trait.entries.joinToString(",", "{", "}") { "\"${it.name.lowercase()}\":${traits[it]}" }

    /** An ordered list of label/number pairs. A list, not an object, because the order is the point. */
    private fun shareJson(pairs: List<Pair<String, Int>>): String =
        pairs.joinToString(",", "[", "]") { "{\"k\":\"${it.first}\",\"v\":${it.second}}" }
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
     * The best site score on this map, computed once. Ratings are a share of it, so "prime" means
     * prime *for this island* rather than against some absolute the player cannot see.
     */
    private val bestScore: Double = SiteSurvey.bestScore(generated.world, legal)

    /**
     * What the land around [cell] is worth — the answer to "why did my colony starve there?" given
     * before landing rather than after. [traits] is the allocation the player has chosen so far, so
     * the "feeds about" figure is theirs and not a generic one.
     */
    fun survey(
        cell: Int,
        speed: Int,
        health: Int,
        hunting: Int,
        elements: Int,
        farming: Int,
        gathering: Int,
    ): String {
        if (cell < 0 || cell >= generated.world.cellCount) return "null"
        val traits = TraitAllocation(speed, health, hunting, elements, farming, gathering)
        val s = SiteSurvey.of(generated.world, cell, isLegal(cell), bestScore)
        return "{\"legal\":${s.legal}" +
            ",\"rating\":\"${s.rating}\"" +
            ",\"share\":${(s.shareOfBest * 100).toInt()}" +
            ",\"farmland\":${s.farmland.toInt()}" +
            ",\"game\":${s.game.toInt()}" +
            ",\"timber\":${s.timber}" +
            ",\"stone\":${s.stone}" +
            ",\"water\":${s.freshWater}" +
            ",\"feeds\":${s.feedsAbout(traits)}" +
            ",\"settlers\":${GameConfig.World.STARTING_SETTLERS}}"
    }

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

/**
 * The Chronicle: points earned by finished runs, and the permanent upgrades they buy.
 *
 * `:sim` has had this since M6 and the playable build could not reach a line of it — a run computed
 * its Chronicle points, printed them on the end screen, and threw them away. So the honest answer to
 * "what are Chronicle points and how do I spend them" was *nothing, and you can't*. This is the
 * façade that makes them real: the shell keeps the encoded state in `localStorage`, hands it back on
 * the next visit, and a run started from it is a run with the upgrades applied.
 *
 * The state is a plain string rather than an object graph so the shell can store it without knowing
 * anything about upgrades, and so adding an upgrade cannot break a stored legacy: unknown names are
 * skipped on the way in, exactly as unknown techs are (AD-57's lesson).
 */
@JsExport
class WebChronicle(encoded: String = "") {

    private val legacy: Legacy = decode(encoded)

    val points: Int get() = legacy.chroniclePoints
    val runsPlayed: Int get() = legacy.runsPlayed
    val bestYears: Int get() = legacy.bestYears

    /** Every upgrade, with what it costs, what it is worth, and whether it can be bought now. */
    fun upgrades(): String = LegacyUpgrade.entries.joinToString(",", "[", "]") { upgrade ->
        val cost = legacy.costOf(upgrade)
        val level = legacy.levelOf(upgrade)
        "{\"id\":\"${upgrade.name}\"" +
            ",\"label\":\"${escapeJson(labelOf(upgrade))}\"" +
            ",\"blurb\":\"${escapeJson(blurbOf(upgrade))}\"" +
            ",\"level\":$level" +
            ",\"max\":${upgrade.maxLevel}" +
            ",\"cost\":${cost ?: -1}" +
            ",\"affordable\":${cost != null && cost <= legacy.chroniclePoints}}"
    }

    /** Buys one level. False when it is maxed or unaffordable — the caller's request was not legal. */
    fun buy(id: String): Boolean {
        val upgrade = LegacyUpgrade.entries.firstOrNull { it.name == id } ?: return false
        return legacy.buy(upgrade)
    }

    /** Banks a finished run's points and its record. Returns what the run earned. */
    fun recordRun(points: Int, yearsSurvived: Int): Int {
        legacy.grant(points)
        legacy.recordRun(yearsSurvived)
        return points
    }

    /** The opening allocation budget this legacy grants, over the standard ten. */
    val bonusAllocationPoints: Int get() = legacy.bonusAllocationPoints

    val startingSettlers: Int get() = legacy.startingSettlers

    /** For `localStorage`. Deliberately not JSON: the shell never reads inside it. */
    fun encode(): String = buildString {
        append(legacy.chroniclePoints).append('|')
        append(legacy.runsPlayed).append('|')
        append(legacy.bestYears)
        for (upgrade in LegacyUpgrade.entries) {
            val level = legacy.levelOf(upgrade)
            if (level > 0) append('|').append(upgrade.name).append(':').append(level)
        }
    }

    private companion object {
        fun decode(encoded: String): Legacy {
            if (encoded.isBlank()) return Legacy()
            val parts = encoded.split('|')
            val levels = HashMap<LegacyUpgrade, Int>()
            for (part in parts.drop(3)) {
                val name = part.substringBefore(':')
                val level = part.substringAfter(':', "").toIntOrNull() ?: continue
                // An upgrade this build does not know is dropped rather than throwing: a stored
                // legacy must survive the game gaining and losing upgrades.
                LegacyUpgrade.entries.firstOrNull { it.name == name }?.let { levels[it] = level }
            }
            return Legacy.restore(
                parts.getOrNull(0)?.toIntOrNull() ?: 0,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.toIntOrNull() ?: 0,
                levels,
            )
        }

        fun labelOf(upgrade: LegacyUpgrade): String = when (upgrade) {
            LegacyUpgrade.ALLOCATION_POINT -> "Deeper roots"
            LegacyUpgrade.EXTRA_SETTLERS -> "A larger landing"
            LegacyUpgrade.STARTING_BUILDING -> "Ready-made"
            LegacyUpgrade.FAST_LEARNERS -> "Quick hands"
            LegacyUpgrade.STANDING -> "Old families"
            LegacyUpgrade.FERTILITY_FLOOR -> "Deep soil"
            LegacyUpgrade.DIPLOMACY -> "Good name"
        }

        fun blurbOf(upgrade: LegacyUpgrade): String = when (upgrade) {
            LegacyUpgrade.ALLOCATION_POINT ->
                "+1 point to spend on the opening screen. Capped at +4 however much you spend."
            LegacyUpgrade.EXTRA_SETTLERS -> "+5 settlers step off the boat."
            LegacyUpgrade.STARTING_BUILDING -> "One building already standing on day one."
            LegacyUpgrade.FAST_LEARNERS -> "Skill matures faster, so a young colony is less helpless."
            LegacyUpgrade.STANDING -> "Citizens start with influence, so the council is contested sooner."
            LegacyUpgrade.FERTILITY_FLOOR -> "A floor under soil fertility: land cannot be exhausted entirely."
            LegacyUpgrade.DIPLOMACY -> "The rivals begin better disposed toward you."
        }
    }
}

/** Trait-effect rows as JSON. One implementation, so the two screens cannot disagree. */
private fun effectsJson(rows: List<TraitEffect>): String =
    rows.joinToString(",", "[", "]") { row ->
        // Full precision, not `fixed()`: soil recovery is ~0.003 a day, and rounding to two places
        // before serialising turned an honest "+16%" row into "0.00 -> 0.00". Formatting is the
        // reader's job and belongs at the point of display.
        "{\"label\":\"${escapeJson(row.label)}\"" +
            ",\"from\":${row.from}" +
            ",\"to\":${row.to}" +
            ",\"unit\":\"${row.unit.name.lowercase()}\"" +
            ",\"pct\":${row.percentChange}" +
            ",\"lowerIsBetter\":${row.lowerIsBetter}" +
            ",\"changes\":${row.changes}}"
    }

/** Makes a string safe to drop inside the hand-rolled JSON these façades emit. */
private fun escapeJson(text: String): String = text.replace("\\", "").replace("\"", "'")

/**
 * The building index: every structure in the game, read out of the catalogue itself.
 *
 * Generated rather than written, which is the whole point of it. A hand-maintained table of twenty
 * buildings' costs and effects is wrong the first time a number is tuned, and silently — so this
 * walks `GameConfig.Buildings.CATALOGUE` and reports what is actually there. One line of the index
 * cannot disagree with the simulation, because there is nothing to disagree with.
 *
 * The effects are described rather than dumped: a spec carries fifteen possible fields and any one
 * building sets three or four, so listing only the non-zero ones is what makes the index readable.
 */
@JsExport
fun buildingIndex(): String = GameConfig.Buildings.CATALOGUE.joinToString(",", "[", "]") { spec ->
    val effects = ArrayList<String>()
    fun note(condition: Boolean, text: String) { if (condition) effects.add(text) }
    note(spec.housingCapacity > 0, "houses ${spec.housingCapacity}")
    note(spec.foodStorageBonus > 0.0, "+${spec.foodStorageBonus.toInt()} food storage")
    note(spec.careCapacity > 0.0, "cares for ${spec.careCapacity.toInt()}")
    note(spec.moraleBonus > 0.0, "+${pct(spec.moraleBonus)} morale")
    note(spec.influenceBonus > 0.0, "+${fixed(spec.influenceBonus)} influence a day")
    note(spec.militaryStrength > 0.0, "+${spec.militaryStrength.toInt()} military strength")
    note(spec.safetyBonus > 0.0, "+${pct(spec.safetyBonus)} safety")
    note(spec.knowledgeMultiplier > 0.0, "+${pct(spec.knowledgeMultiplier)} research")
    note(spec.farmYieldBonus > 0.0, "+${pct(spec.farmYieldBonus)} farm yield")
    note(spec.buildSpeedBonus > 0.0, "+${pct(spec.buildSpeedBonus)} build speed")
    note(spec.diseaseResistBonus > 0.0, "+${pct(spec.diseaseResistBonus)} disease resistance")
    note(spec.seasonFloor > 0.0, "a floor under the worst season")
    note(spec.foodToWealth > 0.0, "turns surplus grain into money")

    "{\"type\":\"${spec.type.name}\"" +
        ",\"label\":\"${escapeJson(labelOfBuilding(spec.type))}\"" +
        ",\"category\":\"${spec.category.name.lowercase()}\"" +
        ",\"shape\":\"${BuildingShape.of(spec.category).name.lowercase()}\"" +
        ",\"tier\":${spec.tier}" +
        ",\"footprint\":${spec.footprint}" +
        ",\"wood\":${spec.woodCost.toInt()}" +
        ",\"stone\":${spec.stoneCost.toInt()}" +
        ",\"work\":${spec.buildPointsRequired.toInt()}" +
        ",\"upkeep\":${fixed(spec.upkeepWealth)}" +
        ",\"effects\":" + effects.joinToString(",", "[", "]") { "\"${escapeJson(it)}\"" } +
        "}"
}

/** A whole-number percentage, for the index's effect lines. */
private fun pct(value: Double): String = "${(value * 100).toInt()}%"

/**
 * The name a player sees, as distinct from the enum's.
 *
 * `HOUSING` and `HUT` are accurate and graceless; a building index is read, so it gets words.
 */
private fun labelOfBuilding(type: BuildingType): String = when (type) {
    BuildingType.FIELD -> "Field"
    BuildingType.GRANARY -> "Granary"
    BuildingType.IRRIGATION -> "Irrigation"
    BuildingType.MILL -> "Mill"
    BuildingType.HUT -> "Healer's hut"
    BuildingType.CLINIC -> "Clinic"
    BuildingType.AQUEDUCT -> "Aqueduct"
    BuildingType.HOSPITAL -> "Hospital"
    BuildingType.WATCHTOWER -> "Watchtower"
    BuildingType.BARRACKS -> "Barracks"
    BuildingType.WALL -> "Wall"
    BuildingType.ARMOURY -> "Armoury"
    BuildingType.WORKSHOP -> "Workshop"
    BuildingType.LIBRARY -> "Library"
    BuildingType.ACADEMY -> "Academy"
    BuildingType.OBSERVATORY -> "Observatory"
    BuildingType.HOUSING -> "Houses"
    BuildingType.PLAZA -> "Plaza"
    BuildingType.TEMPLE -> "Temple"
    BuildingType.THEATRE -> "Theatre"
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
fun describeBuild(
    speed: Int,
    health: Int,
    hunting: Int,
    elements: Int,
    farming: Int,
    gathering: Int = GameConfig.Traits.BASE_VALUE,
): String {
    val archetype = Archetype.of(TraitAllocation(speed, health, hunting, elements, farming, gathering))

    // Every bonus is reported as the raw change to the thing its label names, so the sign means what
    // it says: "army strength +25%" and "building cost -20%" are both good news, and the reader never
    // has to know which direction the underlying multiplier runs. Read off Archetype rather than
    // written out here, so the setup screen cannot promise a bonus the simulation does not apply —
    // the same reasoning as the generated building index (AD-77) and the effect rows (AD-68).
    val bonuses = mutableListOf<String>()
    fun row(label: String, multiplier: Double) {
        if (multiplier == 1.0) return
        val pct = (multiplier - 1.0) * 100.0
        bonuses.add("{\"label\":\"${escapeJson(label)}\",\"pct\":$pct}")
    }
    row("army strength", archetype.strengthBonus)
    row("march speed", archetype.marchBonus)
    row("wall integrity", archetype.wallBonus)
    row("disease resistance", archetype.diseaseBonus)
    row("building cost", archetype.buildCostBonus)
    row("building decay", archetype.decayBonus)

    return "{\"label\":\"${archetype.label}\",\"blurb\":\"${archetype.blurb}\"," +
        "\"shape\":\"${archetype.shape.name.lowercase()}\"," +
        "\"unit\":\"${escapeJson(archetype.uniqueUnit.label)}\"," +
        "\"unique\":${archetype != Archetype.BALANCED}," +
        "\"bonuses\":[${bonuses.joinToString(",")}]}"
}

/**
 * What each trait's next point would change, for the allocation screen — before a run exists.
 *
 * Returns every trait at once, keyed by name, because the screen shows all six steppers together
 * and asking per trait would mean six crossings of the Kotlin/JS boundary per keystroke.
 */
@JsExport
fun previewTraitEffects(
    speed: Int,
    health: Int,
    hunting: Int,
    elements: Int,
    farming: Int,
    gathering: Int = GameConfig.Traits.BASE_VALUE,
): String {
    val traits = TraitAllocation(speed, health, hunting, elements, farming, gathering)
    return Trait.entries.joinToString(",", "{", "}") { trait ->
        "\"${trait.name.lowercase()}\":${effectsJson(TraitEffects.of(traits, trait))}"
    }
}

/** The derived numbers the allocation screen previews, without starting a run. */
@JsExport
fun previewTraits(
    speed: Int,
    health: Int,
    hunting: Int,
    elements: Int,
    farming: Int,
    gathering: Int = GameConfig.Traits.BASE_VALUE,
): String {
    val traits = TraitAllocation(speed, health, hunting, elements, farming, gathering)
    return "{\"work\":${fixed(traits.workMultiplier)}," +
        "\"lifespan\":${traits.lifespanYears.toInt()}," +
        "\"hunt\":${fixed(traits.huntYield)}," +
        "\"farm\":${fixed(traits.farmYield)}," +
        "\"gather\":${fixed(traits.gatherYield)}," +
        "\"build\":${fixed(traits.buildRate)}," +
        "\"fertility\":${fixed(traits.fertilityMultiplier)}," +
        "\"ration\":${fixed(traits.rationMultiplier)}," +
        "\"shelter\":${fixed(traits.elementsShelter)}," +
        "\"winter\":${fixed(traits.seasonalYieldMultiplier(1.0))}}"
}

private fun fixed(value: Double): String {
    val scaled = (value * 100).toInt()
    val whole = scaled / 100
    val part = (if (scaled < 0) -scaled else scaled) % 100
    return "$whole.${part.toString().padStart(2, '0')}"
}
