package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Time
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SimClockTest {

    @Test
    fun `paused clock never ticks`() {
        val clock = SimClock(speedMultiplier = 0)
        var ticks = 0
        clock.advance(10.0) { ticks++ }
        assertEquals(0, ticks)
        assertEquals(0L, clock.tick)
    }

    @Test
    fun `one second at 1x runs the base tick rate`() {
        val clock = SimClock(speedMultiplier = 1)
        var ticks = 0
        clock.advance(1.0) { ticks++ }
        assertEquals(Time.BASE_TICKS_PER_SECOND.toInt(), ticks)
    }

    @Test
    fun `fractional frames accumulate without losing or duplicating ticks`() {
        val clock = SimClock(speedMultiplier = 1)
        var ticks = 0
        // 600 frames at 1/60s is exactly 10 simulated seconds.
        repeat(600) { clock.advance(1.0 / 60.0) { ticks++ } }
        assertEquals((10 * Time.BASE_TICKS_PER_SECOND).toInt(), ticks)
        assertEquals(ticks.toLong(), clock.tick)
    }

    @Test
    fun `catch-up is capped per frame and the remainder is carried`() {
        val clock = SimClock(speedMultiplier = 100)
        var ticks = 0
        // A 10s stall at 100x asks for 10,000 ticks; only the cap may run this frame.
        clock.advance(10.0) { ticks++ }
        assertEquals(Time.MAX_TICKS_PER_FRAME, ticks)

        var more = 0
        clock.advance(0.0) { more++ }
        assertEquals(0, more, "a zero-length frame must not drain the carry")
    }

    @Test
    fun `tick count is identical across speed settings for the same simulated duration`() {
        // 50,000 ticks reached at each speed: the tick sequence must be byte-identical.
        val target = 50_000L
        val sequences = Time.SPEED_MULTIPLIERS.filter { it > 0 }.map { speed ->
            val clock = SimClock(speedMultiplier = speed)
            val seen = ArrayList<Long>(target.toInt())
            val frame = 1.0 / 60.0
            while (clock.tick < target) {
                clock.advance(frame) { t -> if (t <= target) seen.add(t) }
            }
            seen
        }
        val first = sequences.first()
        assertTrue(first.size >= target)
        for (other in sequences.drop(1)) {
            assertEquals(first.take(target.toInt()), other.take(target.toInt()))
        }
    }

    @Test
    fun `calendar derives from the tick count`() {
        val clock = SimClock(speedMultiplier = 1)
        clock.runTicks(Time.DAYS_PER_YEAR.toLong() + Time.DAYS_PER_SEASON * 2L) {}
        assertEquals(1, clock.year)
        assertEquals(Season.AUTUMN, clock.season)
    }
}
