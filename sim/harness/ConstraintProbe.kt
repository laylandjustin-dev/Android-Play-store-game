package com.pixeltown.harness

import com.pixeltown.sim.DeathCause
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.RunConfig
import com.pixeltown.sim.Simulation
import com.pixeltown.sim.TraitAllocation
import com.pixeltown.sim.Trait

/**
 * What is actually limiting each build, rather than how long it lasted.
 *
 * `BalanceRunner` measures outcomes; this measures *causes*. Liebig's law of the minimum says a
 * population's size is set by its scarcest resource alone, so if every build is limited by the same
 * thing, only the traits bearing on that thing can matter — the rest are decoration however good
 * their numbers look. Sugarscape found the same in its own two-trait agents, whose populations
 * converge on good vision and low metabolism because those are the only traits touching sugar.
 *
 * So before changing what a trait does, this prints the cause-of-death mix, the seasonal swing in
 * food, and the share of people lost to each pressure, per build. A trait whose pressure kills
 * almost nobody cannot be a real choice, and that is a measurement, not an opinion.
 *
 * `./gradlew -Ppixeltown.simOnly=true :sim:probe`
 */
object ConstraintProbe {

    private val BUILDS = listOf(
        "even 5/5/5/5/5" to TraitAllocation.of(5, 5, 5, 5, 5),
        "farming 3/4/3/4/8" to TraitAllocation.of(3, 4, 3, 4, 8),
        "speed 8/4/3/3/4" to TraitAllocation.of(8, 4, 3, 3, 4),
        "health 3/8/3/4/4" to TraitAllocation.of(3, 8, 3, 4, 4),
        "elements 3/4/3/8/4" to TraitAllocation.of(3, 4, 3, 8, 4),
        "hunting 3/4/8/3/4" to TraitAllocation.of(3, 4, 8, 3, 4),
    )

    private val SEEDS = longArrayOf(1_000L, 8_919L, 16_838L)
    private const val YEARS = 60

    @JvmStatic
    fun main(args: Array<String>) {
        val years = args.firstOrNull { it.startsWith("--years=") }?.substringAfter('=')?.toInt() ?: YEARS

        println("Cause of death and limiting pressure, $years years, ${SEEDS.size} seeds per build.")
        println("Shares are of all player deaths; 'winter' is the worst season's food store as a")
        println("share of the best season's, so a low number means the year has a real trough.")
        println()
        println(
            "build".padEnd(20) + "died".padStart(6) + "starve".padStart(8) + "ill".padStart(7) +
                "expos".padStart(7) + "age".padStart(7) + "combat".padStart(8) + "emig".padStart(7) +
                "  winter  peak  yrs",
        )

        for ((name, traits) in BUILDS) {
            val totals = HashMap<DeathCause, Int>()
            var peak = 0
            var yearsLived = 0
            var winterRatio = 0.0

            for (seed in SEEDS) {
                val sim = Simulation.newRun(RunConfig(seed = seed, traits = traits))

                var bestStore = 0.0
                var worstStore = Double.MAX_VALUE
                val player = sim.civ(GameConfig.World.PLAYER_CIV_ID)
                var seasonHigh = 0.0
                var seasonLow = Double.MAX_VALUE

                repeat(years * GameConfig.Time.DAYS_PER_YEAR) {
                    if (sim.endState != null) return@repeat
                    sim.runUnattended(1)
                    // Sample the store once a season: the swing between the best and worst season
                    // is what an Elements trait would have to be defending against.
                    if (sim.day % GameConfig.Time.DAYS_PER_SEASON == 0L && player.population > 0) {
                        val perHead = player[com.pixeltown.sim.Resource.FOOD] / player.population
                        if (perHead > seasonHigh) seasonHigh = perHead
                        if (perHead < seasonLow) seasonLow = perHead
                    }
                }
                bestStore = seasonHigh
                worstStore = seasonLow

                for ((cause, n) in sim.chronicle.deathBreakdown()) {
                    totals[cause] = (totals[cause] ?: 0) + n
                }
                peak += player.peakPopulation
                yearsLived += sim.year
                winterRatio += if (bestStore > 0.0 && worstStore < Double.MAX_VALUE) worstStore / bestStore else 0.0
            }

            val died = totals.values.sum().coerceAtLeast(1)
            fun share(cause: DeathCause) = "%.0f%%".format(100.0 * (totals[cause] ?: 0) / died)

            println(
                name.padEnd(20) + died.toString().padStart(6) +
                    share(DeathCause.STARVATION).padStart(8) +
                    share(DeathCause.ILLNESS).padStart(7) +
                    share(DeathCause.EXPOSURE).padStart(7) +
                    share(DeathCause.OLD_AGE).padStart(7) +
                    share(DeathCause.COMBAT).padStart(8) +
                    share(DeathCause.EMIGRATION).padStart(7) +
                    "  %.2f".format(winterRatio / SEEDS.size).padStart(8) +
                    (peak / SEEDS.size).toString().padStart(6) +
                    (yearsLived / SEEDS.size).toString().padStart(5),
            )
        }

        println()
        println("Derived stats at each trait value, for reference:")
        println("value".padEnd(7) + Trait.entries.joinToString("") { it.name.lowercase().padStart(14) })
        for (v in 1..8) {
            val t = TraitAllocation.of(v, v, v, v, v)
            println(
                v.toString().padEnd(7) +
                    "work %.2f".format(t.workMultiplier).padStart(14) +
                    "life %.0f".format(t.lifespanYears).padStart(14) +
                    "hunt %.2f".format(t.huntYield).padStart(14) +
                    "shelt %.2f".format(t.elementsShelter).padStart(14) +
                    "farm %.2f".format(t.farmYield).padStart(14),
            )
        }
    }
}
