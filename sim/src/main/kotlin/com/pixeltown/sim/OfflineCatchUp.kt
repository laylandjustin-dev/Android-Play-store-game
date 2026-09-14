package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Meta
import kotlin.math.min

/**
 * What happened while the player was away.
 *
 * This is the single most important retention feature in the design, so it is built from the
 * Chronicle's structured events rather than from a diff of before-and-after numbers: the report
 * should read like a chronicle, not a receipt.
 */
data class ReturnReport(
    val daysElapsed: Long,
    val yearsElapsed: Int,
    val realSecondsAway: Long,
    /** True when the player was away longer than their offline cap allows. */
    val cappedAt: Int?,
    val startYear: Int,
    val endYear: Int,
    val populationBefore: Int,
    val populationAfter: Int,
    val births: Int,
    val deaths: Int,
    val deathsByCause: Map<DeathCause, Int>,
    val elections: List<ElectionResult>,
    val buildingsCompleted: List<String>,
    val warsDeclared: List<String>,
    val raids: Int,
    val techTiersGained: Int,
    val endState: EndState?,
    /** Notable moments, already ordered, for the narrative templates in the UI. */
    val highlights: List<ChronicleEvent>,
) {
    val populationChange: Int get() = populationAfter - populationBefore
}

/**
 * Fast-forwards a run to catch up with real time.
 *
 * Crucially this runs *the same [Simulation.step] as live play* — there is no approximation path.
 * The design is explicit about that, and it is also the only way the guarantee holds that a save
 * resumed after eight hours is identical to one that was watched for eight hours.
 */
object OfflineCatchUp {

    /**
     * Ticks to run for a given absence. Capped by the player's entitlement, and never negative
     * (a clock that has gone backwards — a timezone change, a user fiddling with settings —
     * must not rewind or stall the world).
     */
    fun ticksFor(realSecondsAway: Long, capHours: Int): Long {
        if (realSecondsAway <= 0) return 0
        val cappedSeconds = min(realSecondsAway, capHours.toLong() * 3_600L)
        return (cappedSeconds * Meta.OFFLINE_TICKS_PER_REAL_SECOND).toLong()
    }

    /** True if the absence was longer than the cap, so the player lost some of it. */
    fun wasCapped(realSecondsAway: Long, capHours: Int): Boolean =
        realSecondsAway > capHours.toLong() * 3_600L

    /**
     * Advances [simulation] by the elapsed time and returns what happened.
     *
     * [nowEpochMillis] and the save's own timestamp are the only wall-clock values the simulation
     * ever sees; everything downstream is in ticks.
     */
    fun resume(
        simulation: Simulation,
        savedAtEpochMillis: Long,
        nowEpochMillis: Long,
        capHours: Int = simulation.config.offlineCapHours,
    ): ReturnReport {
        val secondsAway = ((nowEpochMillis - savedAtEpochMillis) / 1000L).coerceAtLeast(0L)
        return advance(simulation, ticksFor(secondsAway, capHours), secondsAway, capHours)
    }

    /** Runs a known number of ticks and reports on them. Used by the tests and the time-warp product. */
    fun advance(
        simulation: Simulation,
        ticks: Long,
        realSecondsAway: Long = 0L,
        capHours: Int = simulation.config.offlineCapHours,
    ): ReturnReport {
        val player = simulation.civ(GameConfig.World.PLAYER_CIV_ID)
        val startDay = simulation.day
        val startYear = simulation.year
        val populationBefore = player.population
        val techBefore = player.techTier
        val electionsBefore = simulation.elections.size

        simulation.run(ticks.toInt())

        val events = simulation.chronicle.since(startDay)
        val deathEvents = events.filter { it.kind == ChronicleEventKind.DEATH && it.civId == player.id }

        return ReturnReport(
            daysElapsed = simulation.day - startDay,
            yearsElapsed = simulation.year - startYear,
            realSecondsAway = realSecondsAway,
            cappedAt = if (wasCapped(realSecondsAway, capHours)) capHours else null,
            startYear = startYear,
            endYear = simulation.year,
            populationBefore = populationBefore,
            populationAfter = player.population,
            births = events.count { it.kind == ChronicleEventKind.BIRTH && it.civId == player.id },
            deaths = deathEvents.size,
            deathsByCause = deathEvents.mapNotNull { it.deathCause }.groupingBy { it }.eachCount(),
            elections = simulation.elections.drop(electionsBefore).filter { it.civId == player.id },
            buildingsCompleted = events
                .filter { it.kind == ChronicleEventKind.BUILDING_COMPLETED && it.civId == player.id }
                .mapNotNull { it.detail },
            warsDeclared = events
                .filter { it.kind == ChronicleEventKind.WAR_DECLARED }
                .mapNotNull { it.detail },
            raids = events.count { it.kind == ChronicleEventKind.RAID },
            techTiersGained = player.techTier - techBefore,
            endState = simulation.endState,
            highlights = highlightsOf(events),
        )
    }

    /**
     * The events worth telling someone about, newest last. Routine births and deaths are left out
     * — a report that lists every death is the receipt the design warns against.
     */
    private fun highlightsOf(events: List<ChronicleEvent>): List<ChronicleEvent> {
        val notable = events.filter { it.kind in NOTABLE }
        return if (notable.size <= Meta.RETURN_REPORT_HIGHLIGHTS) {
            notable
        } else {
            notable.takeLast(Meta.RETURN_REPORT_HIGHLIGHTS)
        }
    }

    private val NOTABLE = setOf(
        ChronicleEventKind.ELECTION,
        ChronicleEventKind.WAR_DECLARED,
        ChronicleEventKind.PEACE,
        ChronicleEventKind.RAID,
        ChronicleEventKind.COUP,
        ChronicleEventKind.TECH_TIER,
        ChronicleEventKind.DISASTER,
        ChronicleEventKind.RUN_ENDED,
    )
}
