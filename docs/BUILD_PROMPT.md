# Claude Code Build Prompt — "Pixel Town" (Android simulation / incremental game)

Paste everything below into Claude Code as the initial prompt. It is written as instructions
to the agent, not as marketing copy. Work through the milestones in order and commit after each.

---

## 0. Your role and the rules of engagement

You are building a complete, shippable Android game from scratch. Read this whole document
before writing code.

**Non-negotiables:**

1. The simulation layer is **pure Kotlin with zero Android imports**. It lives in
   `com.pixeltown.sim` and must be runnable and testable on the JVM.
2. The simulation is **deterministic**. One seeded RNG instance threaded through everything.
   The same seed + the same player inputs = the same 500-year history, every time.
3. Every tunable number lives in one file: `sim/GameConfig.kt`. No magic numbers scattered
   through logic. I will be re-balancing constantly and I want one place to do it.
4. After every milestone: run `./gradlew :app:assembleDebug` and `./gradlew test`, fix all
   failures, then `git commit` with a message naming the milestone. Do not move on with a
   red build.
5. Write a `CLAUDE.md` in the repo root at Milestone 0 recording architecture decisions,
   module layout, and the build/test commands. Keep it updated.
6. Ask me before adding any third-party dependency not listed below.

**Stack:**

- Kotlin, min SDK 26, **target SDK 36 (Android 16)** — since 31 Aug 2026 Google Play has
  required new submissions to target API 36. Verify this is still current before release;
  the bar rises every August.
- Google Play Billing Library (latest stable) — pre-approved, see §14
- Jetpack Compose for UI; simulation rendered into an `ImageBitmap` via direct pixel writes
  (`IntArray` → `Bitmap.setPixels`) drawn with `Canvas.drawImage` and
  `FilterQuality.None` for crisp nearest-neighbour scaling. **Do not** draw citizens as
  individual Compose shapes — you'll be pushing thousands of them.
- `kotlinx.coroutines` for the tick loop, `kotlinx.serialization` for saves
- Persistence: single JSON save file in app-private storage, written atomically
  (write temp, fsync, rename). No Room, no SQLite.
- Tests: JUnit 5 + kotlin.test on the JVM, simulation only.

---

## 1. The game in one paragraph

Every person is one pixel. You start by spending **10 points** across five inherited traits,
then 50 settlers drop onto a procedurally generated map and start living: working, eating,
pairing, giving birth, ageing, and dying. Each has a **survival score** that governs their
odds day to day. Once a year the town elects a **Premier** who decides what gets built —
farms, health, military, tech, lifestyle — and each choice bends the civilisation's growth
curve, wealth, and posture. Meanwhile four AI civilisations are running the same simulation
on the same map, and they will trade with you, extort you, or come for your grain. You mostly
watch. You intervene through influence, not micromanagement. When your town finally collapses
or transcends, you bank Chronicle points and start again stronger.

---

## 2. Milestones

Build in this order. Each milestone must be playable/inspectable at its end.

### M0 — Skeleton
Gradle project, module structure, `CLAUDE.md`, empty Compose activity showing a black
128×128 canvas scaled to fit. `SimClock` running at 10 ticks/sec logging tick count.
**Done when:** app installs and logs ticks.

### M1 — World & renderer
Procedural map generation, terrain types, the pixel renderer, camera (pinch zoom 1×–8×, pan).
**Done when:** a generated island renders and I can zoom/pan smoothly at 60fps.

### M2 — Citizens & the life cycle
Citizen struct, spawning 50 settlers, survival score, hunger, ageing, death, birth, movement.
No jobs yet — they wander and starve. **Done when:** a seeded 50-person colony reliably dies
out in a predictable number of days and the unit test asserts it.

### M3 — Economy & jobs
Resources, job assignment, farming/hunting/gathering/building/idle, food stores, stat effects.
**Done when:** with a decent Farming allocation the colony stabilises and slowly grows for
200+ years; with a bad one it collapses.

### M4 — Buildings, the Premier, and the council
Building catalogue, construction, the annual election, Premier agendas, player influence.
**Done when:** a full 50-year run produces a town with varied buildings and a readable
election history.

