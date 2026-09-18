package com.pixeltown.harness

import com.pixeltown.sim.ChronicleEventKind
import com.pixeltown.sim.EndState
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.RunConfig
import com.pixeltown.sim.Simulation
import com.pixeltown.sim.Temperament
import com.pixeltown.sim.TraitAllocation
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The headless balance harness (§12 of the brief).
 *
 * Runs a grid of allocations across a set of seeds, prints one CSV row per simulation, and then
 * checks the five balance targets the brief names. It lives outside `src/main` on purpose: it is a
 * development tool, and nothing in it should ever be compiled into the Android app.
 *
 * ```
 * ./gradlew -Ppixeltown.simOnly=true :sim:balance                      # the standard 200-sim sweep
 * ./gradlew -Ppixeltown.simOnly=true :sim:balance --args="--seeds=40"  # a bigger one
 * ```
 *
 * Simulations are independent, so they run one per core. Determinism is per-run — each is seeded
 * from its own `RunConfig` and touches nothing shared — so the CSV is identical whatever the
 * machine's core count, which is what makes a harness run comparable across machines.
 */
object BalanceRunner {

    /** How long a run is allowed to last before it is called a success and stopped. */
    private const val YEAR_CAP = 300

    /**
     * The speed a player actually watches a long run at. The brief's "mean run length: 25-45 real
     * minutes" is wall-clock time in a player's hands, not CPU time, so it is derived from the
     * years a run lasts and the rate the clock runs at.
     */
    private const val ASSUMED_SPEED = 10

    /**
     * The allocations swept.
     *
     * "Naive" is the brief's 2/2/2/2/2 — written for a base of 1, where this game's base is 3 — so
     * it is an even spread of the ten points, which is what the phrase means and what a player who
     * does not read the trait descriptions will produce.
     */
    private val ALLOCATIONS: List<Pair<String, TraitAllocation>> = listOf(
        "naive-even" to TraitAllocation.of(5, 5, 5, 5, 5),
        "farmer" to TraitAllocation.of(3, 4, 3, 4, 8),
        "farm+elements" to TraitAllocation.of(3, 4, 3, 6, 6),
        "hardy" to TraitAllocation.of(3, 8, 3, 5, 4),
        "swift" to TraitAllocation.of(8, 4, 3, 3, 5),
        "hunter" to TraitAllocation.of(5, 4, 8, 3, 3),
        "warlike" to TraitAllocation.of(4, 3, 7, 3, 6),
        "scholar-ish" to TraitAllocation.of(7, 5, 3, 3, 5),
        "weathered" to TraitAllocation.of(3, 4, 3, 8, 5),
        "bad" to TraitAllocation.of(8, 3, 3, 3, 1),
    )

    /** One simulation's result — one row of the CSV. */
    data class Run(
        val seed: Long,
        val allocationName: String,
        val allocation: TraitAllocation,
        val years: Int,
        val peakPopulation: Int,
        val finalPopulation: Int,
        val techTier: Int,
        val endState: EndState?,
        val dominantTemperament: Temperament?,
        val buildings: Int,
        val wars: Int,
    ) {
        val reachedCap: Boolean get() = years >= YEAR_CAP
        val minutesAtAssumedSpeed: Double
            get() = years * GameConfig.Time.DAYS_PER_YEAR /
                (GameConfig.Time.BASE_TICKS_PER_SECOND * ASSUMED_SPEED) / 60.0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val seeds = args.firstOrNull { it.startsWith("--seeds=") }?.substringAfter('=')?.toInt() ?: 20
        val csvPath = args.firstOrNull { it.startsWith("--csv=") }?.substringAfter('=')
        val only = args.firstOrNull { it.startsWith("--only=") }?.substringAfter('=')

        val allocations = if (only == null) ALLOCATIONS else ALLOCATIONS.filter { it.first == only }
        require(allocations.isNotEmpty()) { "no allocation matches --only=$only" }

        val jobs = ArrayList<Pair<Long, Pair<String, TraitAllocation>>>()
        for (seedIndex in 0 until seeds) {
            val seed = SEED_BASE + seedIndex * SEED_STRIDE
            for (allocation in allocations) jobs.add(seed to allocation)
        }

        System.err.println("Running ${jobs.size} simulations (${allocations.size} allocations x $seeds seeds)...")
        val startedAt = System.nanoTime()
        val results = runAll(jobs)
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0

        val csv = buildString {
            appendLine(
                "seed,allocation,speed,health,hunting,elements,farming," +
                    "years,peak_population,final_population,tech_tier,end_state," +
                    "dominant_temperament,buildings,wars",
            )
            for (r in results) {
                appendLine(
                    "${r.seed},${r.allocationName}," +
                        "${r.allocation.speed},${r.allocation.health},${r.allocation.hunting}," +
                        "${r.allocation.elements},${r.allocation.farming}," +
                        "${r.years},${r.peakPopulation},${r.finalPopulation},${r.techTier}," +
                        "${r.endState?.name ?: "RUNNING"},${r.dominantTemperament?.name ?: "NONE"}," +
                        "${r.buildings},${r.wars}",
                )
            }
        }
        print(csv)
        if (csvPath != null) {
            File(csvPath).also { it.parentFile?.mkdirs() }.writeText(csv)
            System.err.println("CSV written to $csvPath")
        }

        System.err.println()
        System.err.println(report(results, elapsed))
        exitProcess(if (Targets.evaluate(results).all { it.met }) 0 else 1)
    }

