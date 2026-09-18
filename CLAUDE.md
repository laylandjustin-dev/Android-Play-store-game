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
│       ├── Building.kt        Building types, specs, instances, and aggregated civ effects.
│       ├── BuildingSystem.kt  Siting, construction, upkeep, and effect aggregation.
│       ├── Politics.kt        Agendas, candidates, premiers, elections, names, pitch lines.
│       ├── CouncilSystem.kt   The vote, the Premier's decisions, and unrest.
│       ├── Diplomacy.kt      Relations, armies, and the arithmetic of posture and battle.
│       ├── SaveGame.kt       The versioned save format and its codec.
│       ├── Legacy.kt         Chronicle points, permanent upgrades, RunConfig, RunSummary.
│       ├── OfflineCatchUp.kt Time away -> ticks, and the "while you were away" report.
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

**AD-43 — A web playback viewer, because the app cannot be built here.** `WebExporter` records a
real run as a sprite sheet of yearly frames plus a timeline of stats and Chronicle events, which a
static HTML page plays back with pan, zoom and a scrubber. It is a *recording*, not a second
implementation — the frames come from the same `FrameRenderer` the app uses, so there is no risk
of a parallel JavaScript simulation drifting from the real one. It is skipped unless
`PIXELTOWN_WEB_OUT` is set: exporting two centuries costs 90 seconds and does not belong in every
suite run. If a genuinely playable web build is ever wanted, the right answer is Kotlin/JS
compiling `:sim` itself, not a port.

**AD-44 — There is a real web build: `:sim` compiled to JavaScript.** Not a port and not a
recording — `web/src/WebGame.kt` is a thin façade over the same module the Android app uses, so
there is exactly one implementation of the game. Three things made it possible:

- **`:sim` is now portable Kotlin.** `java.lang.Long.rotateLeft`, `Math.pow`, `Math.round` and
  `Integer.signum` are gone, replaced with stdlib equivalents; determinism is unchanged (all 171
  tests still pass). Gzip is the single exception and lives alone in `SaveCompression.kt`, which
  the web build leaves out.
- **The Kotlin JS compiler is invoked directly**, not through the Kotlin/JS Gradle plugin, which
  pulls a Node and Yarn toolchain this environment cannot download. `web/build-web.sh` fetches the
  JS stdlib klibs from Maven Central and runs `K2JSCompiler` from the Gradle cache. K2 needs two
  passes: sources to klib, then klib to JavaScript via `-Xinclude`.
- **The JS output directory is cleared by the compiler**, so the klibs must live outside it.

**AD-45 — The web build found a real performance bug that also affects Android.** Land
regeneration sweeps all 16,384 cells every tick and looked up two `Map<TerrainType, Double>`
entries per cell — 32,768 hash lookups a tick. On the JVM that is invisible; in JavaScript it cost
more than every citizen in the game put together. Replaced with flat arrays indexed by terrain
ordinal:

| | before | after |
|---|---|---|
| JS tick (pop ~90) | 4.7ms | **1.4ms** |
| JVM test suite | 5m13 | **4m17** |

A 3.4x speedup in the browser and a real one on the JVM too. This is the same lesson as AD-31 from
the other direction: a per-cell or per-citizen hash lookup is a per-tick sweep in disguise.

**AD-46 — In the browser, ticks run on a timer and painting on an animation frame.** The first
version drove the simulation from `requestAnimationFrame`, which ties the world's clock to the
display — against AD-3, the project's own rule that sim rate is decoupled from frame rate. It also
made the loop untestable: this environment's headless Chrome delivers exactly one animation frame,
so the world sat at year zero. Ticks now run from `setInterval` under a 12ms budget checked
*after* each tick (checking before meant a frame could run none at all), and rAF only repaints.

**AD-47 — Rival civilisations allocate their own traits: archetype priors, a viability floor, and
diversity rejection.** The allocation screen belongs to the player alone, so the four AI civs need a
method of their own. Three alternatives were rejected: uniform random (produces incoherent peoples,
and a third of them Hunting-heavy and therefore dead in two years — AD-26), a fixed table of
handmade builds (identical rivals every run, which kills replay value), and optimising for survival
(every rival converges on farming, so the map holds five of the same civ). The method now in
`RivalStrategist.kt` follows four principles:

- **Coherent.** Each `Personality` carries prior weights over the five traits, and points are drawn
  against those weights, so a militant people really is built for war and a mercantile one for
  trade. This is the same idea as AD-33 — personality should be legible in what a civ *does* — one
  layer earlier, in what its people *are*.
- **Viable.** Farming is raised to `Rivals.MIN_VIABLE_FARMING` before the priors get a say. Measured,
  not asserted: rivals still alive at year 150 across five seeds came out 10/20 at a floor of 3,
  **13/20 at 4**, and 10/20 at 5 — a floor of 5 eats a fifth of the budget and starts hurting.
- **Diverse.** A per-civ "focus" exponent (0.6–2.6) is rolled on the priors, so some peoples commit
  hard to one idea and others hedge; duplicate builds within a run are rejected and redrawn. Sixty
  draws produce at least ten distinct builds.
- **Honest.** The build matches the personality the Rivals panel shows the player, so reading
  "militant" is real information about who you are facing.

**AD-48 — The player's civ is rendered to be found, and there is a highlight mode for when it is
not.** Five civs in five colours on a 128x128 island is legible in a screenshot and not on a phone
at arm's length. Three changes, all in the renderer so they are tested and shared by every front
end: the player's citizens never dim below `Render.PLAYER_MIN_BRIGHTNESS` however badly the run is
going (rivals keep the full survival-brightness range), the player's claimed territory is tinted
`PLAYER_TERRITORY_TINT_SCALE` harder than anyone else's, and a broken ring of
`HOME_MARKER_RADIUS` marks the player's founding site without ever painting over the cell itself.
`FrameRenderer.render(focusPlayer = true)` additionally pushes the rivals down to
`FOCUS_RIVAL_BRIGHTNESS_SCALE` — the answer to "where am I?" rather than a permanent view mode, so
it is a toggle in the UI and off by default. Hardship is still visible: a starving player civ is
dimmer than a fed one, just never invisible.

**AD-49 — The colony's name is the player's, cleaned in `:sim`, and part of the save.** Naming is
presentation — no system reads it to decide anything, which is what makes it safe: a named run and
an unnamed one on the same seed have identical RNG state after 400 ticks, and a test asserts that.
Three things are deliberate:

- **Cleaning lives in `ColonyName`, not in a UI.** `sanitise` is total and has no RNG in it, so
  every front end applies the same rules and a stored name always reloads to the same string.
  Whitespace is collapsed, control and FORMAT characters are dropped (a pasted right-to-left
  override would reorder the whole HUD line it is drawn on), and the result is capped at
  `MAX_COLONY_NAME_LENGTH` — a layout limit, since the name shares a line with the year on a phone.
  A name that cleans away to nothing becomes the default rather than an error.
- **Rival names are chosen around the player's.** The Chronicle prints names, not ids, so a player
  who calls their town "Kressen" would otherwise be indistinguishable from a rival. The name pool
  is longer than the civ count and rivals take the first entries the player leaves free — a
  deterministic scan, so it costs nothing from the RNG stream. `Simulation.CIV_NAMES` is gone.
- **`RunConfigSave.colonyName` is defaulted**, so a save written before this change still loads and
  simply carries the default name — which is what it was displaying anyway. No format bump.

**AD-50 — A people grows: one trait point every ten years, and an idle player never falls
behind.** The opening ten points were the only allocation decision in a 300-year run, which left
the trait sheet inert after minute one. Each civ now earns a point per decade. Four consequences
worth recording:

- **`Civilization.traits` is now a `var`, and that is safe because nothing caches it.** Every
  system reads `civ.traits` live and `TraitAllocation` computes its derived stats in its
  constructor, so growth is a whole new allocation object, never a mutated one.