### M5 — Rivals
Four AI civilisations, abstracted-but-fair simulation, diplomacy, trade, raids, war.
**Done when:** rivals grow and decline independently and can plausibly destroy me.

### M6 — Meta layer & persistence
Save/load, offline catch-up, collapse/ascension end states, Chronicle points, permanent
upgrades, new-run flow. **Done when:** I can close the app for 3 hours, reopen it, and the
world has correctly advanced.

### M7 — Polish & balance
UI pass, sound hooks, a headless balance harness, tuning. Details in §11.

### M8 — Monetisation
Play Billing integration, entitlement store, the product catalogue, purchase/restore flows,
the paywall surfaces. Details in §14. **Done when:** all products purchase and restore
correctly against Play's licence-testing accounts, and entitlements survive reinstall.

### M9 — Retention systems
Daily return loop, notifications, the "while you were away" report, milestone hooks.
Details in §15. **Done when:** a fresh install produces a sensible 7-day return cadence
with no notification spam.

### M10 — Store release
Signing, Play Console setup, listing assets, data safety, policy compliance, internal
testing track. Details in §16. **Done when:** a signed AAB is live on the internal track
and installable from Play.

---

## 3. The starting allocation

Five traits. Each starts at **base 3**. You get **10 points**. Max 8 in any one trait.
These are genetic to your people and apply for the whole run.

| Trait | What it does |
|---|---|
| **Speed** | Work output rate, movement speed, build speed, research speed. `workMultiplier = 0.55 + 0.15 × Speed` |
| **Health** | Max HP, disease resistance, lifespan. `lifespan = 48 + 3.0 × Health` years, `diseaseResist = 0.06 × Health` |
| **Hunting** | Yield from wild tiles, and doubles as base military effectiveness. `huntYield = 0.4 + 0.18 × Hunting` |
| **Elements** | Resistance to weather, seasons, and disasters. Reduces cold/heat/storm penalties by `0.11 × Elements` |
| **Farming** | Yield from farm tiles, soil recovery, livestock. `farmYield = 0.5 + 0.16 × Farming` |

Present this as the first screen: five rows with `−` / `+`, a live preview panel showing the
derived numbers above, and a one-line archetype label that updates as you allocate
(e.g. heavy Hunting + Speed → "Raiders"; heavy Farming + Health → "Settlers"; balanced →
"Generalists"). The label is cosmetic — do not gate anything on it.

**Trait interactions to implement:** Elements is a multiplier on both Farming and Hunting
output during harsh seasons, so a Farming-8 / Elements-3 civ boom-and-busts, while
Farming-5 / Elements-5 is steadier. Make sure this is actually true in the numbers.

---

## 4. The world

- Grid: **128 × 128** cells. One citizen occupies one cell and is drawn as one pixel.
- Terrain per cell: `OCEAN, BEACH, PLAIN, FOREST, HILL, MOUNTAIN, RIVER, MARSH`.
- Generate with value-noise elevation + moisture, then classify. Rivers carved by steepest
  descent from high-moisture peaks to ocean. Seed-driven.
- Per-cell mutable state: `fertility` (0–1), `wildGame` (0–1, regenerates), `ownerCivId`,
  `buildingId?`, `occupantId?`.
- Fertility drains where farms overwork it and recovers over time; recovery rate scales with
  the owning civ's Farming trait. Over-farming is a real failure mode — I want to see towns
  exhaust their land.

**Colour palette (renderer):** terrain in muted earth tones; citizens drawn on top as bright
pixels tinted by civ colour, brightness modulated by survival score so a starving town
visibly dims. Buildings are 2×2 or 3×3 blocks in a saturated accent colour. Player civ is
warm gold; rivals get distinct hues (crimson, teal, violet, pale green).

---

## 5. The citizen

```kotlin
data class Citizen(
    val id: Int,
    var x: Int, var y: Int,
    val civId: Int,
    val sex: Sex,
    var ageDays: Int,
    var hp: Float,            // 0..maxHp
    var nutrition: Float,     // 0..1
    var morale: Float,        // 0..1
    var survival: Float,      // 0..100, derived each tick
    var job: Job,
    var skill: Float,         // 0..1, grows with time in job
    var influence: Float,     // 0..1, drives election candidacy
    var partnerId: Int?,
    var pregnantUntilDay: Int?,
    var homeBuildingId: Int?
)
```

