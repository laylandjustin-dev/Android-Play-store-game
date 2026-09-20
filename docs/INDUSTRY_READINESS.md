# Industry-standard gap list

What stands between Pixel Town as it is now and a game that would not look out of place on the
Play Store. Ordered by what would block a release, not by effort. Every item names the evidence in
this repository rather than a general principle.

## 1. Blockers — the game cannot ship without these

**`:app` has never been compiled.** The sandbox cannot reach `dl.google.com`, so the whole Android
front end is unverified: `MainActivity.kt`, `PixelCanvas.kt` and `WorldGestures.kt` are 280 lines
that have parsed but never run. The playable build today is the web one. Until CI's Android job has
gone green once, every statement about the app is a hypothesis.

**There is no app identity.** `app/src/main/res` contains exactly one file, `values/strings.xml`.
No launcher icon, no adaptive icon, no splash, no app theme of its own (the manifest borrows
`@android:style/Theme.Material.NoActionBar.Fullscreen`). Play rejects an upload without an icon.

**The Android UI is a fraction of the web UI.** `web/shell/index.html` is 1,774 lines carrying six
screens, three modals, the people and relations tabs, the site survey and the allocation sheet.
`MainActivity.kt` is 157 lines: a canvas and a HUD. Everything built since M4 exists only in the
browser. This is the single largest piece of outstanding work in the project, and it is invisible
in the test suite because `:sim` is fully covered.

**M8 has not started.** No Play Billing, no product catalogue, no purchase or restore flow. The
entitlement interface exists (`Legacy.kt`, AD-8/AD-9) and nothing implements it. The Founders Pass
48-hour offline cap is referenced in tuning tables for a product that cannot be bought.

**No privacy policy, no data-safety declaration, no target-API paperwork.** Play console
requirements, not engineering, but they block the button.

## 2. Table stakes — present in essentially every shipped mobile game, absent here

**Sound.** Zero audio of any kind. The brief lists "sound hooks" under M7 and they were not built.
A pixel sim does not need a score, but a town with no audible feedback for a birth, a raid or an
election reads as a screensaver.

**Onboarding.** A new player is handed six traits, ten points, a 400x400 map and a landing choice
with no explanation of what any of it does. `TraitEffects` (AD-68) tells them what a point is worth
in numbers; nothing tells them what the game is. A first run that scripts the first two years —
land here, this is your food, this is your election — is the difference between a 30-second bounce
and a session.

**Settings.** No volume, no speed default, no haptics toggle, no "confirm before force war", no way
to turn the focus-player highlight on permanently. The web shell has a couple of toggles; there is
no settings surface at all.

**Save slots and cloud save.** One save file in app-private storage, written atomically. `allowBackup`
is `false` in the manifest, so a lost phone is a lost town. A 300-year run represents hours; players
expect it to survive a device change. Play Games Services saved games is the cheap answer.

**Notifications.** This is an idle game with an offline catch-up path (AD-41) tuned so an absence is
"a chapter rather than the whole book". Nothing tells the player their chapter is ready. A local
notification at the 8-hour cap is the entire retention loop of the genre and it is missing.

**Accessibility.** No content descriptions, no dynamic-type handling, no colour-blind consideration —
and colour now carries civ identity in both citizens (AD-52) and buildings (AD-76), so a deuteranope
cannot tell their town from a rival's. The eight swatches are tested for being distinct in RGB, not
for being distinct to a player. This is a real correctness bug in the one thing AD-48 set out to fix.

**Localisation.** `strings.xml` is English and the web shell hardcodes every string inline.

**Crash reporting and analytics.** Nothing. A balance harness that runs 1,026 simulations is useless
against the question "where do real players actually stop playing", and that question is the only one
that matters after launch. Funnel events for first run, first election, first building, first death,
run end, plus a crash reporter, before any further tuning by simulation.

## 3. Quality gaps the sweep already found

**The Farming cliff is the game's headline problem.** The 1,026-run sweep measures a 157x spread
between the best and worst single-trait build; a Gathering-8 people with Farming-3 survives 0.0 years.
Five of six traits are decoration. A player who reads the allocation screen honestly and picks
"builders" is handed a dead run. No amount of UI fixes this; it is a design decision about whether
Health, Elements, Speed and Gathering touch food throughput at all, and it is still open.

**All five balance targets in section 12 fail.** Recorded honestly in `CLAUDE.md`, which is right,
but a game that misses every one of its own stated balance goals is not tuned.

**55% of runs die inside five years.** A first-time player's most likely experience is losing in under
a minute of real time without being told why. Even if that is the intended difficulty, the game does
not currently explain a collapse — the end screen reports what happened, not what the player should
have done differently.

**Every balance table in `CLAUDE.md` is provisional.** The M3-M7 figures were measured at 128x128,
before footprints and before the trait lean. They describe a game that no longer exists and should be
re-measured or struck.

**Roads were requested and never built.** Dirt paths connecting houses, 2px white highways that cannot
be built over, 50% movement on stone. This needs an infrastructure layer in `World`, a movement cost in
pathing, generation, and renderer work. Given that AD-53 measured movement as the thing that decides
whether a people is viable at all, roads are not cosmetic — they are a balance lever, and probably the
cleanest available answer to the Farming cliff.

## 4. Engineering hygiene

**The balance harness writes its CSV only at the end.** A timeout loses the whole sweep. It should
stream rows.

**No screenshot or golden-image tests for the renderer.** Map PNGs are exported on every test run
(AD-16) and inspected by a human, which is better than nothing and not a gate. The renderer is the
one part of `:sim` whose bugs are invisible to assertions about numbers.

**No performance gate.** AD-20 warns that 100x speed needs a 1ms tick and a late-run colony on a phone
will not sustain it. That is recorded as a known issue and nothing fails the build when a tick gets
slower. A benchmark with a threshold would have caught AD-45's hash-lookup bug before the browser did.

**No R8 verification of the save format.** CI assembles release on every push specifically because R8
breaks reflection-based serialization — but nothing loads a save on the release artifact. The check
that matters is not "did R8 run" but "does a save written by the debug build load in the release one".

**`:app` has no tests at all.** Everything testable was moved into `:sim`, which was the right call;
the residue still deserves a couple of instrumentation tests once the module compiles.

## 5. What I would do, in order

1. Get CI's Android job green. Nothing else can be trusted until the app compiles.
2. Bring the Android UI to parity with the web shell. It is the bulk of the remaining work.
3. Fix the trait cliff. It is the difference between six choices and one.
4. Icon, sound, onboarding, notifications, settings, cloud save — the table stakes, roughly a
   fortnight together and the point at which this stops reading as a prototype.
5. Analytics and crash reporting, then stop tuning by simulation and start tuning by players.
6. Billing last. There is no point selling access to a game nobody has finished a run of.