- **The ceiling does the balancing.** `MAX_PER_TRAIT` is 8 and the opening budget is 10, so there
  are 15 points to win and a civ tops out around year 150. No new cap was needed.
- **Rivals spend on the spot; the player's point waits.** A rival's choice is a weighted draw, so
  it has to happen at a fixed point in the tick to stay deterministic. The player's accumulates —
  the decision is the mechanic.
- **But an unspent point auto-spends after a year** (`GENERATION_AUTOSPEND_GRACE_DAYS`). This is an
  idle game with an offline catch-up path (AD-41): a player away for a day must not return behind
  four rivals who spend theirs immediately. The automatic choice is deliberately the *safe* one —
  food if the town is hungry or cannot feed itself, otherwise its weakest trait — so attention is
  rewarded with direction, never with raw power. Without this the fifty-year building gate
  regressed on the first run of the suite, which is how the asymmetry was caught.

**AD-51 — The player chooses where to land, and an illegal choice is ignored rather than
rejected.** `WorldGenerator.generate` takes an optional `playerSite`; if it is buildable ground on
the main landmass it becomes civ 0's site and the rivals are placed around it by the existing
separation pass. Anything else — ocean, a mountain, an offshore islet, a stale cell from a
different seed — is silently discarded and the generator's own pick stands, so a bad value can
never produce an unplayable run. The RNG is consumed identically either way, so a seed plus a site
is all a replay needs. `legalStartSites` exposes the same test the generator applies, because a UI
that lets the player tap anywhere and then quietly overrules them is worse than no choice at all.

**AD-52 — Civ colours are per-run state, not a global table.** `Palette.CIV` was a fixed array,
which was fine while the player was always gold. `CivColors` is now a value the renderer is handed:
the map preview and a live run can disagree about who is what colour, which they must, since the
preview exists before the run does. Rivals are recoloured around the player's pick for the same
reason their names are chosen around the colony's (AD-49) — a rival in the player's own colour
would defeat the point of choosing one. The choice is an index into a fixed set of eight tested
swatches rather than a free picker: a player who chose forest green would lose their own people
against the trees, and the game would have let them.

**AD-53 — The balance harness, and the four faults it found.** `sim/harness/BalanceRunner.kt`
lives in its own Gradle source set so a development tool can never be linked into the app. It
sweeps a grid of allocations across seeds, writes the CSV §12 asks for, and evaluates the five
balance targets, exiting non-zero if any fails. Simulations are independent, so it runs one per
core; determinism is per-run, so the CSV is identical whatever the machine's core count.

About 2,400 simulations over ten sweeps produced four findings that no amount of reading the code
would have given:

- **Over-farming was a no-op.** A cell worked every day drained 0.0035 against a Farming-5 people's
  0.0032/day of recovery — a net 0.0003. Recovery applies to every cell every day and drain only to
  worked ones, so that one number was the whole constraint, and it made the failure mode AD-21 was
  designed around not exist. Nothing ever stopped a comfortable town.
- **Movement, not work rate, decided whether a people was viable at all.** Two builds with
  *identical* Farming 4 measured 199 years (Speed 8) and 3.8 years (Speed 3). The work multiplier
  could not explain a 52x gap. Work is spatial (AD-21) and a worker counts as working only when
  standing on or beside their cell (AD-22), so at 1.14 cells a day a slow people spends its life
  walking to a field rather than in it.
- **Food output is a cliff dial, not a difficulty dial.** Lowering it to make the game harder made
  a naive spread live *longer* — 241 years to 273, peak population 212 to 306 — because a smaller
  town does not over-farm itself into a famine. It decides who can feed themselves at all.
- **An emergency threshold above normal reserves makes emergency the only mode.** Raising the food
  crisis trigger to 30 days of stock put every civ permanently in crisis with 95% of its workforce
  farming: nothing gathered, nothing built, nobody housed, nobody born.

