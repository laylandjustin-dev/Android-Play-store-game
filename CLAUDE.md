# Pixel Town — architecture and working notes

A pixel-per-citizen Android simulation / incremental game. Every person is one pixel; the town
elects a Premier once a year who decides what gets built; four AI civilisations run the same
simulation on the same map. See `docs/BUILD_PROMPT.md` for the full design brief — this file
records decisions, not requirements.

## Module layout

```
pixeltown/
├── sim/          Pure Kotlin JVM library — the entire simulation. No Android imports, ever.
│   └── src/main/kotlin/com/pixeltown/sim/
│       ├── GameConfig.kt      Every tunable number in the game. One file, by design.
│       ├── Types.kt           Domain enums (terrain, jobs, traits, temperaments, end states).
│       ├── SimRandom.kt       The single seeded RNG threaded through everything.
│       ├── SimClock.kt        Fixed-step accumulator: sim rate decoupled from frame rate.
│       ├── ValueNoise.kt      Seeded value noise + fBm, used by map generation.
│       ├── World.kt           The 128x128 grid, as parallel primitive arrays.
│       ├── WorldGenerator.kt  Island generation: fields, terrain, rivers, starting sites.
│       ├── WorldRenderer.kt   Paints the world into a flat ARGB IntArray; FrameRenderer composites.
│       ├── TraitAllocation.kt The five traits and every stat derived from them.
│       ├── Citizen.kt         One person, one pixel. Mutable by design.
│       ├── Civilization.kt    A civ's shared state: stores, tech, unrest, statistics.
│       ├── Chronicle.kt       The rolling event record behind the feed and the return report.
│       ├── Simulation.kt      The tick: founding, work, feeding, ageing, death, pairing, birth.
│       ├── EconomySystem.kt   Job assignment, work cells, production, soil and game recovery.
│       ├── Palette.kt         Terrain/civ/building colours as plain ints.
│       └── Viewport.kt        The visible rectangle of the world, in cells.
└── app/          Android application — Compose UI, bitmap upload, billing, persistence.
    └── src/main/kotlin/com/pixeltown/app/
        ├── MainActivity.kt    Compose root, frame-driven tick loop, HUD.
        ├── PixelCanvas.kt     IntArray → Bitmap.setPixels → drawImage(FilterQuality.None).
        └── WorldGestures.kt   Pinch-zoom and pan, converted from screen px to world cells.
```

`:app` depends on `:sim`. `:sim` depends on nothing but the Kotlin stdlib and
kotlinx.serialization. That dependency direction is the whole architecture.

## Architecture decisions

**AD-1 — The simulation is a separate Gradle module, not a package.** Making `:sim` a plain
Kotlin JVM library means an Android import there is a compile error, not a code-review catch.
A `checkNoAndroidImports` task in `sim/build.gradle.kts` additionally fails the build on any
`import android...` line, which covers the case where someone adds an Android dependency to the
module by hand.

**AD-2 — Determinism comes from one RNG with explicit state.** `SimRandom` is xoshiro256** with
four serialisable state words. `kotlin.random.Random` is deliberately not used: its algorithm is
not contractually stable across Kotlin versions, so a save written by one release could replay
differently under the next. One instance is threaded through every system; nothing else may
generate randomness. The generator state is part of the save file.

**AD-3 — The tick accumulator is fixed point, not floating point.** Real frame deltas are
irregular and a `Double` accumulator drifts: 600 frames of 1/60s at 1× lands on 99.9999 ticks
and silently loses a day. `SimClock` accumulates in millionths of a tick (`Long`), so the carry
is exact and tick N is identical whether it was reached at 1×, 10× or 100×. Catch-up is capped
at 200 ticks per frame and the remainder is carried, so a stalled frame cannot spiral.

**AD-4 — Every tunable number lives in `sim/GameConfig.kt`.** Logic reads from it; no magic
numbers in systems. `GameConfigTest` guards the invariants the design depends on (survival
weights sum to 100, every enum-keyed table is complete, the purchased-allocation cap is 4).

**AD-5 — One rendering path.** The simulation writes ARGB ints into a single `IntArray`
(`PixelFramebuffer`), which is pushed to one reused `Bitmap` via `setPixels` and drawn once per
frame with `FilterQuality.None`. Citizens are never Compose shapes — there will be thousands.

