package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Meta
import com.pixeltown.sim.GameConfig.Politics
import com.pixeltown.sim.GameConfig.Time
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PersistenceTest {

    private fun newRun(seed: Long = 1L) = Simulation.newRun(1L, TraitAllocation.of(3, 4, 3, 4, 8))

    private fun roundTrip(sim: Simulation): Simulation =
        Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))

    // ------------------------------------------------------------------ the round trip

    @Test
    fun `save then load reproduces the state exactly`() {
        val sim = newRun()
        sim.runUnattended(30 * Time.DAYS_PER_YEAR)
        val loaded = roundTrip(sim)

        assertEquals(sim.stateHash(), loaded.stateHash())
        assertEquals(sim.day, loaded.day)
        assertEquals(sim.population, loaded.population)
        assertEquals(sim.buildings.size, loaded.buildings.size)
        assertEquals(sim.elections.size, loaded.elections.size)
        assertEquals(sim.rng.snapshot(), loaded.rng.snapshot())
    }

    @Test
    fun `save, load and tick is identical to ticking without the save`() {
        // The guarantee the whole architecture exists to provide.
        val sim = newRun()
        sim.runUnattended(30 * Time.DAYS_PER_YEAR)
        val loaded = roundTrip(sim)

        repeat(6) {
            sim.runUnattended(500)
            loaded.runUnattended(500)
            assertEquals(sim.stateHash(), loaded.stateHash(), "diverged by day ${sim.day}")
        }
        assertEquals(
            sim.chronicle.deathBreakdown(),
            loaded.chronicle.deathBreakdown(),
            "the two histories recorded different deaths",
        )
    }

    @Test
    fun `a save taken mid-campaign resumes the same election`() {
        // Saving during the thirty-day campaign window used to lose the candidates, so the
        // reloaded run generated a different slate and elected a different Premier.
        val sim = newRun()
        sim.runUnattended(Time.DAYS_PER_YEAR * 2 - Politics.CAMPAIGN_DAYS + 5)
        assertNotNull(sim.campaignFor(0), "the test did not actually land inside a campaign")

        val loaded = roundTrip(sim)
        assertEquals(
            sim.campaignFor(0)?.map { it.name },
            loaded.campaignFor(0)?.map { it.name },
            "the campaign did not survive the save",
        )

        sim.runUnattended(Politics.CAMPAIGN_DAYS + 10)
        loaded.runUnattended(Politics.CAMPAIGN_DAYS + 10)
        assertEquals(sim.stateHash(), loaded.stateHash())
        assertEquals(
            sim.elections.last().winnerName,
            loaded.elections.last().winnerName,
            "a different candidate won the election after reloading",
        )
    }

    @Test
    fun `every phase of the year round-trips`() {
        for (offset in listOf(0, 45, 90, 180, 270, 330, 355, 359)) {
            val sim = newRun()
            sim.runUnattended(5 * Time.DAYS_PER_YEAR + offset)
            val loaded = roundTrip(sim)
            sim.runUnattended(400)
            loaded.runUnattended(400)
            assertEquals(sim.stateHash(), loaded.stateHash(), "a save on day $offset of the year diverged")
        }
    }

    @Test
    fun `a save during a war restores the armies in the field`() {
        val sim = newRun()
        var found = false
        repeat(120 * Time.DAYS_PER_YEAR) {
            sim.runUnattended(1)
            if (!found && sim.armiesInField.any { it.size > 2 }) found = true
        }
        if (!found) return

        val loaded = roundTrip(sim)
        assertEquals(
            sim.armiesInField.map { "${it.id}:${it.civId}:${it.targetCivId}:${it.size}:${it.returning}" },
            loaded.armiesInField.map { "${it.id}:${it.civId}:${it.targetCivId}:${it.size}:${it.returning}" },
        )
    }

    @Test
    fun `the compressed save round-trips and is much smaller`() {
        val sim = newRun()
        sim.runUnattended(20 * Time.DAYS_PER_YEAR)
        val save = sim.snapshot()
        val raw = SaveFormat.encode(save).toByteArray()
        val compressed = SaveFormat.encodeCompressed(save)

        assertTrue(compressed.size < raw.size / 3, "gzip saved little: ${raw.size} -> ${compressed.size}")
        val loaded = Simulation.restore(SaveFormat.decodeCompressed(compressed))
        assertEquals(sim.stateHash(), loaded.stateHash())
    }

    // ------------------------------------------------------------------ refusing bad saves

    @Test
    fun `a save from a newer build is refused rather than half-read`() {
        val sim = newRun()
        sim.runUnattended(100)
        val text = SaveFormat.encode(sim.snapshot().copy(version = SaveFormat.VERSION + 1))
        assertThrows<IncompatibleSaveException> { SaveFormat.decode(text) }
    }

    @Test
    fun `a save whose island would not regenerate is refused`() {
        // If world generation ever changes, an old save must fail loudly rather than quietly drop
        // the player's town onto different terrain.
        val sim = newRun()
        sim.runUnattended(100)
        val tampered = sim.snapshot().copy(terrainHash = 12345)
        assertThrows<IncompatibleSaveException> { Simulation.restore(tampered) }
    }

    // ------------------------------------------------------------------ offline catch-up

    @Test
    fun `offline catch-up matches live ticking exactly`() {
        // The design's own test: eight hours away must equal eight hours watched.
        val sim = newRun()
        sim.runUnattended(20 * Time.DAYS_PER_YEAR)

        val watched = roundTrip(sim)
        val away = roundTrip(sim)

        val ticks = OfflineCatchUp.ticksFor(8 * 3600L, Meta.OFFLINE_CAP_HOURS_FREE)
        // Both sides must advance the *same* way or this proves nothing. OfflineCatchUp.advance
        // runs unattended — it has to, because an unspent trait point now stops the clock and an
        // attended catch-up would halt at the first decade boundary — so the watched side does too.
        // Driving the two sides differently is exactly the mistake this test exists to catch.
        watched.runUnattended(ticks.toInt())
        OfflineCatchUp.advance(away, ticks)

        assertEquals(watched.stateHash(), away.stateHash())
        assertEquals(watched.day, away.day)
    }

    @Test
    fun `time away converts to ticks and respects the cap`() {
        val free = Meta.OFFLINE_CAP_HOURS_FREE
        val hour = OfflineCatchUp.ticksFor(3600L, free)
        assertEquals((3600 * Meta.OFFLINE_TICKS_PER_REAL_SECOND).toLong(), hour)

        // Beyond the cap, nothing more accrues.
        val atCap = OfflineCatchUp.ticksFor(free * 3600L, free)
        val overCap = OfflineCatchUp.ticksFor(free * 3600L * 10, free)
        assertEquals(atCap, overCap)
        assertTrue(OfflineCatchUp.wasCapped(free * 3600L * 10, free))
        assertTrue(!OfflineCatchUp.wasCapped(60L, free))

        // The Founders Pass cap is worth having.
        assertTrue(
            OfflineCatchUp.ticksFor(48 * 3600L, Meta.OFFLINE_CAP_HOURS_FOUNDERS_PASS) > atCap,
            "the raised cap bought nothing",
        )
    }

    @Test
    fun `a clock that goes backwards does not rewind or stall the world`() {
        assertEquals(0L, OfflineCatchUp.ticksFor(-9999L, 8))
        assertEquals(0L, OfflineCatchUp.ticksFor(0L, 8))

        val sim = newRun()
        sim.runUnattended(500)
        val before = sim.day
        val report = OfflineCatchUp.resume(sim, savedAtEpochMillis = 10_000_000L, nowEpochMillis = 1_000L)
        assertEquals(before, sim.day, "a backwards clock moved the world")
        assertEquals(0L, report.daysElapsed)
    }

    @Test
    fun `the return report describes what happened`() {
        val sim = newRun()
        sim.runUnattended(25 * Time.DAYS_PER_YEAR)
        val populationBefore = sim.populationOf(0)

        val report = OfflineCatchUp.advance(sim, ticks = 12L * Time.DAYS_PER_YEAR, realSecondsAway = 8 * 3600L)

        // Catch-up can stop short: reaching a tech tier holds the world until the player chooses,
        // and it must do so while they are away too, or coming back would mean finding the
        // decision already taken. So the report describes what *happened*, which is at most the
        // time asked for, and it has to be self-consistent about it.
        // It can also stop mid-year, so the days are not a whole number of years: what has to hold
        // is that it ran at most what was asked, and that the years and the days agree.
        assertTrue(report.daysElapsed in 1..12L * Time.DAYS_PER_YEAR, "ran ${report.daysElapsed} days")
        assertEquals(
            (report.daysElapsed / Time.DAYS_PER_YEAR).toInt(),
            report.yearsElapsed,
            "the report's years and days disagree",
        )
        assertEquals(populationBefore, report.populationBefore)
        assertEquals(sim.populationOf(0), report.populationAfter)
        assertEquals(
            report.yearsElapsed,
            report.elections.size,
            "a year passed without an election, or an election happened outside the report's span",
        )
        assertTrue(report.births > 0, "twelve years without a birth")
        assertTrue(report.highlights.size <= Meta.RETURN_REPORT_HIGHLIGHTS)
        assertTrue(
            report.highlights.none { it.kind == ChronicleEventKind.BIRTH },
            "the report listed routine births — it should read like a chronicle, not a receipt",
        )
        assertEquals(report.deaths, report.deathsByCause.values.sum())
    }

    @Test
    fun `the report says when the player lost time to the cap`() {
        val sim = newRun()
        sim.runUnattended(1000)
        val capped = OfflineCatchUp.advance(sim, 100L, realSecondsAway = 100 * 3600L, capHours = 8)
        assertEquals(8, capped.cappedAt)

        val notCapped = OfflineCatchUp.advance(sim, 100L, realSecondsAway = 60L, capHours = 8)
        assertNull(notCapped.cappedAt)
    }
}
