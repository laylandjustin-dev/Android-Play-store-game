package com.pixeltown.harness

import com.pixeltown.sim.DeathCause
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.RunConfig
import com.pixeltown.sim.Simulation
import com.pixeltown.sim.TraitAllocation
import com.pixeltown.sim.Trait
import com.pixeltown.sim.World
import com.pixeltown.sim.WorldGenerator
import com.pixeltown.sim.Job
import com.pixeltown.sim.Resource

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
                sim.acknowledgeElection()
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

    /**
     * The first two years, month by month: food in the store, mouths to feed, and who is working.
     *
     * Reported because "food drains continuously and never goes up, and it kills me before year one
     * whatever I do" is a claim about the opening minute of the game, which every existing test
     * walks straight past on its way to measuring a century.
     */
    private fun food() {
        for ((name, traits) in BUILDS) {
            for (seed in longArrayOf(1_000L, 8_919L)) {
                val sim = Simulation.newRun(RunConfig(seed = seed, traits = traits))
                val civ = sim.civ(GameConfig.World.PLAYER_CIV_ID)
                println("PROBE $name seed=$seed  start food=${civ[Resource.FOOD].toInt()} pop=${civ.population}")
                var day = 0
                while (day < 2 * GameConfig.Time.DAYS_PER_YEAR && sim.endState == null) {
                    if (!sim.step()) {
                        // A decision is open; answer it the safe way so the clock keeps running.
                        val player = GameConfig.World.PLAYER_CIV_ID
                        sim.acknowledgeElection()
                        sim.pendingTechChoices(player).firstOrNull()?.let { sim.chooseTech(player, it) }
                        while (sim.pendingTraitPoints(player) > 0) {
                            val t = sim.civ(player).traits.improvable.minByOrNull {
                                sim.civ(player).traits[it] * Trait.entries.size + it.ordinal
                            } ?: break
                            if (!sim.spendTraitPoint(player, t)) break
                        }
                        continue
                    }
                    day++
                    if (day % 30 != 0) continue
                    val jobs = sim.citizens.filter { it.civId == 0 }.groupingBy { it.job }.eachCount()
                    println(
                        "PROBE   day=${day.toString().padStart(3)} " +
                            "food=${civ[Resource.FOOD].toInt().toString().padStart(5)} " +
                            "cap=${civ.foodStorageCapacity.toInt().toString().padStart(5)} " +
                            "pop=${civ.population.toString().padStart(3)} " +
                            "farm=${jobs[Job.FARMER] ?: 0} hunt=${jobs[Job.HUNTER] ?: 0} " +
                            "gath=${jobs[Job.GATHERER] ?: 0} child=${jobs[Job.CHILD] ?: 0} " +
                            "idle=${jobs[Job.IDLE] ?: 0}",
                    )
                }
                println("PROBE   ended=${sim.endState} at day ${sim.day}")
            }
        }
    }

    /**
     * Does *where the player lands* decide whether they can eat?
     *
     * The generator keeps only cells whose `siteScore` is above zero for itself, but the player's
     * choice is accepted on "buildable ground on the mainland" alone — and `legalStartSites`, which
     * the map preview draws, applies that same weak test. So this walks the legal cells, scores them
     * the way the generator would, and plays the worst ones.
     */
    private fun sites() {
        val farming = TraitAllocation.of(3, 4, 3, 4, 8)
        for (seed in longArrayOf(1_000L, 8_919L)) {
            val probe = Simulation.newRun(RunConfig(seed = seed, traits = farming))
            val world = probe.world
            val legal = WorldGenerator.legalStartSites(world)
            val cells = (0 until world.cellCount).filter { legal[it] }

            // Score every legal cell the way the generator scores its own candidates.
            val r = GameConfig.World.CIV_START_SCORE_RADIUS
            fun score(index: Int): Double {
                val x = index % world.width
                val y = index / world.width
                var total = 0.0
                for (dy in -r..r) for (dx in -r..r) {
                    val nx = x + dx
                    val ny = y + dy
                    if (!world.inBounds(nx, ny)) continue
                    total += world.fertility[world.index(nx, ny)] + 0.5 * world.wildGame[world.index(nx, ny)]
                }
                return total
            }
            val scored = cells.map { it to score(it) }.sortedBy { it.second }
            println(
                "PROBE seed=$seed legal=${cells.size} " +
                    "worst=${"%.1f".format(scored.first().second)} " +
                    "best=${"%.1f".format(scored.last().second)} " +
                    "zeroScore=${scored.count { it.second <= 0.0 }}",
            )

            // Walk the distribution, so the floor is chosen where survival actually turns rather
            // than at a round number.
            val best = scored.last().second
            val picks = listOf(0, 5, 10, 15, 20, 25, 30, 40, 50, 75, 100).map { pct ->
                val i = ((scored.size - 1) * pct) / 100
                "p$pct" to scored[i]
            }
            for ((label, pick) in picks) {
                val sim = Simulation.newRun(
                    RunConfig(seed = seed, traits = farming, startCell = pick.first),
                )
                val civ = sim.civ(GameConfig.World.PLAYER_CIV_ID)
                val foodAt = HashMap<Int, Int>()
                var day = 0
                while (day < 2 * GameConfig.Time.DAYS_PER_YEAR && sim.endState == null) {
                    if (!sim.step()) {
                        val player = GameConfig.World.PLAYER_CIV_ID
                        sim.acknowledgeElection()
                        sim.pendingTechChoices(player).firstOrNull()?.let { sim.chooseTech(player, it) }
                        while (sim.pendingTraitPoints(player) > 0) {
                            val t = sim.civ(player).traits.improvable.minByOrNull {
                                sim.civ(player).traits[it] * Trait.entries.size + it.ordinal
                            } ?: break
                            if (!sim.spendTraitPoint(player, t)) break
                        }
                        continue
                    }
                    day++
                    if (day % 90 == 0) foodAt[day] = civ[Resource.FOOD].toInt()
                }
                // How close are the neighbours, and did anyone take the player's land?
                val home = civ.homeSite
                val nearest = sim.civs.filter { !it.isPlayer }.minOf {
                    val a = it.homeSite; val b = home
                    maxOf(
                        kotlin.math.abs(a % world.width - b % world.width),
                        kotlin.math.abs(a / world.width - b / world.width),
                    )
                }
                val owned = (0 until world.cellCount).count { world.ownerCivId[it].toInt() == 0 }
                println(
                    "PROBE   ${label.padEnd(6)} cell=${pick.first.toString().padStart(5)} " +
                        "score=${"%.1f".format(pick.second).padStart(6)} " +
                        "ofBest=${"%.2f".format(pick.second / best)} " +
                        "nearRival=${nearest.toString().padStart(3)} cells=${owned.toString().padStart(4)} " +
                        "food@90=${(foodAt[90] ?: -1).toString().padStart(5)} " +
                        "@180=${(foodAt[180] ?: -1).toString().padStart(5)} " +
                        "@360=${(foodAt[360] ?: -1).toString().padStart(5)} " +
                        "pop=${civ.population.toString().padStart(3)} ended=${sim.endState} day=${sim.day}",
                )
            }
        }
    }

    /**
     * Two runs side by side — one that feeds itself and one that does not — on the same seed and
     * the same allocation, differing only in where the colony landed.
     *
     * Prints what the food system is actually doing: how many farmers hold a work cell, how many of
     * them are standing on or beside it (AD-22, which is what "at work" means), and the fertility
     * under their feet. A colony can be full of farmers and still starve if none of them ever
     * arrives at a field.
     */
    private fun trace(args: Array<String>) {
        val seed = args.firstOrNull { it.startsWith("--seed=") }?.substringAfter('=')?.toLong() ?: 1_000L
        val cells = args.firstOrNull { it.startsWith("--cells=") }?.substringAfter('=')
            ?.split(',')?.map { it.trim().toInt() } ?: emptyList()
        val farming = TraitAllocation.of(3, 4, 3, 4, 8)

        for (cell in cells) {
            val sim = Simulation.newRun(RunConfig(seed = seed, traits = farming, startCell = cell))
            val civ = sim.civ(GameConfig.World.PLAYER_CIV_ID)
            val world = sim.world
            println("PROBE ==== seed=$seed startCell=$cell home=${civ.homeSite}")
            var previous = civ[Resource.FOOD]
            var day = 0
            while (day < 200 && sim.endState == null) {
                if (!sim.step()) {
                    val player = GameConfig.World.PLAYER_CIV_ID
                    sim.pendingTechChoices(player).firstOrNull()?.let { sim.chooseTech(player, it) }
                    while (sim.pendingTraitPoints(player) > 0) {
                        val t = sim.civ(player).traits.improvable.minByOrNull {
                            sim.civ(player).traits[it] * Trait.entries.size + it.ordinal
                        } ?: break
                        if (!sim.spendTraitPoint(player, t)) break
                    }
                    continue
                }
                day++
                if (day % 20 != 0) continue

                val farmers = sim.citizens.filter { it.civId == 0 && it.job == Job.FARMER }
                val withCell = farmers.count { it.workCell != World.NONE }
                val atWork = farmers.count { f ->
                    f.workCell != World.NONE && maxOf(
                        kotlin.math.abs(f.x - f.workCell % world.width),
                        kotlin.math.abs(f.y - f.workCell / world.width),
                    ) <= 1
                }
                val meanFert = farmers.filter { it.workCell != World.NONE }
                    .map { world.fertility[it.workCell].toDouble() }
                    .let { if (it.isEmpty()) 0.0 else it.average() }
                val meanSkill = farmers.map { it.skill.toDouble() }.let { if (it.isEmpty()) 0.0 else it.average() }
                val food = civ[Resource.FOOD]
                println(
                    "PROBE  day=${day.toString().padStart(3)} food=${food.toInt().toString().padStart(5)} " +
                        "delta=${"%+.1f".format((food - previous) / 20.0).padStart(7)}/day " +
                        "pop=${civ.population.toString().padStart(3)} " +
                        "farmers=${farmers.size.toString().padStart(3)} withCell=${withCell.toString().padStart(3)} " +
                        "atWork=${atWork.toString().padStart(3)} fert=${"%.2f".format(meanFert)} " +
                        "skill=${"%.2f".format(meanSkill)}",
                )
                previous = food
            }
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
        if (args.contains("--food")) { food(); return }
        if (args.contains("--sites")) { sites(); return }
        if (args.contains("--trace")) { trace(args); return }
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