**AD-6 — `:app` is optional in the build graph.** The Android Gradle Plugin needs Google's Maven
repository, which is not reachable from every machine (this sandbox included, and some CI
runners). Building with `-Ppixeltown.simOnly=true` excludes `:app` so the simulation stays
buildable and testable anywhere. Plugins are therefore declared per-module; the root
`build.gradle.kts` declares none.

**AD-7 — Java 17 bytecode from any JDK 17+.** Neither module pins a toolchain (no JDK
provisioning in a sandboxed build); both set `jvmTarget`/`targetCompatibility` to 17 instead.

**AD-10 — Terrain is classified by quantile, not by absolute elevation.** Value-noise fields vary
a lot between seeds: at a fixed sea level the land fraction ranged from 0.27 to 0.45, so some
seeds were archipelagos and others near-continents. `WorldGenerator` slices the *sorted*
elevation field at `TARGET_LAND_FRACTION` (and the land thresholds at shares of the land), so
every map has a comparable amount of usable land while the island's shape still varies freely.
Balance can then assume a roughly constant amount of farmland per run.

**AD-11 — The island mask is square (Chebyshev), not radial.** A radial falloff only reaches zero
at the four corners, so land ran off the middle of each edge. A square mask reaches zero along
every border, which makes "the coastline is always drawn by the noise, never by the map bounds"
an invariant the tests can assert.

**AD-12 — Rivers are routed on a blurred copy of the elevation field, from the most inland
sources.** Steepest descent on raw value noise stalls in the first pit it meets (rivers came out
5–8 cells long), and ranking sources by elevation put them on coastal peaks. Flow now descends a
box-blurred field with a small uphill breach tolerance, and sources are ranked by breadth-first
distance from the ocean with moisture as the tiebreak. Rivers now run 11–13 cells against a
maximum possible inland distance of 16–23, i.e. close to what the island geometry allows.

**AD-13 — Starting sites are restricted to the largest landmass.** A test caught the generator
stranding a civ on an offshore islet. Since armies walk across the map as pixel columns (M5),
every civ must be reachable on foot; `WorldGenerator` now flood-fills for the mainland and scores
sites only there. A separate test asserts ≥85% of land is one connected component.

**AD-14 — `Viewport` lives in `:sim`, not the UI layer.** It is pure world-space arithmetic that
deserves tests, and the simulation needs it regardless: rivals are simulated in full detail
within `Rivals.DETAIL_RADIUS_CELLS` of the player-visible area and approximated beyond it, so
"what can the player see" is a simulation input. The app converts gesture deltas from screen
pixels to world cells and hands them to it.

**AD-15 — The renderer lives in `:sim` too; the app only uploads bitmaps.** `WorldRenderer`
writes ARGB ints into a flat `IntArray` with no Android types, so the code that runs every frame
is unit-tested and benchmarked on the JVM (terrain repaint is ~0.3ms against a 16ms budget).
`PixelFramebuffer` in the app is the only class that knows what a `Bitmap` is. Zoom and pan move
the *source* rectangle of a single `drawImage` call rather than resampling the buffer, so there is
no per-zoom pixel work and no filtering artefacts.

**AD-16 — Map previews are exported as PNGs from the test source set.** `MapPreviewExporter`
writes `sim/build/preview/map-seed-*.png` on every test run using `javax.imageio`, which lets the
renderer be inspected without a device. It is test-only on purpose: `java.awt` does not exist on
Android and must never be dexed into the app.

**AD-17 — Short rations are shared proportionally, not first-come-first-served.** Feeding citizens
in id order until the store ran dry would have starved high-id citizens first, making who dies an
artefact of spawn order. Every citizen now receives the same fraction of their ration.

**AD-18 — Starvation past the 12-day threshold is a daily roll, not a hard cut.** Because
rationing is uniform, every citizen in a famine is in an identical state, and a hard threshold
killed an entire 50-person colony on a single tick. A daily chance past
`STARVATION_DAYS` spreads collapse over about a week without letting anyone die sooner than the
design allows; `STARVATION_CERTAIN_DEATH_DAYS` still guarantees an end.

**AD-19 — The tick iterates the population by index, not with an iterator.** Births append to the
living list mid-tick, which threw `ConcurrentModificationException`. Iterating `0 until sizeAtTickStart`
also means newborns are not processed by systems that already ran this tick, and since newborn ids
are always the largest, appending keeps the list in ascending id order for free.