**AD-54 — Three of the M7 tunings raise a floor rather than lower a ceiling.** Work rate, movement
and (attempted) farm yield were all narrowed by lifting their bottom end, not cutting their top.
The pattern behind it: *a trait that gates viability is a trait players cannot choose against*. When
Speed decided whether farming worked at all rather than how well, four of the five traits were
decoration. `GameConfigTest` now asserts the two deviations from the design's stated formulas
explicitly, so a third one appearing silently fails the build.

**AD-55 — The harness's targets can be met by a broken game, and the test suite is what catches
it.** One tuning state hit three of the five balance targets — and it did so because soil drain had
been pushed above *every* allocation's recovery rate, so no town could grow past its fifty settlers.
Runs ended early, which the targets read as difficulty. `EconomyTest` ("even a Farming-8 people
exhaust their land"), `CouncilTest` ("0 buildings after fifty years") and `PersistenceTest` ("twelve
years without a birth") all failed at once and were right to. Outcome metrics measure what happened;
invariant tests measure whether the thing that happened was the game. Both gates, every time.

**AD-56 — Drain belongs inside the range of recovery rates.** That is what makes Farming decide
*sustainability* rather than merely speed: at a drain of 0.0060 against recovery of
`0.0022 + 0.00065 x Farming`, a Farming-3 people loses ground, a Farming-5 people roughly holds and a
Farming-8 people gains. Put the drain above the top of the band and the game stops growing
everywhere; put it below the bottom and over-farming is a no-op again.

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

**AD-27 — Citizens carry a personal political leaning, or the electorate is a hive mind.** People
in one town are in near-identical condition, so they compute near-identical needs and every
election came back 95-0 — which would make the Council screen, and the player's influence levers,
pointless. Each citizen now draws a small bias toward one category at birth. Elections are now
contested (typical split 35/29/23) while a famine or a raid still swings the town as a bloc.

**AD-28 — Unrest is a threshold, not a slope.** The first version raised unrest whenever
suffering x need-gap cleared 0.02, which almost any Premier managed: unrest saturated within a
year, every run, and the emigration and coups that followed (23 coups in 13 years) killed
colonies that M3 kept alive for centuries.

**AD-29 — Two workforce shares are protected from the agenda entirely.** Food, which no ideology
may starve below `MIN_FOOD_WORKER_SHARE`, and materials. A town that never gathers wood can never
build anything, and almost every building costs wood — one run quarried 1,250 stone, gathered 18,
and never laid a foundation in fifty years. Gatherers also now work toward whichever of wood or
stone the town is short of.

**AD-30 — Knowledge output is scaled, not the tier costs.** The design fixes both scholar output
(0.20/day) and the tier costs (120 x 2.6^tier), and the two contradict its own stated intent that
tier 6 be "a genuine grind that most runs don't reach": at face value a town of 150 reached tier 6
in about twelve years. The cost curve is the spine of the whole incremental layer, so it is kept
exactly as written and `KNOWLEDGE_OUTPUT_SCALE` (0.10) adjusts the other side. Tier 6 now lands
around year 200.

**AD-31 — The per-tick population index, and the O(population^2) trap.** Systems that need "this
civ's citizens" used to filter the whole living list, and two of them — care access in the
survival score, and housing slack in the conception check — did so *per citizen*. At 2,300 people
a tick cost 6.6ms against the 1ms budget 100x speed needs, and the cost was growing
super-linearly. The tick now builds one index per civ (members, healers, housed) and reads it:
**6.6ms → 0.59ms, and linear again.** Building site search was a second offender, O(cells x
buildings) per order; it now reads the world grid, which already records every footprint.

*Rule of thumb this established: anything called once per citizen per tick may not itself walk a
collection.* Worth checking against on every new system.

**AD-32 — All five civs are simulated at full detail.** The brief allows a coarser budget for
rivals outside the player's view (`Rivals.DETAIL_RADIUS_CELLS`). Measurement says it is not needed:
a five-civ tick with several thousand citizens costs ~0.6ms, and the same brief insists rivals
"use the same simulation code — not a fake stat-ticker". Two update paths would also be two places
for behaviour to drift. The constant is kept for M7, when phone-side 100x performance is
confronted properly.

**AD-33 — Personality reaches a rival's politics through its elections.** Rivals elect Premiers
like everyone else, so with purely random agendas nobody kept a standing army, nobody felt
threatened, nobody voted military, and 150-year runs contained zero wars — a stable, eventless
peace. A rival civ's candidates now run on the platform its people's character favours 55% of the
time (militants build armies, mercantiles build plazas). The player's civ has no such pull: its
politics is whatever its own condition makes it.

**AD-34 — Nobody trades with a civ that is raiding them.** Every pair traded every season, and
the goodwill exactly cancelled the resentment raids created, so tension could never climb to war.
Trade now stops above the raid threshold, which lets the escalation ladder actually work: trade,
friction, raids, war, exhaustion, peace, trade again.

**AD-35 — Aggression's military term is normalised against a reference army size.** The design's
formula uses the raw share of a civ under arms, but a realistic army is a tenth of a town, so that
term never exceeded 0.05 and aggression never approached the raid or war thresholds. A civ with
`MILITARY_SHARE_REFERENCE` (20%) of its people under arms now scores full marks on that term.
Gating war on aggression above 0.5 was over-constrained for the same reason and is now 0.30.

**AD-36 — The truce overflow.** `inTruce` computed `day - peaceMadeOn` against a `Long.MIN_VALUE`
sentinel for "no war has ever ended". That subtraction overflows to a negative number, which read
as *in truce* — so every pair of civs was permanently at truce and **no war was ever declared in
any run**, silently. The "never" case is now checked explicitly, and a test covers it. Worth
remembering: a sentinel that participates in arithmetic is a bug waiting for the right question to
be asked of it.

**AD-37 — The save is explicit DTOs, not the live objects.** A save file is a compatibility
contract, and a format that mirrors whatever the runtime classes happen to look like breaks the
moment a field is renamed. Only `SaveGame.kt` carries `@Serializable`, which also gives R8 one
small, obvious surface to keep rather than the whole domain (§16's warning about reflection-based
serialization and minification).

**AD-38 — Terrain is regenerated from the seed, and the save carries a hash of it.** Storing the
grid would double the file for data that is a pure function of the seed. The hash means a future
change to world generation fails the load loudly instead of quietly dropping a player's town onto
a different island.

**AD-39 — Restore order matters: buildings are placed before ownership is restored.**
`BuildingSystem.place` stamps its civ onto the cells a building covers — correct when it is built,
wrong on load, because a cell a rival later *worked* had changed hands since. Loading ownership
first and placing buildings after silently reverted 75 cells and diverged the run within 500
ticks. Derived grids (`buildingId`, `occupantId`) are rebuilt rather than saved, so there is only
ever one source of truth; ownership is not derived, so it is saved and applied last.

**AD-40 — The in-progress election is part of the save.** Saving inside the thirty-day campaign
window lost the candidate slate, so a reloaded run generated a different one from a different
point in the RNG stream and elected a different Premier. Invisible unless a test saves at an
awkward moment, which `every phase of the year round-trips` now does.

**AD-41 — Offline time does not run at 1x, and this is the number that sets the return cadence.**
Read literally, "convert elapsed real seconds to ticks" at the live rate means one hour away is
100 game years and the free 8-hour cap is 800 — more than twice the longest possible run.
Measured: three hours away ran 240 years, ended the run in Endurance, and took 48 seconds to
compute. Every check-in would finish the player's civilisation, and the Founders Pass 48-hour cap
would sell nothing, since 8 hours already exceeds any run.

| Away | At 1x (as written) | At `OFFLINE_TICKS_PER_REAL_SECOND` = 0.5 |
|---|---|---|
| 1 hour | 100 years | 5 years |
| 3 hours | 300 years | 15 years |
| 8 hours (free cap) | 800 years | 40 years |
| 48 hours (Founders) | 4,800 years | 240 years |

At 0.5 an absence is a chapter rather than the whole book: a 300-year run spans seven or eight
visits and the Pass is a real upgrade. Catch-up compute also falls from 48s to 1.2s for a
three-hour absence. **Worth a second opinion** — it is a design decision, not just a constant.

**AD-42 — Saves are gzipped on disk.** A 60-year run is 981KB of JSON and 141KB gzipped. The file
is dominated by two 16,384-element float arrays (soil fertility, wild game) that cannot be rounded
without breaking determinism, so compression is the only lever. `java.util.zip` exists on both the
JVM and Android, so this costs nothing in portability.

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
# The headless balance sweep (section 12). Exits non-zero if a target is missed.
./gradlew -Ppixeltown.simOnly=true :sim:balance
./gradlew -Ppixeltown.simOnly=true :sim:balance --args="--seeds=20 --csv=out.csv --only=farmer,hunter"

# Simulation only — works on any JDK 17+ machine, no Android SDK needed.
./gradlew -Ppixeltown.simOnly=true :sim:test     # JUnit 5 tests (~3 minutes)
./gradlew -Ppixeltown.simOnly=true :sim:build    # compile + tests + no-Android-imports check

# Can this machine build the Android app? Says exactly what is missing if not.
./scripts/check-android-env.sh

# Type-check app/ against the simulation API without the Android SDK.
./scripts/check-app-sources.sh

# Build the playable web version: :sim compiled to JavaScript.
./web/build-web.sh build/web-game        # then serve build/web-game/js + web/shell/index.html

# Export a run as frames + timeline for the web playback viewer (opt-in; ~90s).
PIXELTOWN_WEB_OUT=/tmp/web ./gradlew -Ppixeltown.simOnly=true \
  :sim:test --tests '*WebExporter*' --rerun-tasks

# Full build — requires the Android SDK and network access to dl.google.com.
./gradlew :app:assembleDebug
```

Per the brief: after every milestone, run the debug assemble and the tests, fix all failures,
then commit with a message naming the milestone. Do not move on with a red build.

## Milestone status

- **M0 — Skeleton.** Done. Modules, config, clock, RNG, canvas.
- **M1 — World & renderer.** Simulation side done and tested: generation, terrain, rivers,
  starting sites, the pixel renderer and the viewport. The Compose gesture layer
  (`WorldGestures.kt`) and the HUD are written but **unbuilt and unrun** — see below. "Zoom and
  pan smoothly at 60fps" is therefore not yet verified on a device.
- **M7 — Balance harness & tuning. Harness done; two of five targets met, and the gap is a design
  question rather than a constant.** `./gradlew -Ppixeltown.simOnly=true :sim:balance` runs the
  sweep (`--seeds=N`, `--only=a,b`, `--csv=path`). About 2,400 sims over ten sweeps; see AD-53 to
  AD-56 for what it found and the M7 tables below for where the numbers landed. **Not met, and why:
  Health and Elements contribute nothing to food throughput**, which is the binding constraint, so a
  people built on either subsists at its fifty founding settlers and dies — 19 collapses out of 20.
  Every dial that rescues them rescues the strong builds too, or starves the economy of non-food
  work, because the workforce food share is a fixed weight system (AD-25, AD-29). Making those two
  traits affect food *need* or *loss* would fix it and is a design decision, not a sweep.
- **M6 — Meta layer & persistence.** Done and tested. A versioned save that round-trips exactly
  (including mid-campaign and mid-war), gzipped on disk; offline catch-up through the same
  `step()` as live play; all four end states with Ascension outranking Endurance; Chronicle point
  scoring; seven permanent upgrades with the allocation cap enforced at +4 however much is spent;
  `RunConfig`, fixed at run start, which is what makes AD-8 testable. Gate met: a saved run
  resumed after an absence is byte-identical to one that was watched.
- **M5 — Rivals.** Done and tested. Relations with symmetric tension, derived posture, trade,
  tribute demands, border friction, raids and sustained war; armies that are real citizens
  marching across the map; battle as multi-day attrition with walls favouring the defender;
  safety in the survival score answering to the strongest hostile neighbour; a Rivals report for
  the UI. Gate met — see the table below. `sim/build/preview/war.png` shows a campaign in
  progress.
- **M4 — Buildings, the Premier, and the council.** Done and tested. The 20-building catalogue
  across five categories and six tiers, construction from builder output (half-built structures
  are inert), upkeep and ruin, housing, annual elections with a campaign window, candidate
  agendas/temperaments/pitch lines, the vote as a feedback loop on the town's condition, all four
  player influence levers, unrest with emigration and coups, and tech tiers. Gate met: a 50-year
  run produces ~63 buildings of 14 kinds and 50 contested elections.
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

### M4 pacing measurements

One farming run (seed 1), all five civs simulated:

| Year | Player pop | Tech tier | Buildings (kinds) |
|---|---|---|---|
| 50 | 131 | 3 | 63 (14) |
| 100 | 297 | 4 | 63 (14) |
| 200 | 1,329 | 6 | 63 (14) |

Tick cost with M4 systems active: 0.28ms at 460 people, 0.59ms at 2,300 — linear.

### M5 measurements

150-year runs, farming allocation, all five civs (counts are across the whole map):

| Seed | Final populations | Wars | Raids | Trades | Combat deaths | Civs extinct |
|---|---|---|---|---|---|---|
| 1 | 70 / 142 / 196 / 721 / 0 | 165 | 57 | 2,065 | 503 | 1 |
| 42 | 337 / 485 / 0 / 0 / 0 | 149 | 56 | 1,483 | 188 | 3 |
| 555 | 120 / 0 / 0 / 93 / 531 | 159 | 41 | 1,298 | 228 | 2 |

Rivals grow and decline independently, and civilisations really do die — on seed 42 only two of
five survive. The pressure is now strong enough that the M3 economy gate had to be split: those
tests run `civCount = 1` so a failure means the economy broke, not that someone invaded, and a
separate test asserts that sharing the map with four rivals costs something real.

**Wars are frequent** — roughly one per pair per decade. That is within the spirit of a hostile
map but is a prime candidate for the M7 harness to tune, along with the extinction rate.

Wealth now has sinks — trade pays for grain and tribute is extorted in it — though the sums are
still large. And a **Hunting-8 civ is still non-viable** — the
Premier now sets the food share, but the farm/hunt split inside it stays fixed (AD-25).

Against the §12 targets: runs were **too survivable** here, and the M7 harness is where that was
measured properly — see the M7 tables below. A naive spread came down from 254 years to 242 with a
sound economy, still short of the 80-140 target, while "a good one reaches 300 about one run in
three" now holds at 27%. Seed-to-seed variance is large (even spread
peaks at 102 on one seed and 1,237 on another), which is worth watching: some of it is map luck,
but some is famine cascades near a knife edge.

### M7 balance measurements

300 runs (15 allocations x 20 seeds), 300-year cap, all five civs.
Reproduce with `:sim:balance --args="--seeds=20 --csv=out.csv"`; CSVs in `sim/build/balance/`.

| allocation | mean y | median | reached 300y | peak pop | tech tier |
|---|---|---|---|---|---|
| farmer / pure-farming 3/4/3/4/8 | 264 | 300 | 60% | 309 | 4.3 |
| farm+elements 3/4/3/6/6 | 254 | 298 | 50% | 439 | 4.7 |
| warlike 4/3/7/3/6 | 248 | 295 | 45% | 390 | 4.2 |
| naive-even 5/5/5/5/5 | 242 | 300 | 55% | 266 | 3.9 |
| swift 8/4/3/3/5 | 226 | 249 | 35% | 349 | 4.0 |
| scholar-ish 7/5/3/3/5 | 210 | 258 | 45% | 263 | 3.4 |
| pure-speed 8/4/3/3/4 | 112 | 56 | 20% | 128 | 1.3 |
| hunter 5/4/8/3/3 | 95 | 26 | 15% | 159 | 1.5 |
| pure-hunting 3/4/8/3/4 | 80 | 2 | 15% | 112 | 0.9 |
| weathered 3/4/3/8/5 | 66 | 22 | 10% | 61 | 0.5 |
| **pure-health 3/8/3/4/4** | **3.6** | 0.5 | 0% | 50 | 0.0 |
| **pure-elements 3/4/3/8/4** | **2.0** | 1 | 0% | 50 | 0.0 |
| bad 8/3/3/3/1 | 0.0 | 0 | 0% | 50 | 0.0 |

Against the §12 targets:

| target | measured | |
|---|---|---|
| a naive even spread survives 80-140 years | 242y | FAIL |
| a reasoned allocation reaches 300y about 1 in 3 | 27% | **PASS** |
| no single trait at 8 is dominant on its own | 132x spread | FAIL |
| Ascension is rare without Chronicle upgrades | 12% | FAIL (was 31%) |
| mean run length on a first play is 25-45 minutes | 34.4 min | **PASS** |

Two things about reading this table. **Years survived is confounded by Ascension**, which ends a run
the moment tier 6 meets 400 people — making tier 6 harder converts year-200 ascensions into
year-300 endurances and *raises* mean run length. And **an 8-seed probe cannot confirm anything
here**: the naive distribution is bimodal, four runs dying between 71 and 102 years and ten reaching
the cap, which is map luck. Probes screen a change; only the full sweep measures it.

The five one-trait builds are the honest summary of what is left: Farming 264, Speed 112, Hunting
80, Elements 2.0, Health 3.6. Two of the five traits are not weak but unbuildable, and that is the
open item M7 hands on.

## Building `:app`

`:app` is compiled by CI (`.github/workflows/ci.yml`), or locally on a machine with the Android
SDK. Run `scripts/check-android-env.sh` first — it reports exactly what is missing and exits
non-zero if this machine cannot build the app.

```bash
./gradlew :app:assembleDebug     # needs the Android SDK + dl.google.com
./gradlew :app:assembleRelease   # R8 + resource shrinking; verify save/load on this artifact
```

CI has two jobs. **Simulation (JVM)** runs `-Ppixeltown.simOnly=true :sim:build` with no SDK at
all, and publishes the test report and the generated map PNGs as artifacts, so a change to world
generation is visible in the build rather than only in the numbers. **Android app** installs
platform 36 and build-tools 36, then assembles debug *and* release and runs lint. The release
assemble is deliberately on every push, not saved for release day: R8 has a habit of breaking
reflection-based serialization, and the save format is kotlinx.serialization.

### Known environment limitation

The sandbox this repository is developed in cannot reach `dl.google.com`, so **`:app` has never
been compiled here**. Routes that were checked and do not work:

- `maven.google.com` is reachable but 301-redirects to `dl.google.com`, which the network policy
  denies at CONNECT with a 403.
- Maven Central carries `com.android.tools.build:gradle` only up to 2.3.0 (2017) — useless for
  compileSdk 36.
- The Android SDK itself is only distributed from `dl.google.com`.

`scripts/check-app-sources.sh` narrows the gap: it runs the Kotlin compiler over `app/` against
`sim.jar` with no Android classpath, and fails on any error the missing SDK does not explain. It
proves the app sources parse and that every symbol they use from `:sim` resolves with compatible
signatures; it proves nothing about whether a Compose API is used correctly. It runs in the JVM
CI job and has been verified to fail on a deliberately wrong call into `:sim`.

So the first CI run is still the first real compile of `app/`. Treat its output as new information, not
as flakiness. Everything in `app/` that could be moved somewhere testable has been: the renderer,
the viewport, and the screen-pixel-to-world-cell conversion all live in `:sim` with tests, and
`app/` is left holding Compose plumbing, the bitmap upload, and the Activity.

Non-Android dependency versions in `gradle/libs.versions.toml` have been verified to exist on
Maven Central. **The Android ones (AGP 8.10.1, Compose BOM 2025.05.01, activity-compose 1.10.1,
core-ktx 1.16.0, lifecycle 2.9.0) could not be checked from here** and are the most likely cause
if the first CI run fails to resolve.

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