    private fun runAll(jobs: List<Pair<Long, Pair<String, TraitAllocation>>>): List<Run> {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        return try {
            val tasks = jobs.map { (seed, allocation) ->
                Callable { simulate(seed, allocation.first, allocation.second) }
            }
            pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    /** One headless run, to its end or to the year cap. */
    fun simulate(seed: Long, name: String, allocation: TraitAllocation): Run {
        val sim = Simulation.newRun(RunConfig(seed = seed, traits = allocation))
        sim.runUntilEnd(YEAR_CAP * GameConfig.Time.DAYS_PER_YEAR)

        val player = sim.civ(GameConfig.World.PLAYER_CIV_ID)
        val temperaments = sim.elections
            .filter { it.civId == GameConfig.World.PLAYER_CIV_ID }
            .groupingBy { it.temperament }
            .eachCount()

        return Run(
            seed = seed,
            allocationName = name,
            allocation = allocation,
            years = sim.year,
            peakPopulation = player.peakPopulation,
            finalPopulation = player.population,
            techTier = player.techTier,
            endState = sim.endState,
            dominantTemperament = temperaments.maxByOrNull { it.value }?.key,
            buildings = sim.buildingsOf(GameConfig.World.PLAYER_CIV_ID).count { it.isComplete },
            wars = sim.chronicle.totalOf(ChronicleEventKind.WAR_DECLARED),
        )
    }

    // ------------------------------------------------------------------ the targets

    /** One balance target and whether this sweep met it. */
    data class Target(val name: String, val met: Boolean, val detail: String)

    object Targets {
        fun evaluate(results: List<Run>): List<Target> {
            val naive = results.filter { it.allocationName == "naive-even" }
            val reasoned = results.filter { it.allocationName in REASONED }
            val single = results.filter { it.allocationName in SINGLE_TRAIT }

            val naiveMean = naive.map { it.years }.average()
            val capShare = reasoned.count { it.reachedCap } / reasoned.size.toDouble()
            val ascensions = results.count { it.endState == EndState.ASCENSION } / results.size.toDouble()
            val singleBest = single.groupBy { it.allocationName }
                .mapValues { (_, runs) -> runs.map { it.years }.average() }
            val bestReasoned = reasoned.map { it.years }.average()
            val meanMinutes = results.filter { it.years > 0 }.map { it.minutesAtAssumedSpeed }.average()

            return listOf(
                Target(
                    "a naive even spread survives 80-140 years",
                    naiveMean in 80.0..140.0,
                    "mean ${fmt(naiveMean)}y over ${naive.size} runs " +
                        "(median ${median(naive.map { it.years.toDouble() })}y)",
                ),
                Target(
                    "a reasoned allocation reaches $YEAR_CAP years about 1 run in 3",
                    capShare in 0.20..0.50,
                    "${pct(capShare)} of ${reasoned.size} reasoned runs reached the cap",
                ),
                Target(
                    "no single trait at 8 is dominant on its own",
                    singleBest.values.none { it > bestReasoned },
                    singleBest.entries.joinToString { "${it.key} ${fmt(it.value)}y" } +
                        " vs reasoned ${fmt(bestReasoned)}y",
                ),
                Target(
                    "Ascension is rare without Chronicle upgrades",
                    ascensions <= 0.10,
                    "${pct(ascensions)} of runs ascended",
                ),
                Target(
                    "mean run length is 25-45 real minutes at ${ASSUMED_SPEED}x",
                    meanMinutes in 25.0..45.0,
                    "${fmt(meanMinutes)} minutes",
                ),
            )
        }

        /** Allocations a player who read the trait descriptions might plausibly build. */
        private val REASONED = setOf("farmer", "farm+elements", "hardy", "swift", "scholar-ish", "weathered")

        /** Builds that put 8 into one trait, to check none of them wins by itself. */
        private val SINGLE_TRAIT = setOf("hunter", "hardy", "swift", "weathered")
    }

    private fun report(results: List<Run>, elapsedSeconds: Double): String = buildString {
        appendLine("=".repeat(78))
        appendLine("${results.size} simulations in ${fmt(elapsedSeconds)}s")
        appendLine("=".repeat(78))
        appendLine()
        appendLine(
            "allocation".padEnd(16) + "mean y".padStart(8) + "median".padStart(8) +
                "cap%".padStart(7) + "peak".padStart(8) + "tier".padStart(6) +
                "  end states",
        )
        for ((name, _) in ALLOCATIONS) {
            val runs = results.filter { it.allocationName == name }
            if (runs.isEmpty()) continue
            val ends = runs.groupingBy { it.endState?.name ?: "RUNNING" }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key.lowercase()}:${it.value}" }
            appendLine(
                name.padEnd(16) +
                    fmt(runs.map { it.years }.average()).padStart(8) +
                    fmt(median(runs.map { it.years.toDouble() })).padStart(8) +
                    pct(runs.count { it.reachedCap } / runs.size.toDouble()).padStart(7) +
                    runs.map { it.peakPopulation }.average().toInt().toString().padStart(8) +
                    fmt(runs.map { it.techTier }.average()).padStart(6) +
                    "  " + ends,
            )
        }
        appendLine()
        for (target in Targets.evaluate(results)) {
            appendLine("${if (target.met) "PASS" else "FAIL"}  ${target.name}")
            appendLine("      ${target.detail}")
        }
    }

    // Seeds are spread rather than 1..N: consecutive seeds are not more similar than distant ones
    // (the RNG is xoshiro, not a linear one), but a stride makes it obvious in the CSV which runs
    // share a map and which do not.
    private const val SEED_BASE = 1_000L
    private const val SEED_STRIDE = 7_919L

    private fun fmt(value: Double): String {
        val scaled = kotlin.math.round(value * 10).toInt()
        return "${scaled / 10}.${kotlin.math.abs(scaled % 10)}"
    }

    private fun pct(value: Double): String = "${kotlin.math.round(value * 100).toInt()}%"

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