**AD-20 — Citizens stay an array of objects for now; measured, not assumed.** Tick cost is linear
in population: ~0.06ms at 500 citizens, 0.35ms at 3,000, 0.64ms at 6,000 on a desktop JVM, and an
unbounded fed colony plateaus around 6,500 on a 128x128 map. Struct-of-arrays is not yet
warranted. **But note for M7:** 100x needs 1,000 ticks/sec, i.e. a 1ms tick budget. A phone is
several times slower than this machine, so a large late-run colony will not sustain true 100x —
the per-frame tick cap will silently throttle it. That is a real balance-and-UX issue to confront
at the polish milestone, not a bug in the clock.

**AD-21 — Work is spatial, and workers look for cells near themselves.** A farmer walks to a
fertile cell and works *that* cell, draining its fertility; this is what makes over-farming a real
failure mode and pushes a growing town outward. The first implementation searched from the town
centre with a weak distance penalty, which sent workers on 20-cell marches to marginally better
soil — at ~1 cell/day only 5 of 48 ever arrived and the colony starved surrounded by good land.
Workers now search within `WORK_SEARCH_RADIUS` of where they stand, with a steep distance penalty.

**AD-22 — A worker counts as "at work" when standing on their cell *or beside it*.** Requiring the
exact cell deadlocked workers whose plot was occupied by a passer-by, since one occupant per cell
is a hard invariant.

**AD-23 — Two constants the design formulas need but do not state.** Both are deviations, recorded
here rather than buried:
  - `SKILL_OUTPUT_FLOOR` (0.45). Output is written as `... x skill`, but skill starts at 0 and
    takes six years to mature, so a literal reading has a new colony produce nothing and starve
    before anyone learns their trade. A beginner now works at 45% of a master's rate.
  - `FARM_OUTPUT_SCALE` / `HUNT_OUTPUT_SCALE` (3.0). The formula's natural scale is ~1.0 food per
    farmer-day — exactly one person's ration — so a colony could never staff anything but farms.
    A competent farmer now feeds about three people, which is what leaves room for hunters,
    gatherers, builders and scholars.

**AD-24 — The design's conception rate was wrong against its own childhood length, and is now
0.0012.** At the documented 0.0028 an eligible woman conceives roughly once a year; against a
14-year childhood that drove the child share of a colony past 65% within a decade, and a
workforce that small cannot feed its dependants. An even-spread colony died in 5-11 years while a
farming colony ran away to 1,700 people. Measured sweep:

| `CONCEIVE_BASE` | even spread | farming build | child share at year 50 |
|---|---|---|---|
| 0.0028 (design) | dies 5-11y | 1,536-1,764 | 0.50-0.58 |
| **0.0012 (now)** | **120y+, ~90 people** | **233-309** | **0.29-0.40** |
| 0.0006 | 120y+, ~68 people | 68-72 | 0.22-0.33 |

0.0012 also puts the 400-population Ascension condition back in reach as a stretch rather than a
formality.

**AD-25 — Job weights stay a fixed, farm-heavy split until the Premier exists.** Deriving the
food share from the civ's own `farmYield`/`huntYield` ratio was tried and made every allocation
worse: the two coefficients are similar, but a farm cell (fertility ~0.85) out-produces a game
cell (~0.5 and falling as it is hunted), so splitting by coefficient sent half the workforce to
the weaker job — an even-spread colony fell from ~100 years to under 10. Choosing a food strategy
is the Premier's job, informed by what the town actually needs (M4), not something to infer from
the trait sheet.

**AD-26 — Hunting was self-defeating and is now sustainable.** Regrowth at 0.4% of capacity per
day against 2% depletion meant a hunter stripped a cell in weeks and moved on; the values are now
1.0% and 0.8%, so a hunted cell reaches equilibrium instead of collapsing. **Still open:** a
Hunting-8 civ dies in 1-2 years, because the fixed weights put 46% of its workforce into farming
it has no talent for. The allocation screen offers Hunting as a path, so this must be resolved
when Premier agendas land (M4) — it is a known gap, not a finished balance.

### Decisions recorded ahead of implementation

**AD-8 — Entitlements are read only at run start.** The simulation snapshots its starting
configuration when a run begins and never queries the billing layer again, so a purchase can
neither change an in-flight run nor corrupt a save. (To be enforced by a test at M8.)

**AD-9 — Entitlement validation is local for v1, and that is a deliberate trade-off.**
Entitlements are stored locally and obfuscated (not plaintext JSON), which is spoofable on a
rooted device. Accepted: this is a single-player game with no economy to protect and no
multiplayer advantage to gain, and a validation backend is not worth its cost yet. The
`Entitlements` interface is kept backend-ready so server-side receipt validation via the Play
Developer API can be added later without touching call sites.

