package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Time

/**
 * Fixed-step accumulator that decouples simulation rate from frame rate.
 *
 * Each frame, [advance] adds `deltaSeconds * ticksPerSecond` to an accumulator and drains it in
 * whole ticks — never partial, never skipped — so tick N is identical whether it was reached at
 * 1x or 100x. Catch-up is capped at [Time.MAX_TICKS_PER_FRAME] per call and the remainder is
 * carried, so a stalled frame can never make the accumulator spiral.
 *
 * This class owns no simulation state; it only decides *how many* ticks to run.
 */
class SimClock(
    speedMultiplier: Int = 1,
    startTick: Long = 0L,
) {
    /** 0 (paused), 1, 10, or 100. Sticky: persisted in the save and restored on resume. */
    var speedMultiplier: Int = speedMultiplier
        set(value) {
            require(value in Time.SPEED_MULTIPLIERS) { "unsupported speed $value" }
            field = value
        }

    /** Days elapsed since the run began. 1 tick = 1 day. */
    var tick: Long = startTick
        private set

    /**
     * Pending fraction of a tick, held in fixed point ([MICRO] units per tick) rather than as a
     * double. Real frame deltas are irregular, and a double accumulator drifts: 600 frames of
     * 1/60s at 1x lands on 99.9999 ticks instead of 100. Fixed point makes the carry exact.
     */
    private var accumulatorMicro: Long = 0L

    val isPaused: Boolean get() = speedMultiplier == 0

    val ticksPerSecond: Double get() = Time.BASE_TICKS_PER_SECOND * speedMultiplier

    /** Whole days into the current year. */
    val dayOfYear: Int get() = (tick % Time.DAYS_PER_YEAR).toInt()

    val year: Int get() = (tick / Time.DAYS_PER_YEAR).toInt()

    val season: Season get() = Season.entries[dayOfYear / Time.DAYS_PER_SEASON]

    /**
     * Accumulate real time and run whole ticks. [onTick] receives each tick index as it runs.
     * Returns the number of ticks executed, which the renderer uses to decide whether this frame
     * is the last of a batch (see [Time.RENDER_THROTTLE_ABOVE_SPEED]).
     */
    fun advance(deltaSeconds: Double, onTick: (Long) -> Unit): Int {
        val count = pendingTicks(deltaSeconds)
        repeat(count) {
            tick += 1
            onTick(tick)
        }
        return count
    }

    /**
     * Drain a fixed number of ticks irrespective of wall-clock time. Used by offline catch-up and
     * by the balance harness, which must go through the same code path as live simulation.
     */
    fun runTicks(count: Long, onTick: (Long) -> Unit) {
        var remaining = count
        while (remaining > 0) {
            tick += 1
            onTick(tick)
            remaining -= 1
        }
    }

    /**
     * Accumulates [deltaSeconds] and returns how many whole ticks to run now, capped at
     * [Time.MAX_TICKS_PER_FRAME]. Exposed for tests; callers should use [advance].
     */
    fun pendingTicks(deltaSeconds: Double): Int {
        if (isPaused || deltaSeconds <= 0.0) return 0
        accumulatorMicro += Math.round(deltaSeconds * ticksPerSecond * MICRO)
        if (accumulatorMicro < MICRO) return 0
        val whole = accumulatorMicro / MICRO
        val capped = if (whole > Time.MAX_TICKS_PER_FRAME) Time.MAX_TICKS_PER_FRAME.toLong() else whole
        accumulatorMicro -= capped * MICRO
        return capped.toInt()
    }

    /** Reset the fractional carry. Only for run start / load, never mid-run. */
    fun resetAccumulator() {
        accumulatorMicro = 0L
    }

    fun tickState(): State = State(tick, speedMultiplier)

    data class State(val tick: Long, val speedMultiplier: Int)

    companion object {
        /** Fixed-point resolution of the tick accumulator. */
        private const val MICRO = 1_000_000L

        fun restore(state: State): SimClock = SimClock(state.speedMultiplier, state.tick)
    }
}
