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
│       ├── GameConfig.kt   Every tunable number in the game. One file, by design.
│       ├── Types.kt        Domain enums (terrain, jobs, traits, temperaments, end states).
│       ├── SimRandom.kt    The single seeded RNG threaded through everything.
│       └── SimClock.kt     Fixed-step accumulator: sim rate decoupled from frame rate.
└── app/          Android application — Compose UI, the pixel renderer, billing, persistence.
    └── src/main/kotlin/com/pixeltown/app/
        ├── MainActivity.kt  Compose root and the frame-driven tick loop.
        └── PixelCanvas.kt   IntArray → Bitmap.setPixels → drawImage(FilterQuality.None).
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