## Build and test commands

```bash
# Simulation only — works on any JDK 17+ machine, no Android SDK needed.
./gradlew -Ppixeltown.simOnly=true :sim:test     # JUnit 5 tests
./gradlew -Ppixeltown.simOnly=true :sim:build    # compile + tests + no-Android-imports check

# Full build — requires the Android SDK and network access to dl.google.com.
./gradlew :app:assembleDebug
./gradlew test
```

Per the brief: after every milestone, run the debug assemble and the tests, fix all failures,
then commit with a message naming the milestone. Do not move on with a red build.

## Milestone status

- **M0 — Skeleton.** Done. Modules, config, clock, RNG, canvas.
- **M1 — World & renderer.** Simulation side done and tested: generation, terrain, rivers,
  starting sites, the pixel renderer and the viewport. The Compose gesture layer
  (`WorldGestures.kt`) and the HUD are written but **unbuilt and unrun** — see below. "Zoom and
  pan smoothly at 60fps" is therefore not yet verified on a device.
- **M3 — Economy & jobs.** Done and tested. Resources, weekly job assignment with hunger
  overriding politics, spatial work cells, farming/hunting/gathering/scholarship/craft, skill
  growth and reassignment cost, soil drain and recovery, game depletion and regrowth, territory
  claims. Gate met: a Farming allocation stabilises and grows for 200+ years, a Farming-1 one
  collapses inside 30. See the balance table below. `sim/build/preview/colony-year-60.png` shows
  five mature towns.
- **M2 — Citizens & the life cycle.** Done and tested: 50 settlers per civ, the survival score,
  hunger, ageing, disease, death with causes, pairing, pregnancy, birth, wandering, and the
  Chronicle. No jobs yet, so a colony lives on its founding stores and starves: extinction lands
  between days 49 and 55 depending on seed, identical every replay. `sim/build/preview/colony-day-10.png`
  shows five colonies on the map.

### M3 balance measurements

300-year cap, seeds 1/42/555, population peak in brackets. Reproduce with `BalanceTest`:

| Allocation | seed 1 | seed 42 | seed 555 |
|---|---|---|---|
| even 5/5/5/5/5 | 300y [102] | 195y [92] | 300y [1237] |
| farmer 3/4/3/4/8 | 300y [860] | 300y [678] | 277y [83] |
| farm+elem 3/4/3/6/6 | 300y [2666] | 300y [1097] | 300y [708] |
| hunter 5/4/8/3/3 | 2y [50] | 1y [51] | 1y [55] |
| bad 8/3/3/3/1 | 0y [50] | 0y [50] | 0y [50] |

Against the §12 targets: runs are currently **too survivable** — a naive spread should fail in
80-140 years and a good one should reach 300 only about one run in three. Both are expected to
tighten once buildings carry upkeep (M4) and rivals raid, extort and invade (M5); the real tuning
pass is the headless harness at M7, over 200+ sims. Seed-to-seed variance is large (even spread
peaks at 102 on one seed and 1,237 on another), which is worth watching: some of it is map luck,
but some is famine cascades near a knife edge.

## Known environment limitation

The sandbox this repository is currently developed in cannot reach `dl.google.com`, so the
Android Gradle Plugin cannot be resolved and **`:app` has never been compiled here**. `:sim` is
fully built and tested. Anything under `app/` is unverified code until it is built on a machine
with the Android SDK and unrestricted network access — treat the first such build as a step in
its own right, not a formality.

## Stack

Kotlin 2.1.20, min SDK 26, target/compile SDK 36, AGP 8.10.1, Gradle 8.14.3, Jetpack Compose
(BOM 2025.05.01), kotlinx.coroutines, kotlinx.serialization, JUnit 5 + kotlin.test.

Persistence will be a single JSON save file in app-private storage written atomically (temp,
fsync, rename). No Room, no SQLite.

## Conventions

- Time: 1 tick = 1 day; 30-day months, 12 months, 4 seasons, 360-day years.
- Probabilities in `GameConfig` are per-day unless the name says otherwise.
- Derived trait stats are written as `base + perPoint * trait` so the formulas in the brief map
  one-to-one onto constant pairs.
- Ask before adding any third-party dependency that is not already in `gradle/libs.versions.toml`.
