package com.pixeltown.sim

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Exports a real run as a sprite sheet of yearly frames plus a timeline of what happened, so the
 * simulation can be watched in a browser on a phone.
 *
 * This is a *recording* of the actual simulation — the frames come from the same [FrameRenderer]
 * the Android app uses — not a second implementation of the game. Test-only, like the other
 * exporters: `java.awt` must never reach the app.
 *
 * A tool rather than a test, so it is skipped unless asked for — exporting two centuries costs a
 * minute and a half, which does not belong in every suite run:
 *
 * ```
 * PIXELTOWN_WEB_OUT=/tmp/web ./gradlew -Ppixeltown.simOnly=true :sim:test --tests '*WebExporter*'
 * ```
 */
class WebExporter {

    private val years = 200
    private val columns = 16

    @Test
    fun `export a run for the web viewer`() {
        val destination = System.getenv("PIXELTOWN_WEB_OUT")
        assumeTrue(destination != null, "set PIXELTOWN_WEB_OUT to export a run for the web viewer")
        val outputDir = File(destination!!).apply { mkdirs() }

        val sim = Simulation.newRun(1L, TraitAllocation.of(3, 4, 3, 4, 8))
        val renderer = FrameRenderer(sim.world)
        val pixels = IntArray(sim.world.cellCount)

        val rows = (years + columns - 1) / columns
        val sheet = BufferedImage(columns * sim.world.width, rows * sim.world.height, BufferedImage.TYPE_INT_RGB)
        val timeline = StringBuilder()
        timeline.append("""{"width":${sim.world.width},"height":${sim.world.height},""")
        timeline.append(""""columns":$columns,"years":$years,"civs":[""")
        timeline.append(sim.civs.joinToString(",") { civ ->
            """{"name":"${civ.name}","personality":"${civ.personality}","colour":"${hex(Palette.civColor(civ.id))}"}"""
        })
        timeline.append("""],"frames":[""")

        for (year in 0 until years) {
            if (sim.endState == null) sim.run(GameConfig.Time.DAYS_PER_YEAR)
            renderer.render(sim, pixels, ownershipTint = 0.18f)

            val col = year % columns
            val row = year / columns
            for (y in 0 until sim.world.height) {
                for (x in 0 until sim.world.width) {
                    sheet.setRGB(col * sim.world.width + x, row * sim.world.height + y, pixels[y * sim.world.width + x])
                }
            }

            val player = sim.civ(0)
            val premier = sim.premierOf(0)
            val events = sim.chronicle.recent(400)
                .filter { it.tick > (year.toLong()) * GameConfig.Time.DAYS_PER_YEAR - GameConfig.Time.DAYS_PER_YEAR }
                .filter { it.kind in NOTABLE }
                .takeLast(6)

            if (year > 0) timeline.append(",")
            timeline.append("{")
            timeline.append(""""y":$year,""")
            timeline.append(""""pops":[${sim.civs.joinToString(",") { it.population.toString() }}],""")
            timeline.append(""""food":${player[Resource.FOOD].toInt()},""")
            timeline.append(""""wood":${player[Resource.WOOD].toInt()},""")
            timeline.append(""""stone":${player[Resource.STONE].toInt()},""")
            timeline.append(""""knowledge":${player[Resource.KNOWLEDGE].toInt()},""")
            timeline.append(""""wealth":${player[Resource.WEALTH].toInt()},""")
            timeline.append(""""tier":${player.techTier},""")
            timeline.append(""""unrest":${"%.2f".format(player.unrest)},""")
            timeline.append(""""buildings":${sim.buildingsOf(0).count { it.isComplete }},""")
            timeline.append(""""armies":${sim.armiesInField.size},""")
            timeline.append(""""premier":${premier?.let { "\"${escape(it.name)}\"" } ?: "null"},""")
            timeline.append(""""agenda":${premier?.let { "\"${it.agenda.dominant}\"" } ?: "null"},""")
            timeline.append(""""temperament":${premier?.let { "\"${it.temperament}\"" } ?: "null"},""")
            timeline.append(""""tension":[${(1 until sim.civs.size).joinToString(",") {
                "%.2f".format(sim.relations.tensionBetween(0, it))
            }}],""")
            timeline.append(""""war":[${(1 until sim.civs.size).joinToString(",") {
                sim.relations.atWar(0, it).toString()
            }}],""")
            timeline.append(""""events":[${events.joinToString(",") { e ->
                """{"k":"${e.kind}","c":${e.civId},"d":"${escape(e.detail ?: "")}"}"""
            }}]""")
            timeline.append("}")
        }

        timeline.append("""],"endState":${sim.endState?.let { "\"$it\"" } ?: "null"}}""")

        ImageIO.write(sheet, "png", File(outputDir, "frames.png"))
        File(outputDir, "timeline.json").writeText(timeline.toString())
        println("WEBEXPORT frames=${File(outputDir, "frames.png").length() / 1024}KB timeline=${File(outputDir, "timeline.json").length() / 1024}KB end=${sim.endState} pop=${sim.populationOf(0)}")
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private fun escape(text: String) = text.replace("\\", "").replace("\"", "'")

    private val NOTABLE = setOf(
        ChronicleEventKind.ELECTION, ChronicleEventKind.WAR_DECLARED, ChronicleEventKind.PEACE,
        ChronicleEventKind.RAID, ChronicleEventKind.COUP, ChronicleEventKind.TECH_TIER,
        ChronicleEventKind.RUN_ENDED, ChronicleEventKind.BUILDING_COMPLETED,
    )
}
