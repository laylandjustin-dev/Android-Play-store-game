package com.pixeltown.sim

/**
 * The one and only source of randomness in the simulation.
 *
 * Deterministic by construction: a xoshiro256** generator with explicit, serialisable state.
 * A single instance is threaded through every system, so the same seed plus the same player
 * inputs replays the same history exactly. [kotlin.random.Random] is deliberately not used —
 * its algorithm is not contractually stable across Kotlin versions.
 */
class SimRandom private constructor(
    private var s0: Long,
    private var s1: Long,
    private var s2: Long,
    private var s3: Long,
) {

    constructor(seed: Long) : this(0L, 0L, 0L, 0L) {
        // SplitMix64 to expand a single seed into the four state words.
        var x = seed
        fun next(): Long {
            x += -0x61c8864680b583ebL
            var z = x
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        s0 = next(); s1 = next(); s2 = next(); s3 = next()
    }

    /** Uniform 64-bit value. */
    fun nextLong(): Long {
        val result = java.lang.Long.rotateLeft(s1 * 5, 7) * 9
        val t = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor t
        s3 = java.lang.Long.rotateLeft(s3, 45)
        return result
    }

    fun nextInt(): Int = (nextLong() ushr 32).toInt()

    /** Uniform in `[0, bound)`. Rejection-sampled so the distribution stays exactly uniform. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }
        val m = bound - 1
        if (bound and m == 0) return (nextLong() ushr 33).toInt() and m
        var bits: Int
        var value: Int
        do {
            bits = (nextLong() ushr 33).toInt()
            value = bits % bound
        } while (bits - value + m < 0)
        return value
    }

    /** Uniform in `[origin, bound)`. */
    fun nextInt(origin: Int, bound: Int): Int {
        require(bound > origin) { "empty range $origin..<$bound" }
        return origin + nextInt(bound - origin)
    }

    /** Uniform in `[0.0, 1.0)`, 53 bits of precision. */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    /** Uniform in `[0.0, bound)`. */
    fun nextDouble(bound: Double): Double = nextDouble() * bound

    /** Uniform in `[origin, bound)`. */
    fun nextDouble(origin: Double, bound: Double): Double = origin + nextDouble() * (bound - origin)

    fun nextFloat(): Float = nextDouble().toFloat()

    fun nextBoolean(): Boolean = nextLong() < 0L

    /** True with probability [p]. `p <= 0` is never, `p >= 1` is always. */
    fun chance(p: Double): Boolean = when {
        p <= 0.0 -> false
        p >= 1.0 -> true
        else -> nextDouble() < p
    }

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    /** Fisher-Yates, in place, so ordering is seed-stable. */
    fun <T> shuffle(items: MutableList<T>) {
        for (i in items.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = items[i]
            items[i] = items[j]
            items[j] = tmp
        }
    }

    /** Snapshot of the generator state, for the save file. */
    fun snapshot(): State = State(s0, s1, s2, s3)

    data class State(val s0: Long, val s1: Long, val s2: Long, val s3: Long)

    companion object {
        /** Restore an exact generator from a saved [State]. */
        fun restore(state: State): SimRandom = SimRandom(state.s0, state.s1, state.s2, state.s3)
    }
}