Store citizens in a **struct-of-arrays** backing store if the naive version drops frames past
~3,000 agents. Measure first; don't prematurely optimise.

### Time and speed controls

1 tick = 1 day. 360 days = 1 year (12 × 30-day months, 4 seasons). Base rate at 1× is
10 ticks/sec.

| Speed | Ticks/sec | 1 year takes | 100 years takes |
|---|---|---|---|
| Pause | 0 | — | — |
| **1×** | 10 | 36 s | 1 h |
| **10×** | 100 | 3.6 s | 6 min |
| **100×** | 1,000 | 0.36 s | 36 s |

Requirements:

- **Decouple sim rate from frame rate.** Render stays at 60fps regardless of speed. Use a
  fixed-step accumulator: each frame, add `deltaTime × ticksPerSecond` to an accumulator and
  drain it in whole ticks. At 100× that's ~17 ticks per frame, batched in one loop with the
  renderer untouched until the batch completes.
- **Determinism is unaffected by speed.** Tick N must produce identical state whether it was
  reached at 1× or 100×. The accumulator must never produce partial or skipped ticks. Cover
  this with a test: run 50,000 ticks at each speed setting and assert identical final state.
- **Cap the catch-up.** If a frame stalls, drain at most 200 ticks in one frame and carry the
  rest — never let the accumulator spiral.
- **Throttle expensive per-tick work above 10×.** Skip renderer pixel writes on all but the
  final tick of a batch, and downsample the Chronicle event feed (aggregate rather than
  listing every death). Simulation logic itself is never skipped or approximated.
- **Speed is sticky** — persist it in the save and restore it on resume.
- Show the current date and speed in the World HUD. At 100× surface a compressed ticker
  ("Year 112 · 4 births, 6 deaths, harvest poor") instead of the individual event feed, and
  **auto-drop to 1×** on any major event: election result, war declaration, raid, disaster,
  coup, or tech tier unlock. The player should never miss the dramatic moments because they
  left it running fast. Make the auto-drop toggleable in settings.

### Survival score

Recomputed each tick, 0–100:

```
survival = 34×healthNorm
         + 26×nutrition
         + 14×shelterQuality
         + 10×safety
         +  8×careAccess
         +  8×morale
         − seasonPenalty
         − ageFrailty
```

- `healthNorm = hp / maxHp`
- `shelterQuality` from housing buildings in range, 0 if homeless
- `safety` from military strength vs. nearest rival threat, plus walls
- `careAccess` from clinic capacity per capita
- `seasonPenalty = seasonSeverity × (1 − 0.11 × Elements)`, seasonSeverity peaks in winter
- `ageFrailty` = 0 until 60% of lifespan, then rises to 25 at lifespan

### Death

```
pDeath = 0.00035 × (1 − survival/100)^2.2 × ageMortalityMultiplier
```
Plus discrete causes: starvation (nutrition at 0 for 12+ consecutive days), disease events,
combat, disaster, old age (hard cap at `lifespan × 1.3`). Log every death with a cause to a
rolling **Chronicle** buffer — this feeds the events feed in the UI and makes the town feel
authored.

### Birth

Eligible: female, age 16–42, partnered, `survival > 58`, town food surplus > 0, and housing
slack available.
```
pConceive = 0.0028 × (survival/100) × housingSlack × fertilityBonus
```
Gestation 270 days. Newborns consume 0.45 food until 14, count as `Job.CHILD`, and inherit
civ traits directly (no genetics system — keep it simple).

### Pairing
Unpartnered adults within 12 cells with compatible age windows pair up with low daily
probability. On partner death, a widow/widower can re-pair after 180 days.

### Movement
Citizens path toward their job target with simple greedy movement plus jitter. No A*; the map
is open enough. They must not stack — one occupant per cell, resolve collisions by picking a
free neighbour.

---

## 6. Jobs and the economy

**Resources:** `FOOD`, `WOOD`, `STONE`, `KNOWLEDGE`, `WEALTH`.

**Jobs:** `CHILD, FARMER, HUNTER, GATHERER, BUILDER, SOLDIER, SCHOLAR, HEALER, ARTISAN, IDLE`.

