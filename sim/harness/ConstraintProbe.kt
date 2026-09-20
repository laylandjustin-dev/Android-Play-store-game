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

    /**
     * Why do the AI civs outpace the player's own town?
     *
     * Reported from play, so this compares like with like: same map, same starting allocation for
     * everyone, and then it prints what each civ ended up with. If the rivals are ahead on traits
     * or tech rather than on population, the cause is the growth mechanic rather than the economy.
     */
    private fun npc() {
        val farming = TraitAllocation.of(3, 4, 3, 4, 8)
        for (seed in longArrayOf(1_000L, 8_919L)) {
            val sim = Simulation.newRun(RunConfig(seed = seed, traits = farming))
            // Attended, as a player's run is, and *answered*, as an attentive player's run is: the
            // clock stops on a tech tier and on a decade's trait point, and this takes both the
            // moment they appear — exactly what the shell does. A run left unanswered simply
            // freezes now, which is the fix this probe was written to verify.
            var days = 0
            while (days < 120 * GameConfig.Time.DAYS_PER_YEAR && sim.endState == null) {
                val player = GameConfig.World.PLAYER_CIV_ID
                sim.pendingTechChoices(player).firstOrNull()?.let { sim.chooseTech(player, it) }
                while (sim.pendingTraitPoints(player) > 0) {
                    // The weakest trait with room in it. Deliberately not `needBasedGrowth`, which
                    // is internal to `:sim` and should stay that way — a development tool must not
                    // be the reason a simulation API widens.
                    val trait = sim.civ(player).traits.improvable.minByOrNull {
                        sim.civ(player).traits[it] * Trait.entries.size + it.ordinal
                    } ?: break
                    if (!sim.spendTraitPoint(player, trait)) break
                }
                if (!sim.step()) break
                days++
            }
            println("PROBE seed=$seed ran=${days / GameConfig.Time.DAYS_PER_YEAR}y awaiting=${sim.awaitingPlayer}")
            for (civ in sim.civs) {
                println(
                    "PROBE   ${civ.name.padEnd(9)} pop=${civ.population.toString().padStart(4)} " +
                        "traits=${civ.traits.values.joinToString("/")} spent=${civ.traits.pointsSpent} " +
                        "banked=${civ.unspentTraitPoints} tier=${civ.techTier} techs=${civ.techChoices.size} " +
                        "b=${sim.buildingsOf(civ.id).size}",
                )
            }
        }
    }

    /** Does sharing the island with four rivals still cost the player anything? */
    private fun rivals() {
        val farming = TraitAllocation.of(3, 4, 3, 4, 8)
        for (seed in longArrayOf(1L, 1_000L, 8_919L)) {
            val alone = Simulation.newRun(seed, farming, civCount = 1)
            val crowded = Simulation.newRun(seed, farming)
            val days = 150 * GameConfig.Time.DAYS_PER_YEAR
            alone.runUnattended(days)
            crowded.runUnattended(days)
            val combat = crowded.chronicle.deathsBy(DeathCause.COMBAT)
            val trades = crowded.relations.let { r ->
                (1 until crowded.civs.size).sumOf { r.tradeCount(0, it) }
            }
            println(
                "PROBE seed=$seed alone=${alone.populationOf(0)} crowded=${crowded.populationOf(0)} " +
                    "combatDeaths=$combat trades=$trades peakAlone=${alone.civ(0).peakPopulation} " +
                    "peakCrowded=${crowded.civ(0).peakPopulation}",
            )
        }
    }

    /** Are buildings actually clustered, and do ruins explain the outliers? */
    private fun siting() {
        val sim = Simulation.newRun(
            RunConfig(seed = 1_000L, traits = TraitAllocation.of(3, 4, 3, 4, 8)),
        )
        sim.runUnattended(150 * GameConfig.Time.DAYS_PER_YEAR)
        val limit = GameConfig.Buildings.MAX_DISTANCE_FROM_OWN_BUILDING
        for (civ in sim.civs) {
            val bs = sim.buildingsOf(civ.id)
            if (bs.isEmpty()) continue
            var lonely = 0
            var worst = 0
            for (b in bs) {
                val others = bs.filter { it !== b }
                if (others.isEmpty()) continue
                val nearest = others.minOf {
                    maxOf(kotlin.math.abs(it.x - b.x), kotlin.math.abs(it.y - b.y))
                }
                if (nearest > limit) lonely++
                if (nearest > worst) worst = nearest
            }
            println(
                "PROBE ${civ.name.padEnd(9)} buildings=${bs.size.toString().padStart(3)} " +
                    "beyond-limit=$lonely worst-gap=$worst",
            )
        }
    }

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
        if (args.contains("--siting")) { siting(); return }
        if (args.contains("--rivals")) { rivals(); return }
        if (args.contains("--npc")) { npc(); return }
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
