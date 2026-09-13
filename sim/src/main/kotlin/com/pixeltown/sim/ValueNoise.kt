package com.pixeltown.sim

/**
 * Seeded value noise with fractal (fBm) summation.
 *
 * Hash-based rather than table-based so it is stateless once constructed: sampling in any order
 * gives the same result, which means map generation can be parallelised or reordered later
 * without changing the map a seed produces. Draws its two salt words from the run's [SimRandom]
 * so the whole world stays downstream of the one seeded generator.
 */
internal class ValueNoise(rng: SimRandom) {

    private val saltX: Long = rng.nextLong()
    private val saltY: Long = rng.nextLong()

    /** Deterministic hash of an integer lattice point to `[0, 1)`. */
    private fun lattice(ix: Int, iy: Int): Double {
        var h = ix * -0x61c8864680b583ebL + iy * -0x7ee3623a03d3c83fL + saltX
        h = (h xor (h ushr 33)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 29)) * -0x6b2fb644ecceee15L + saltY
        h = h xor (h ushr 32)
        return (h ushr 11) * (1.0 / (1L shl 53))
    }

    /** Single-octave sample with smoothstep interpolation. Returns `[0, 1]`. */
    fun at(x: Double, y: Double): Double {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = smoothstep(x - x0)
        val fy = smoothstep(y - y0)

        val v00 = lattice(x0, y0)
        val v10 = lattice(x0 + 1, y0)
        val v01 = lattice(x0, y0 + 1)
        val v11 = lattice(x0 + 1, y0 + 1)

        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return top + (bottom - top) * fy
    }

    /**
     * Fractal sum of [octaves] octaves, normalised back to `[0, 1]` so that the thresholds in
     * [GameConfig.World] mean the same thing regardless of octave count.
     */
    fun fractal(
        x: Double,
        y: Double,
        octaves: Int = GameConfig.World.NOISE_OCTAVES,
        frequency: Double = GameConfig.World.NOISE_BASE_FREQUENCY,
        lacunarity: Double = GameConfig.World.NOISE_LACUNARITY,
        persistence: Double = GameConfig.World.NOISE_PERSISTENCE,
    ): Double {
        var sum = 0.0
        var amplitude = 1.0
        var totalAmplitude = 0.0
        var freq = frequency
        repeat(octaves) {
            sum += at(x * freq, y * freq) * amplitude
            totalAmplitude += amplitude
            amplitude *= persistence
            freq *= lacunarity
        }
        return sum / totalAmplitude
    }

    private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
}
