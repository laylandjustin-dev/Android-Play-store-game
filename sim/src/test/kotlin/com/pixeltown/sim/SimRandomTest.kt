package com.pixeltown.sim

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SimRandomTest {

    @Test
    fun `same seed produces the same stream`() {
        val a = SimRandom(20260913L)
        val b = SimRandom(20260913L)
        repeat(10_000) { assertEquals(a.nextLong(), b.nextLong()) }
    }

    @Test
    fun `different seeds diverge`() {
        val a = SimRandom(1L)
        val b = SimRandom(2L)
        assertNotEquals(a.nextLong(), b.nextLong())
    }

    @Test
    fun `snapshot and restore resume the identical stream`() {
        val rng = SimRandom(7L)
        repeat(500) { rng.nextLong() }
        val resumed = SimRandom.restore(rng.snapshot())
        repeat(500) { assertEquals(rng.nextLong(), resumed.nextLong()) }
    }

    @Test
    fun `bounded ints stay in range and cover it`() {
        val rng = SimRandom(99L)
        val seen = BooleanArray(GameConfig.World.WIDTH)
        repeat(100_000) {
            val v = rng.nextInt(GameConfig.World.WIDTH)
            assertTrue(v in 0 until GameConfig.World.WIDTH)
            seen[v] = true
        }
        assertTrue(seen.all { it }, "every value in the range should appear")
    }

    @Test
    fun `doubles stay in the unit interval`() {
        val rng = SimRandom(4242L)
        repeat(100_000) {
            val v = rng.nextDouble()
            assertTrue(v >= 0.0 && v < 1.0)
        }
    }

    @Test
    fun `shuffle is seed-stable`() {
        val a = (1..64).toMutableList()
        val b = (1..64).toMutableList()
        SimRandom(5L).shuffle(a)
        SimRandom(5L).shuffle(b)
        assertEquals(a, b)
        assertNotEquals((1..64).toList(), a)
    }
}