Assignment is automatic each week, driven by the Premier's priority weights plus shortfall
pressure (if food stock < 10 days of consumption, force-reassign toward food regardless of
the Premier's wishes — hunger overrides politics).

**Output per worker per day:**

```
FARMER   = cellFertility × farmYield × skill × workMultiplier × seasonMod
HUNTER   = cellWildGame  × huntYield × skill × workMultiplier
GATHERER = 0.35 × workMultiplier × skill            // wood/stone from forest/hill
BUILDER  = 1.0 × workMultiplier × skill             // build points
SCHOLAR  = 0.20 × workMultiplier × skill × libraryBonus  // knowledge
HEALER   = raises careAccess for nearby citizens
ARTISAN  = 0.30 × workMultiplier × skill            // wealth
SOLDIER  = no output; contributes to military strength
```

`skill` rises 0→1 over ~6 years in the same job and decays if reassigned. Reassignment has a
cost — I want players to feel that whiplash Premiers are bad for the economy.

**Consumption:** 1.0 food/adult/day, 0.45/child. Food spoils at 2%/day above storage capacity.
Granaries raise capacity.

---

## 7. Buildings

Every building: `id, type, x, y, footprint, buildPointsRequired, woodCost, stoneCost, upkeepWealth`.
Construction consumes builder output over time; a half-built building does nothing.

| Category | Buildings | Effects |
|---|---|---|
| **Farms** | Field, Granary, Irrigation, Mill | Fertility use, storage cap, seasonMod floor, food→wealth conversion |
| **Health** | Hut, Clinic, Hospital, Aqueduct | careAccess, disease resistance, lifespan bonus, infant survival |
| **Military** | Watchtower, Barracks, Wall, Armoury | Military strength, safety, raid deflection, rival deterrence |
| **Tech** | Workshop, Library, Academy, Observatory | Knowledge rate, unlock tiers, build speed, yield multipliers |
| **Lifestyle** | Housing, Plaza, Temple, Theatre | Housing slack, morale, influence growth, birth rate, unrest damping |

**Tech tiers:** spend `KNOWLEDGE` to unlock tiers 1–6. Each tier unlocks better buildings in
every category and applies a global multiplier. Tier costs scale `120 × 2.6^tier`. This is the
long-arc incremental spine — make sure tier 6 is a genuine grind that most runs don't reach.

---

## 8. The Premier (the heart of the game)

Every 360 days, an election.

**Candidates:** the 3 citizens with the highest `influence` (influence grows with age, skill,
morale, and proximity to Plazas/Temples). Each candidate is generated with:

- A name, age, and job history
- An **agenda**: a normalised weight vector over the five categories
- A **temperament**: `PASSIVE / PRAGMATIC / AMBITIOUS / ZEALOT`, which sets how far they'll
  deviate from need-based building and how they respond to rivals
- A short pitch line generated from their agenda and temperament (template-based, not an LLM)

**The vote:** citizens vote based on how well each agenda matches their own felt needs
(hungry citizens vote farms; sick citizens vote health; frightened citizens vote military).
So the electorate is a feedback loop on the state of the town. This is the bit that has to
feel alive — make the vote breakdown visible.

**The player's role:** you don't pick the Premier. You spend **Influence points** (accrued
from Plazas/Temples/morale) to:

- Endorse a candidate (+vote weight)
- Petition the sitting Premier to shift one agenda weight for a year
- Veto one building order per year
- Call an emergency referendum (expensive)

**The Premier's term:** at term start they set annual build priorities and job weights from
their agenda, modulated by their temperament and current crises. They act once per season
(4 decision points/year), not every tick.

**Unrest:** if survival is low and the Premier is ignoring the cause, unrest climbs. High
unrest → work output penalty, emigration, and at the extreme a coup that installs the highest-
influence citizen mid-term.

---

## 9. Rival civilisations

Four AI civs on the same map. They use the **same simulation code** — not a fake stat-ticker —
but with a coarser update budget: full detail within 24 cells of any player-visible area,
aggregated arithmetic elsewhere. They must be able to genuinely out-develop me.

Each rival gets randomised traits from the same 10-point pool, plus an AI personality
(`ISOLATIONIST / MERCANTILE / EXPANSIONIST / MILITANT`).

**Posture** is derived, not hardcoded:
```
aggression = 0.5×militaryShare + 0.3×(1 − foodSecurity) + 0.2×personalityBias
```
So a starving militarist attacks; a fat mercantile civ trades.

**Interactions, resolved at season boundaries:**
- **Trade:** surplus-for-deficit resource swaps; raises both parties' wealth and lowers tension
- **Tribute demand:** pay food/wealth or accept a tension spike
- **Border friction:** competing claims on fertile cells
- **Raid:** small combat, steals food, kills a handful of pixels
- **War:** sustained; armies actually walk across the map as pixel columns and fight

**Combat resolution:**
```
strength = Σ soldiers × (0.4 + 0.18×Hunting) × skill × techMultiplier × wallDefenceBonus
```
Resolve as attrition over several days with a random component, not a single dice roll. Show
it on the map — this should be the most visually dramatic thing in the game.

---

## 10. Meta progression

**End states:**
- **Collapse** — population hits 0
- **Conquest** — a rival takes your last settlement
- **Endurance** — survive 300 years
- **Ascension** — reach tech tier 6 with population > 400

**Chronicle points** awarded on any end state:
```
chronicle = floor( (peakPopulation/10) + (yearsSurvived/4) + (techTier^2 × 6) + endStateBonus )
```

Spend permanently on: extra starting allocation points (expensive, capped at +4), extra
starting settlers, a starting building, faster skill growth, higher starting influence,
a fertility floor, better rival relations.

**Offline progress:** on resume, compute elapsed real seconds, cap at 8 hours, convert to
ticks, and fast-forward in a batched loop with rendering disabled. Show a "while you were
away" summary: years passed, births, deaths, elections, what got built, what the rivals did.
This must use the exact same code path as live simulation — no separate approximation.

---

## 11. UI screens

1. **Allocation** — the opening 10-point screen (§3)
2. **World** — the pixel map, resource bars, speed controls (pause / 1× / 10× / 100×, as
   large always-visible buttons), current date, live Chronicle event feed
3. **Council** — sitting Premier, their agenda, term record, influence spending, election UI
4. **Ledger** — population/food/wealth/knowledge graphs over time, cause-of-death breakdown,
   past Premiers and what they built
5. **Rivals** — the four AI civs, known strength, tension meters, active treaties
6. **Legacy** — Chronicle points and permanent upgrades, shown between runs
7. **Store** — Chronicle bundles, unlocks, Founders Pass, Restore Purchases. Reachable from
   Legacy and from a small persistent icon, never as a pop-up that interrupts a run. Show
   localised prices from `ProductDetails`, and state subscription price, period, and trial
   terms on the button itself

Visual direction: dark UI chrome so the pixel map is the brightest thing on screen. Monospace
or a pixel font for numbers. Minimal chrome, no gradients, no rounded-everything. The map is
the game; the UI is a dashboard around it.

---

## 12. Balance harness (build this, I'll need it)

A JVM entry point `sim/harness/BalanceRunner.kt` that runs N headless simulations across a
grid of trait allocations and prints a CSV: seed, allocation, years survived, peak population,
final tech tier, end state, dominant Premier temperament.

**Balance targets to tune against:**
- A naive 2/2/2/2/2 spread survives ~80–140 years
- A well-reasoned allocation reaches 300 years perhaps 1 run in 3
- No single trait at 8 should be dominant on its own
- Ascension should be rare without Chronicle upgrades
- Mean run length on a first play: 25–45 real minutes

Run at least 200 headless sims and iterate on `GameConfig.kt` until these hold. Report the
numbers back to me with the CSV.

---

## 13. Tests I expect

- Determinism: same seed × 10,000 ticks → identical population, resources, building list
- Speed invariance: 50,000 ticks at 1×, 10×, and 100× produce byte-identical final state
- Starvation: zero-food colony dies out within the expected window
- Stability: Farming-8 colony on fertile land sustains 200+ years without intervention
- Election: highest-influence citizens become candidates; vote tallies sum to the voting pop
- Birth/death rates fall inside expected bands over a 100-year run
- Save → load → tick produces the same state as tick without the save round-trip
- Offline catch-up of 8h matches 8h of live ticking exactly
- Combat resolution is symmetric and conserves casualties
- Entitlements are read only at run-start: a purchase mid-run leaves the in-flight
  simulation byte-identical
- Purchased allocation points cap at +4 no matter how many are granted
- Consumable grant → app kill → relaunch → balance survives; non-consumable restores after a
  simulated reinstall

---

## 14. Monetisation architecture

Build this as a clean layer, not as hooks sprinkled through the simulation. One interface:

```kotlin
interface Entitlements {
    fun has(product: ProductId): Boolean       // non-consumables & subscription
    fun balanceOf(currency: Currency): Int     // consumable-granted currency
    suspend fun consume(currency: Currency, amount: Int): Boolean
}
```

The simulation reads entitlements **only at run-start**, when it snapshots the starting
configuration. Nothing mid-run queries the billing layer. This keeps determinism intact and
means a purchase can never corrupt an in-flight save.

**Hard design rule: everything purchasable must also be earnable.** Chronicle points are the
only meta-currency. You can grind them or buy them. There is no purchase-only power, no
purchase-only content that affects outcomes, and no rival-facing advantage. This is a
single-player game, so the only person a whale outpaces is themselves — which is fine, but
the moment something is *only* buyable, the review scores go.

### Product catalogue

**Consumables** (Chronicle point bundles — the core currency):

| Product | Grant | Notes |
|---|---|---|
| `chronicle_small` | 250 pts | The impulse tier |
| `chronicle_medium` | 800 pts | Best-value flag goes here |
| `chronicle_large` | 2,400 pts | |
| `chronicle_huge` | 7,000 pts | |

**Consumables** (direct utility):

| Product | Effect |
|---|---|
| `timewarp_24h` | Instantly simulate 24 game-hours of offline progress, using the real tick loop |
| `timewarp_7d` | Same, 7 days |
| `rebirth_token` | Restart a run keeping current tech tier discoveries |

**Non-consumables** (one-time unlocks):

| Product | Effect |
|---|---|
| `speed_100x` | Unlocks the 100× speed tier (free tier gets pause / 1× / 10×) |
| `remove_ads` | If ads ship at all; see below |
| `palette_pack` | Cosmetic terrain and civ colour schemes. Pure cosmetic |
| `extra_save_slots` | Three parallel civilisations instead of one |

**Subscription** — `founders_pass`, monthly with a 7-day free trial:
- Offline catch-up cap raised from 8h to 48h
- 150 Chronicle points granted monthly
- 100× speed included
- All cosmetics
- Detailed Ledger analytics and full Chronicle history export

Set actual prices in Play Console, not in code. Fetch localised price strings from
`ProductDetails` and never hardcode a currency symbol anywhere in the UI.

### Where "extra evolution points" fit

You asked for buyable evolution points. Route them through Chronicle points rather than
selling allocation points directly:

- Chronicle points buy permanent upgrades, including `+1 starting allocation point`
- That upgrade costs 400 / 900 / 2,000 / 4,500 Chronicle points for the 1st–4th
- **Cap stays at +4 regardless of spend.** At +4 you have 14 points instead of 10, which is
  a real advantage but not a solved game

The cap matters. Uncapped stat purchases turn a simulation into a spreadsheet you pay to win,
and the runs stop being interesting — which kills the retention the purchases were meant to
create.

### Billing implementation requirements

- Google Play Billing Library, latest stable. Digital goods **must** use Play Billing —
  taking payment any other way gets the app removed.
- Handle the full lifecycle: `queryProductDetails` → `launchBillingFlow` → `PurchasesUpdated`
  → grant → **`acknowledgePurchase` within 3 days** (unacknowledged purchases auto-refund) →
  `consumeAsync` for consumables only.
- Call `queryPurchasesAsync` on every app foreground to catch purchases completed out of band
  and to restore entitlements after reinstall.
- Implement an explicit **Restore Purchases** button. Reviewers look for it.
- Handle every failure path visibly: cancelled, network down, pending (slow payment methods
  in some markets take days), already-owned, item-unavailable.
- **Validation:** entitlements are stored locally and are therefore spoofable on a rooted
  device. Accept this for v1 — it's single-player, there's no economy to protect, and a
  backend is not worth it yet. Store entitlements obfuscated (not plaintext JSON), and keep
  the `Entitlements` interface backend-ready so server-side receipt validation via the Play
  Developer API can be added later without touching call sites. Write this trade-off into
  `CLAUDE.md`.
- Test with Play Console licence-testing accounts and the static test SKUs before any real
  product goes live.

### On ads

My recommendation: **no ads in v1.** A contemplative pixel simulation and interstitial video
are a bad match, and ads force an SDK that complicates the data-safety declaration. If you
want ad revenue later, add **rewarded video only** — opt-in, granting a small Chronicle
stipend or a single offline catch-up — and never interstitials or banners over the map.
Build the `Entitlements` layer so `remove_ads` can be switched on without refactoring.

---

## 15. Retention design

Retention in an idle sim comes from the world continuing without you and being worth coming
back to. Build that, and the purchases follow. Do not build engagement traps.

- **The return report is the hook.** "While you were away" should read like a chronicle, not a
  receipt: 3 Premiers, a war with the Teal civ, a famine in year 88, a child born who became
  the best farmer your town has seen. Generate this from the Chronicle event buffer with
  narrative templates. This is the single most important retention feature — treat it that way.
- **Offline cap creates the return cadence.** 8 hours free means twice-daily check-ins are
  natural. The Founders Pass 48h cap sells to people who don't want to be on that leash,
  which is the honest version of this mechanic.
- **Notifications, sparingly.** Local notifications only, max one per day, all individually
  toggleable, with a clean opt-out and no dark patterns. Trigger on: offline cap reached,
  war declared, Premier election, run ended, tech tier unlocked. Ask for
  `POST_NOTIFICATIONS` permission in context, after the first run ends — never on cold start.
- **Milestone pacing.** First Chronicle points within ~10 minutes of first play. First
  permanent upgrade affordable by the end of run one. The first run should *end* — a first
  session that never resolves gives the player nothing to come back for.
- **Long arc.** Tech tier 6 and Ascension should take 20+ runs. That's the retention spine;
  purchases compress it, they don't replace it.
- **Analytics.** Instrument run length, end-state distribution, session count, the screen a
  player was on when they quit, and paywall view-to-purchase. Use a privacy-light option and
  declare everything you collect (§16). You cannot tune any of this blind.

---

## 16. Play Store release checklist

- **Signing:** upload key + Play App Signing. Store the keystore outside the repo; put the
  path in `local.properties` and `.gitignore` it. Never commit a keystore or a signing
  password — tell me if you need one and I'll generate it.
- **Build:** Android App Bundle (`.aab`), R8 minification on, resource shrinking on. Verify
  the release build still simulates identically to debug — R8 has a habit of breaking
  reflection-based serialization, so test save/load on the release artifact specifically.
- **Play Console setup:** app created, content rating questionnaire (this will rate low —
  no violence beyond abstract pixels, but declare in-app purchases honestly), target
  audience declaration, data safety form, privacy policy URL (**required** the moment you
  ship IAP or analytics), ads declaration, Play Families policy if the target audience
  includes under-13s.
- **Listing assets:** app icon 512×512, feature graphic 1024×500, at least 4 phone
  screenshots, short description (80 chars), full description. Lean into the pixel aesthetic
  — screenshots of the map mid-war will sell this better than any copy.
- **Account deletion:** not required if there are no user accounts, and there shouldn't be.
  Keep it that way for v1; it removes a whole compliance surface.
- **Release track discipline:** internal → closed → production. Do not ship straight to
  production. Closed testing has a minimum tester count and duration for new developer
  accounts — check the current requirement in Play Console before you plan the timeline.
- **Compliance to re-verify at release time** (policies change, do not trust this document):
  target API level, the Play Billing requirement for digital goods, subscription disclosure
  rules (price, billing period, and trial terms must be visible *before* purchase), and
  data safety requirements. Check `support.google.com/googleplay/android-developer` and
  report anything that has moved.

---

## 17. Start here

Begin with **M0**. Write `CLAUDE.md` first, then the skeleton, then show me the module layout
and `GameConfig.kt` before you go further. I want to sign off on the constants file before
you build on top of it.
