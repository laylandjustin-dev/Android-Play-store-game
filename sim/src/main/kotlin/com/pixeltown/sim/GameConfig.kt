package com.pixeltown.sim

/**
 * Every tunable number in Pixel Town lives here. Nothing else in [com.pixeltown.sim] may
 * contain a magic number; logic reads from this object so the whole game can be rebalanced
 * from one file.
 *
 * Conventions:
 *  - Time is measured in ticks. 1 tick = 1 day.
 *  - Probabilities are per-day unless the name says otherwise.
 *  - Traits are integers 1..8; derived stats are expressed as `base + perPoint * trait`.
 *
 * Values here are the first-pass numbers taken from the design document. They have not yet
 * been through the balance harness (M7); expect them to move.
 */
object GameConfig {

    // ---------------------------------------------------------------- time

    object Time {
        const val DAYS_PER_MONTH = 30
        const val MONTHS_PER_YEAR = 12
        const val DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR // 360
        const val SEASONS_PER_YEAR = 4
        const val DAYS_PER_SEASON = DAYS_PER_YEAR / SEASONS_PER_YEAR // 90

        /** Ticks per real second at 1x. Speed tiers multiply this. */
        const val BASE_TICKS_PER_SECOND = 10.0

        /** Available speed multipliers. 100x is gated behind an entitlement (see §14). */
        val SPEED_MULTIPLIERS = intArrayOf(0, 1, 10, 100)
        const val SPEED_TIER_REQUIRING_ENTITLEMENT = 100

        /** Never drain more than this many ticks in a single frame; carry the remainder. */
        const val MAX_TICKS_PER_FRAME = 200

        /** Above this multiplier the renderer only paints the final tick of a batch. */
        const val RENDER_THROTTLE_ABOVE_SPEED = 10

        /** Above this multiplier the Chronicle feed aggregates instead of listing events. */
        const val CHRONICLE_AGGREGATE_ABOVE_SPEED = 10
    }

    // ---------------------------------------------------------------- traits

    object Traits {
        const val COUNT = 6
        const val BASE_VALUE = 3
        const val ALLOCATION_POINTS = 10
        const val MIN_PER_TRAIT = 1

        /**
         * The hard ceiling a trait can ever reach, and the cap on what the opening screen may put
         * into one. Two different numbers on purpose.
         *
         * The brief fixes the opening allocation at "max 8 in any one trait", and that still holds:
         * it is what stops a player dumping their whole budget into Farming on turn one. But the
         * decade growth point (AD-50) ran into that same 8 and stopped, so a people finished
         * growing around year 150 and the back half of a long run had nothing left to decide. The
         * ceiling is now 20 and a civilisation keeps developing for as long as it survives.
         */
        const val MAX_PER_TRAIT = 20
        const val ALLOCATION_MAX_PER_TRAIT = 8

        /** Chronicle-bought bonus allocation points. Hard cap, regardless of spend (§14). */
        const val MAX_PURCHASED_ALLOCATION_POINTS = 4

        /**
         * A people grows: every this many years each civilisation earns
         * [GENERATION_POINTS_PER_AWARD] trait points. The player spends theirs from the council
         * screen; rivals spend theirs on the spot. `MAX_PER_TRAIT` is the real ceiling — from the
         * opening 10 points there are 15 more to win, so a civ tops out around year 150.
         */
        const val GENERATION_INTERVAL_YEARS = 10
        const val GENERATION_POINTS_PER_AWARD = 1


        /**
         * workMultiplier = base + perPoint * Speed.
         *
         * M7 tuning. The design's 0.55 + 0.15 made Speed the dominant trait by a distance: it
         * multiplies *every* kind of work, food included, so the 200-sim sweep found survival
         * tracking `workMultiplier x farmYield` almost exactly and a Speed-8 build out-living
         * every considered allocation. Narrowing the range from 2.5x (0.70..1.75) to 1.6x
         * (0.86..1.42) leaves Speed clearly worth having without making it the only real choice.
         */
        const val WORK_MULT_BASE = 0.78
        const val WORK_MULT_PER_SPEED = 0.095

        /**
         * Cells per day a citizen can move, scaled by Speed.
         *
         * M7 tuning, and the answer to the sweep's hardest question. Two builds with *identical*
         * Farming 4 came out 199 years (Speed 8) and 3.8 years (Speed 3) — a 52x difference that
         * the work multiplier cannot explain, since narrowing it left Speed 8 only 39% more
         * productive per working day. The gap was movement: work is spatial (AD-21) and a worker
         * only counts as working when standing on or beside their cell (AD-22), so at 1.14 cells a
         * day a slow people spends most of its life walking to a field. Making over-farming real
         * amplified it, because a rotating town keeps having to walk somewhere new.
         *
         * At 1.20 + 0.11 the range narrows from 1.8x (1.14..2.04) to 1.3x (1.53..2.08): the fast
         * keep their edge and the slow are no longer unable to farm at all. This is the third time
         * in this tuning pass that the answer has been to raise a floor rather than lower a
         * ceiling, which is the pattern worth remembering — a trait that gates viability is a trait
         * players cannot choose against.
         */
        const val MOVE_SPEED_BASE = 1.20
        const val MOVE_SPEED_PER_SPEED = 0.11

        /**
         * fertilityMultiplier = 1 + perPoint * (Health - base).
         *
         * A healthy people carry more pregnancies to term. Measured from the base value, like
         * [RATION_REDUCTION_PER_HEALTH], so a Health-3 people conceive at exactly the documented
         * rate and the trait reads as a change in both directions.
         */
        const val FERTILITY_PER_HEALTH = 0.07

        // lifespan = 48 + 3.0 * Health   (years)
        const val LIFESPAN_YEARS_BASE = 48.0
        const val LIFESPAN_YEARS_PER_HEALTH = 3.0

        /**
         * Individual variation, on top of the traits a whole people share.
         *
         * A civ's traits say what its people are like; [Citizen.vigour] says how this one turned
         * out. It is drawn around 1.0 at birth and it moves work output, hit points, disease
         * resistance and fighting strength together, so a strong citizen is a better farmer *and*
         * a better soldier — one constitution, not five unrelated numbers.
         *
         * Children inherit the average of their parents' vigour plus a fresh draw, so a town's
         * people drift over generations rather than being resampled from scratch each birth. The
         * spread is deliberately modest: this is meant to make individuals legible, not to swamp
         * the trait allocation the player spent ten points on.
         */
        const val VIGOUR_MIN = 0.80
        const val VIGOUR_MAX = 1.20
        const val VIGOUR_INHERITANCE = 0.65
        const val VIGOUR_MUTATION = 0.14

        // diseaseResist = 0.06 * Health
        const val DISEASE_RESIST_PER_HEALTH = 0.06

        const val MAX_HP_BASE = 60.0
        const val MAX_HP_PER_HEALTH = 8.0

        /**
         * The demand side of the food balance, and the piece the five traits were missing.
         *
         * Every trait fed *production*: farm yield, hunt yield, work rate, movement. Liebig's law
         * says a population is limited by its scarcest resource, so with only one side of the
         * ledger represented, the two traits best at producing food were the only two that could
         * matter — measured as Farming-8 lasting 246 years against Health-8's 21, and Sugarscape
         * found exactly the same convergence in its own agents. Its two traits are vision and
         * *metabolism*: how far an agent can see, and how much it has to eat. The second was the
         * one with no counterpart here.
         *
         * Health is now metabolism. A hardy people eat less per head, which is a contribution to
         * the same balance sheet as farming better and is orthogonal to it — supply against demand
         * (Will Wright's orthogonal differentiation, rather than five traits varying along one
         * axis). Elements is the same idea against the season: a cold people eat more to stay warm,
         * and a weathered one does not, so Elements removes the winter surcharge rather than
         * lowering the baseline.
         */
        const val RATION_REDUCTION_PER_HEALTH = 0.09
        /**
         * A surcharge is a tax on *everybody*, and only its avoidance belongs to Elements, so it
         * cannot be sized freely: at 0.85 — the figure that would have matched Health's discount —
         * the winter bill collapsed four of the six probe builds, taking the Hunting build from 38
         * years to zero. It stays where a town can pay it, and Elements is made worth its points on
         * the other side of the ledger instead, through [ELEMENTS_FOOD_STORAGE_BONUS].
         */
        const val WINTER_RATION_SURCHARGE = 0.40

        /**
         * Extra food storage per point of Elements, as a fraction of the base capacity.
         *
         * A benefit that taxes nobody: a weathered people keep their harvest through the winter
         * rather than watching it spoil, which is the same trait doing the same job from the supply
         * side. It matters precisely when the seasonal trough is deepest, which is when an Elements
         * people should be visibly better off than their neighbours.
         */
        const val ELEMENTS_FOOD_STORAGE_BONUS = 0.14

        // huntYield = 0.4 + 0.18 * Hunting  (also the base military effectiveness term)
        const val HUNT_YIELD_BASE = 0.4
        const val HUNT_YIELD_PER_HUNTING = 0.18

        /**
         * farmYield = 0.5 + 0.16 * Farming, exactly as the design specifies.
         *
         * M7 tried raising the floor here to rescue the builds that could not feed themselves, and
         * the attempt is recorded because of what it cost. Lifting a poor farmer from 1.14 to 1.34
         * moved a Health-8 people from 0.1 years to only 3.8, while making a *Farming-1* colony
         * survive 57 years — breaking the M3 gate that a people who cannot farm collapses inside
         * 30. A linear `base + perPoint` cannot deliver a fatal Farming 1, a viable Farming 4 and
         * the design's 1.78 at Farming 8 all at once, and the endpoint is the design's to set.
         *
         * The trap turned out not to live here at all: narrowing movement (see MOVE_SPEED_BASE)
         * took the same build from 3.8 years to 24.4. Reverted, with the measurement kept.
         */
        const val FARM_YIELD_BASE = 0.5
        const val FARM_YIELD_PER_FARMING = 0.16

        /**
         * Wood and stone gathered per point of Gathering.
         *
         * The sixth trait, and the one the other five kept implying: almost every building costs
         * wood, a town that cannot gather it never builds anything (AD-29), and until now no trait
         * touched that at all — gathering was flat whoever you were. A people can now be as good
         * at working timber and stone as they are at farming.
         */
        const val GATHER_YIELD_BASE = 0.55
        const val GATHER_YIELD_PER_GATHERING = 0.15

        /**
         * buildRate = base + perPoint * Gathering.
         *
         * Yield alone would have made Gathering a resource dial rather than a build: a town
         * short of wood simply puts more people on gathering. Tying construction speed to the
         * same trait gives it one legible identity — *this is the people who build things* — and
         * it is the trait the fifty-year building gate (AD-50) actually answers to. Scaled, like
         * gathering, so a base-3 people builds at exactly the old rate.
         */
        const val BUILD_RATE_BASE = 0.70
        const val BUILD_RATE_PER_GATHERING = 0.10

        // Elements reduces cold/heat/storm penalties by 0.11 per point.
        const val ELEMENTS_PENALTY_REDUCTION_PER_POINT = 0.11

        /**
         * Elements also damps the seasonal swing applied to Farming and Hunting output, which is
         * what makes Farming-8 / Elements-3 boom-and-bust while Farming-5 / Elements-5 is steady.
         */
        const val SEASON_SWING_AMPLITUDE = 0.55
        const val SEASON_SWING_DAMP_PER_ELEMENTS = 0.11

        /**
         * Soil recovery per day, scaling with the owning civ's Farming trait.
         *
         * M7 raised both. Drain alone (see `FERTILITY_DRAIN_PER_FARM_DAY`) made over-farming real
         * but turned it into a *movement* race: with recovery this slow, a stripped field never
         * came back, so a town had to keep walking to new ground and every Speed-3 build in the
         * sweep collapsed while the even spread — Speed 5 — became the strongest allocation in the
         * game. Fast recovery turns the same pressure into rotation instead of migration: a cell
         * worked for six months is restored by three months of rest, which a slow people can do as
         * well as a quick one. Speed should buy reach, not decide whether farming works at all.
         *
         * The band is then positioned *around* the drain rate, which is what makes Farming decide
         * sustainability: at 0.0022 + 0.00065 a Farming-3 people recovers 0.0042 against a drain of
         * 0.0060 and loses ground, a Farming-5 people 0.0055 and roughly holds, a Farming-8 people
         * 0.0074 and gains. Lowering the band under a fixed drain is the difficulty dial that does
         * not create a cliff — it squeezes a town big enough to work most of its land, and a colony
         * of fifty never notices.
         */
        const val FERTILITY_RECOVERY_BASE = 0.0022
        const val FERTILITY_RECOVERY_PER_FARMING = 0.00065

        /**
         * Soil recovery per point of Elements, on top of [FERTILITY_RECOVERY_PER_FARMING].
         *
         * Land recovers because weather lets it. Before this, recovery answered to Farming alone,
         * which made Farming a pass/fail gate rather than a strength: against a drain of
         * `FERTILITY_DRAIN_PER_FARM_DAY` (0.0060), recovery of `0.0022 + 0.00065 x Farming` crosses
         * the drain between Farming 5 and 6, and the sweep's survival curve has its knee in exactly
         * that place — 14.8 years at Farming 4, 39.9 at 5, 101.6 at 6. A people that did not buy
         * Farming died whatever else it bought, which is the failure AD-54 names.
         *
         * Elements now buys *sustainability without yield*: an Elements people works land it can
         * keep, but each worked cell still produces at its own `farmYield`. That is a different
         * proposition from Farming, which buys both, rather than a second copy of it.
         */
        const val FERTILITY_RECOVERY_PER_ELEMENTS = 0.00055
    }

    // ---------------------------------------------------------------- world

    object World {
        const val WIDTH = 400
        const val HEIGHT = 400

        const val STARTING_SETTLERS = 55

        /** Settlers are founded as adults of working age, spread across this range. */
        const val SETTLER_MIN_AGE_YEARS = 17
        const val SETTLER_MAX_AGE_YEARS = 38

        /** Share of founding settlers who arrive already partnered. */
        const val SETTLER_PARTNERED_SHARE = 0.55

        /**
         * Skill the founding settlers arrive with, 0..1.
         *
         * They were farmers and hunters somewhere before they emigrated, so starting them at zero
         * was never right — and it is what made the opening decade a pass/fail quiz. A colony's
         * first year is decided entirely by the output of fifty untrained people, so any build a
         * point short of self-sufficiency died before its long-run advantages could appear at all:
         * the `ConstraintProbe` shows a Health-8 people dying of starvation in year zero with 86% of
         * its deaths there, never living long enough for its lifespan or its disease resistance to
         * mean anything.
         *
         * Arriving competent lifts the whole founding decade without touching the steady-state
         * yield curve, so it softens the cliff without making a poor farmer into a good one — a
         * Farming-1 people still cannot feed itself however skilled it is.
         */
        const val SETTLER_STARTING_SKILL = 0.55f

        /** How far from the home site settlers are scattered when a civ is founded. */
        const val SETTLEMENT_SPAWN_RADIUS = 7
        const val RIVAL_CIV_COUNT = 4
        const val TOTAL_CIV_COUNT = RIVAL_CIV_COUNT + 1
        const val PLAYER_CIV_ID = 0

        /**
         * Longest colony name the game will display. The HUD, the Chronicle feed and the rivals
         * list all have to fit it on a phone, so this is a layout limit rather than a data one.
         */
        const val MAX_COLONY_NAME_LENGTH = 24

        // Value-noise elevation / moisture generation.
        const val NOISE_OCTAVES = 5
        const val NOISE_BASE_FREQUENCY = 0.045
        const val NOISE_LACUNARITY = 2.0
        const val NOISE_PERSISTENCE = 0.5

        /**
         * Terrain is classified by *quantile*, not by absolute elevation. Noise fields vary a lot
         * between seeds: a fixed sea level gave land fractions from 0.27 to 0.45 at the same
         * threshold, so some seeds were archipelagos and others were continents. Slicing the
         * sorted elevation field instead means every map has a comparable amount of usable land
         * while the *shape* of the island still varies freely with the seed.
         */
        const val TARGET_LAND_FRACTION = 0.46

        /** Shares of the land, taken from the elevation extremes inward. */
        const val BEACH_SHARE_OF_LAND = 0.14
        const val MOUNTAIN_SHARE_OF_LAND = 0.07
        const val HILL_SHARE_OF_LAND = 0.16

        /** Shares of the land, taken from the wettest end of the moisture field. */
        const val MARSH_SHARE_OF_LAND = 0.10
        const val FOREST_SHARE_OF_LAND = 0.34

        /** Rivers are carved from the N wettest peaks by steepest descent to ocean. */
        const val RIVER_SOURCE_COUNT = 6
        /**
         * River sources are ranked by how far inland they are, because that is what decides river
         * length — ranking by elevation alone put sources on coastal peaks and produced 8-cell
         * streams. Moisture is the tiebreak, so the wettest of the deep-inland candidates wins.
         */
        const val RIVER_SOURCE_MIN_DISTANCE_FROM_OCEAN = 8
        const val RIVER_SOURCE_MOISTURE_WEIGHT = 6.0
        const val RIVER_MAX_LENGTH = 400

        /**
         * Flow is routed on a blurred copy of the elevation field. Steepest descent on raw value
         * noise stalls in the first pit it meets — rivers came out 5-8 cells long — while a
         * smoothed field carries the same descent all the way to the coast.
         */
        const val RIVER_FLOW_SMOOTHING_PASSES = 4

        /** How far uphill a river may breach to escape a pit, in smoothed elevation units. */
        const val RIVER_BREACH_TOLERANCE = 0.004

        /** Radial island falloff: elevation is multiplied down toward the map edge. */
        const val ISLAND_FALLOFF_START = 0.55
        const val ISLAND_FALLOFF_POWER = 2.2

        /** Minimum distance in cells between civ starting sites. */
        /**
         * How far apart five civilisations are planted, in cells.
         *
         * Chosen from how far a town *grows*, not from the proportion of the map it sits on — and
         * that distinction is the whole lesson of the 400x400 resize. Scaling 34 to 110 preserved
         * the geometry of the old island perfectly and broke M5: border friction (the first rung of
         * the escalation ladder, AD-34) requires two civs' owned cells within
         * [Rivals.BORDER_FRICTION_RADIUS] of each other, and at 110 apart their territories never
         * touched at all. Tension could then only ever *ease*, through trade, so 120-year runs
         * contained no raid, no war and not one combat death — exactly the silent, eventless peace
         * AD-33 and AD-34 were each written to fix.
         *
         * At roughly twice [Buildings.BASE_SITE_DISTANCE] a colony is unmistakably its own place on
         * day one, and ordinary growth brings its borders into contact with a neighbour's inside a
         * normal run. The generator relaxes the figure in steps when an island cannot fit five sites,
         * so it costs nothing on a cramped map.
         */
        const val MIN_CIV_START_SEPARATION = 70

        /** Radius of the fertility/game window used to score candidate starting sites. */
        const val CIV_START_SCORE_RADIUS = 6

        /** Random jitter added to a site's score so the best spot is not always the same one. */
        const val CIV_START_SCORE_JITTER = 0.25
    }

    /** Per-terrain base fertility and wild game, and whether the cell is walkable/buildable. */
    object Terrain {
        val FERTILITY = mapOf(
            TerrainType.OCEAN to 0.0,
            TerrainType.BEACH to 0.10,
            TerrainType.PLAIN to 0.85,
            TerrainType.FOREST to 0.55,
            TerrainType.HILL to 0.35,
            TerrainType.MOUNTAIN to 0.0,
            TerrainType.RIVER to 0.0,
            TerrainType.MARSH to 0.45,
        )

        val WILD_GAME = mapOf(
            TerrainType.OCEAN to 0.25,
            TerrainType.BEACH to 0.15,
            TerrainType.PLAIN to 0.35,
            TerrainType.FOREST to 0.90,
            TerrainType.HILL to 0.55,
            TerrainType.MOUNTAIN to 0.20,
            TerrainType.RIVER to 0.60,
            TerrainType.MARSH to 0.50,
        )

        /**
         * Wild game regrows toward the terrain base at this fraction of capacity per day, and one
         * day of hunting takes this fraction of what is on the cell.
         *
         * The first values (0.4% regrowth against 2% depletion) made hunting self-defeating: a
         * hunter stripped a cell in weeks, moved on, and a hunting people starved in their first
         * year. Regrowth now outpaces a single hunter, so a hunting economy is sustainable —
         * though still a lower ceiling than farming, which is the intended shape.
         */
        const val WILD_GAME_REGEN_PER_DAY = 0.010
        const val HUNT_DEPLETION_PER_DAY = 0.008

        /**
         * Fertility drained per farmer-day worked on a cell.
         *
         * M7 tuning, and the sweep's most useful finding. At 0.0035 against a recovery of
         * 0.0032/day for a Farming-5 people, a cell worked every day of the year lost 0.0003 —
         * over-farming broke even, so the failure mode AD-21 was designed around did not exist and
         * nothing ever stopped a comfortable town. Recovery applies to every cell every day while
         * drain applies only to worked ones, so this number is the whole constraint.
         *
         * It must also sit *inside* the range of recovery rates, and a later pass in this milestone
         * broke that: at 0.0085 against a Farming-8 people's 0.0078 recovery, no allocation in the
         * game could sustain a worked field, so towns stopped growing past their fifty settlers and
         * built nothing in fifty years. `EconomyTest` caught it, and the number it was asserting is
         * the right invariant — drain between the worst and best recovery is what makes Farming
         * decide *sustainability* rather than merely speed. At 0.0060 a Farming-3 people runs a
         * deficit, a Farming-5 people breaks even, and a Farming-8 people gains: the trait sheet's
         * promise that farmers restore their land, made real.
         *
         * Raised from 0.0060 to 0.0076 when Elements joined the recovery formula
         * ([FERTILITY_RECOVERY_PER_ELEMENTS]). That term adds 0.00165 to *every* build, base
         * included, so leaving the drain alone would have been a difficulty cut dressed as a trait
         * fix — the mistake AD-53 records in the other direction. The figure holds the baseline
         * deficit where it was (0.0018 at Farming 3 / Elements 3) while giving each trait its own
         * side of the line:
         *
         * | build | recovery | vs drain |
         * |---|---|---|
         * | Farming 3, Elements 3 (base) | 0.0058 | loses ground, exactly as before |
         * | Farming 6, Elements 3 | 0.0078 | holds |
         * | Farming 8, Elements 3 | 0.0091 | gains |
         * | Farming 3, Elements 8 | 0.0086 | gains — the new route |
         */
        const val FERTILITY_DRAIN_PER_FARM_DAY = 0.0076

        /**
         * The fertility of freshly chosen farmland, used as the reference the farm share is measured
         * against.
         *
         * A town on ground this good farms exactly the share it always did, so every balance
         * measurement taken before soil feedback existed still describes the opening of a run. As
         * worked fertility falls below it the town moves people to the range and the woods, in
         * proportion. Measured, not guessed: farmers' mean worked fertility on day 1 came out at
         * 0.738-0.759 across the builds traced while this was being fixed.
         */
        const val PRISTINE_WORKED_FERTILITY = 0.75

        /**
         * The same two tables as flat arrays indexed by [TerrainType.ordinal].
         *
         * Land regeneration touches all 16,384 cells every tick and read both maps per cell —
         * 32,768 hash lookups a tick. The JVM hides that; compiled to JavaScript it was the single
         * most expensive thing in the game, costing more than every citizen put together.
         */
        val FERTILITY_BY_TERRAIN: FloatArray =
            FloatArray(TerrainType.entries.size) { FERTILITY.getValue(TerrainType.entries[it]).toFloat() }

        val WILD_GAME_BY_TERRAIN: FloatArray =
            FloatArray(TerrainType.entries.size) { WILD_GAME.getValue(TerrainType.entries[it]).toFloat() }

        /** Fertility below this makes a farm cell not worth working. */
        const val FERTILITY_ABANDON_THRESHOLD = 0.12

        /** Per-cell random variation applied to the terrain base values at generation time. */
        const val FERTILITY_JITTER = 0.12
        const val WILD_GAME_JITTER = 0.15
    }

    // ---------------------------------------------------------------- survival

    object Survival {
        const val W_HEALTH = 34.0
        const val W_NUTRITION = 26.0
        const val W_SHELTER = 14.0
        const val W_SAFETY = 10.0
        const val W_CARE = 8.0
        const val W_MORALE = 8.0

        const val MIN = 0.0
        const val MAX = 100.0

        /** Seasonal severity by season index (0 = spring .. 3 = winter), before Elements. */
        val SEASON_SEVERITY = doubleArrayOf(2.0, 1.0, 4.0, 14.0)

        /** Age frailty is 0 until this fraction of lifespan, then rises to [AGE_FRAILTY_MAX]. */
        const val AGE_FRAILTY_ONSET_FRACTION = 0.60
        const val AGE_FRAILTY_MAX = 25.0

        /** Shelter quality for a citizen with no home. */
        const val SHELTER_QUALITY_HOMELESS = 0.0
        const val HOUSING_RANGE_CELLS = 16

        /**
         * Safety with no known threat and no walls. Not 1.0: an unwalled settlement on an open map
         * is never entirely safe, and rivals should be able to push this down (M5).
         */
        const val SAFETY_BASELINE = 0.5

        /** Care access with no healers and no clinics. */
        const val CARE_BASELINE = 0.0
    }

    // ---------------------------------------------------------------- life cycle

    object Life {
        // pDeath = BASE * (1 - survival/100)^EXPONENT * ageMortalityMultiplier
        const val DEATH_BASE = 0.00035
        const val DEATH_SURVIVAL_EXPONENT = 2.2

        /** Hard age cap as a multiple of lifespan. */
        const val MAX_AGE_LIFESPAN_MULTIPLE = 1.3

        /** Mortality multiplier ramps from 1.0 at frailty onset to this at lifespan. */
        const val AGE_MORTALITY_MULTIPLIER_AT_LIFESPAN = 6.0
        const val INFANT_MORTALITY_MULTIPLIER = 2.2
        const val INFANT_AGE_DAYS = 3 * Time.DAYS_PER_YEAR

        /** Nutrition recovers this fast on a full ration and drains this fast on nothing. */
        const val NUTRITION_GAIN_PER_FED_DAY = 0.25
        const val NUTRITION_LOSS_PER_HUNGRY_DAY = 0.12

        /** Chance per day that an idle citizen wanders to a neighbouring cell. */
        const val WANDER_CHANCE_PER_DAY = 0.35

        /** Consecutive days at zero nutrition before starvation can kill. */
        const val STARVATION_DAYS = 12

        /**
         * Once past [STARVATION_DAYS], chance per day of dying of it. A hard threshold killed an
         * entire famine-struck colony on the same day, because uniform rationing leaves every
         * citizen in an identical state; a daily roll spreads the collapse over about a week
         * without letting anyone die of starvation sooner than the design allows.
         */
        const val STARVATION_DAILY_DEATH_CHANCE = 0.30

        /** Nobody survives starvation past this many days at zero nutrition. */
        const val STARVATION_CERTAIN_DEATH_DAYS = 26
        const val HP_LOSS_PER_STARVING_DAY = 4.0
        const val HP_REGEN_PER_FED_DAY = 0.8

        // Births
        const val FERTILE_AGE_MIN_YEARS = 16
        const val FERTILE_AGE_MAX_YEARS = 42
        const val BIRTH_MIN_SURVIVAL = 58.0
        /**
         * Daily conception chance for an eligible woman.
         *
         * The design's 0.0028 was measured and is far too high against a 14-year childhood: it
         * drove the child share of a colony to 65% within a decade, and a workforce that small
         * could not feed the dependants. An even-spread colony died in 5-11 years and a farming
         * colony ran away to 1,700 people. At 0.0012 the child share settles near 0.30, an
         * even-spread colony lives for centuries at ~90 people, and a farming colony reaches
         * 230-310 — which puts the 400-population Ascension condition back in reach as a
         * stretch rather than a formality.
         */
        const val CONCEIVE_BASE = 0.0019

        /**
         * Housing slack for a town with no housing at all. Not zero: a colony has to be able to
         * grow enough to build its first house.
         */
        const val HOUSING_SLACK_WITHOUT_HOUSING = 0.5
        const val GESTATION_DAYS = 270
        const val CHILD_UNTIL_YEARS = 14

        /** What a child contributes when they help in the fields. They are not idle, but they are children. */
        const val CHILD_WORK_FRACTION = 0.30

        // Pairing
        const val PAIR_SEARCH_RADIUS_CELLS = 12
        const val PAIR_MIN_AGE_YEARS = 16
        const val PAIR_MAX_AGE_GAP_YEARS = 14
        const val PAIR_DAILY_PROBABILITY = 0.006
        const val WIDOW_REPAIR_DELAY_DAYS = 180

        // Disease. The background rate: a citizen falls ill occasionally and recovers.
        const val DISEASE_EVENT_BASE_CHANCE = 0.0004
        const val DISEASE_HP_DAMAGE = 18.0

        /**
         * Epidemics: the pressure Health actually answers.
         *
         * The `ConstraintProbe` measured illness at **0% of deaths** across every build over sixty
         * years. At a 0.0004 daily chance of losing 18 HP against 84 that regenerates, a citizen is
         * struck once every seven years and always heals first, so Health's disease resistance was
         * defending against something that could not happen. One of the five traits was therefore
         * decoration, which is what made Health-8 a death sentence rather than a style of play.
         *
         * Crowd disease is the historically correct check on a pre-modern town, and it is
         * density-dependent: the chance of an outbreak rises with how many people live together, so
         * this is pressure that bites a *successful* colony. That also happens to be the shape the
         * §12 targets need, where a comfortable naive spread was living 240 years unchallenged.
         *
         * An outbreak runs for [EPIDEMIC_DAYS] and infects a share of the town each day, resisted by
         * Health (0.06 per point, so 0.48 at Health 8) and by healers and hospitals through the same
         * care term the survival score uses.
         */
        const val EPIDEMIC_DAILY_CHANCE_AT_REFERENCE = 0.0022
        const val EPIDEMIC_POPULATION_REFERENCE = 200.0
        /**
         * Below this many people an outbreak cannot take hold. Epidemiology's critical community
         * size: a crowd disease needs a large enough pool of susceptibles to sustain a chain of
         * transmission, and a hamlet does not have one. At 25 the founding colony of fifty was
         * catching plagues in its first decade, which killed the very builds this pressure exists to
         * make playable. At 80 it is a pressure on a *town*, which is the point — it constrains
         * success rather than survival.
         */
        const val EPIDEMIC_MIN_POPULATION = 80
        const val EPIDEMIC_DAYS = 70
        /**
         * Tuned against `HP_REGEN_PER_FED_DAY` (0.8), which is what a first attempt missed: at a 5%
         * daily chance of 9 damage a fed citizen out-heals the outbreak and illness stayed at 0%.
         * At 18% of 12 a Health-3 people loses about 1.0 HP a day net and the frail among them die,
         * while a Health-8 people roughly breaks even and comes through it. An epidemic should thin
         * a town, not erase it.
         */
        const val EPIDEMIC_DAILY_INFECTION_CHANCE = 0.13
        const val EPIDEMIC_HP_DAMAGE = 12.0

        /** Care — healers and hospitals — removes up to this much of the infection chance. */
        const val EPIDEMIC_CARE_MITIGATION = 0.45

        /**
         * Winter exposure: the pressure Elements answers.
         *
         * Measured at 8-11% of deaths and only as a *label* — `EXPOSURE` was what the generic
         * mortality roll was called when the citizen was neither old nor starving, so no mechanic
         * belonged to Elements at all and no amount of it saved anybody. The harsh season now
         * carries its own daily risk, scaled by the season's severity and reduced by
         * `elementsShelter` (0.11 per point, so 0.88 at Elements 8) and by having a roof.
         *
         * A roof matters as much as the trait does, which is what ties Elements to the building
         * layer rather than leaving it a private stat.
         */
        const val EXPOSURE_DAILY_DEATH_CHANCE = 0.00006
        const val EXPOSURE_HOUSED_MULTIPLIER = 0.25

        /** Morale drifts toward this baseline; buildings and events push it around. */
        const val MORALE_BASELINE = 0.55
        const val MORALE_DRIFT_PER_DAY = 0.004

        // Skill
        const val SKILL_YEARS_TO_MASTERY = 6.0
        const val SKILL_DECAY_PER_DAY_WRONG_JOB = 0.0008
        /** Skill retained when a citizen is reassigned to a different job. */
        const val SKILL_REASSIGNMENT_RETENTION = 0.35

        // Influence
        const val INFLUENCE_GROWTH_PER_DAY = 0.00035
        const val INFLUENCE_PER_SKILL = 0.25
        const val INFLUENCE_PER_MORALE = 0.20
        const val INFLUENCE_PLAZA_RANGE_CELLS = 14
        const val INFLUENCE_PLAZA_BONUS = 0.0004
    }

    // ---------------------------------------------------------------- economy

    object Economy {
        /**
         * Food a civ is founded with, per settler. At 1 food/adult/day this is the grace period.
         *
         * M7 raised it from 20. A build that cannot quite feed itself used to die in year zero —
         * fifty settlers, one bad harvest, gone before the player had seen anything happen. The
         * allocation screen then reads as a pass/fail quiz with an invisible answer. At 34 days a
         * marginal colony instead limps through its first year and declines where the player can
         * watch it, which is the difference between a lesson and a shrug. It changes nothing for a
         * build that feeds itself: a working colony never touches the bottom of this store.
         */
        const val STARTING_FOOD_PER_SETTLER = 34.0

        const val FOOD_PER_ADULT_PER_DAY = 1.0

        /**
         * Below this many days of food in store, a town counts as hungry — used when a growth
         * point is spent on the town's behalf rather than by the player.
         */
        const val HUNGRY_TOWN_FOOD_DAYS = 20.0
        const val FOOD_PER_CHILD_PER_DAY = 0.45

        /** Food above storage capacity spoils at this fraction per day. */
        const val SPOILAGE_PER_DAY_OVER_CAPACITY = 0.02

        /**
         * How much food a town can hold, in days of rations *per head*, before the surplus starts to
         * rot. Granaries add to it; Elements multiplies it.
         *
         * This was a flat 400 for every town, from fifty-five settlers to a city of three thousand,
         * and that flat number was a bug with a very visible symptom. A colony is founded with
         * `STARTING_FOOD_PER_SETTLER` x 55 = 1,870 food against a capacity of 400 — so 78% of the
         * founding stores were above the line and rotting at 29 food a day, and the food number fell
         * every single day for the first hundred days of every run no matter what the player did.
         * Reported, correctly, as "food drains continuously and does not go up".
         *
         * Two constants tuned in separate rooms, and the comment on `STARTING_FOOD_PER_SETTLER` says
         * what the intent was: 34 days of grace so that a marginal colony "limps through its first
         * year and declines where the player can watch it". A town that can only *hold* seven days
         * never had that grace. Storage now scales with the population that has to eat it, and the
         * figure is deliberately the same 34: a colony can keep exactly what it landed with.
         *
         * Spoilage still does its job. It was never the thing limiting growth — soil fertility is
         * (AD-56) — and its purpose is to stop a town hoarding indefinitely, which "about a month's
         * food per person" enforces just as well as a flat wall, at every size of town rather than
         * only at the smallest.
         */
        const val FOOD_STORAGE_DAYS_PER_CITIZEN = 34.0

        /**
         * A floor under the above, so a town reduced to a handful of survivors is not also told its
         * remaining stores are rotting. Sized at the old flat value.
         */
        const val MIN_FOOD_STORAGE_CAPACITY = 400.0

        /**
         * Below this many days of food stock, job assignment is forced toward food.
         *
         * M7 tried raising this to 30 days, reasoning that ten days of stock is already a death
         * spiral and a nearly-viable people should react while it still can. Measured, it was much
         * worse: 30 days of stock is a *normal* amount, so the override became the permanent state,
         * 95% of every workforce stood in the fields forever, and with no gatherers or builders the
         * towns never housed anyone and never grew. Health-8 went from 6.2 years to 0.8.
         *
         * Both of those were symptoms of it being a *threshold* at all. It is now the bottom of a
         * continuous ramp: see `EconomySystem.foodWeighted`. Kept as the point at which the food
         * share is at its maximum.
         */
        const val FOOD_CRISIS_DAYS_OF_STOCK = 10

        /**
         * Stores at which a town stops worrying and spares people for everything else. Between this
         * and zero the food share slides smoothly from [MIN_FOOD_WORKER_SHARE] up to
         * [CRISIS_FOOD_WORKER_SHARE].
         */
        const val FOOD_COMFORTABLE_DAYS_OF_STOCK = 55.0

        /** Jobs are reassigned once per week. */
        const val JOB_REASSIGN_INTERVAL_DAYS = 7

        /**
         * Competence floor for an unskilled worker.
         *
         * The design writes output as `... x skill`, but skill starts at 0 and takes six years to
         * mature, so a literal reading has a new colony produce nothing at all and starve before
         * anyone learns their trade. Effective competence is therefore
         * `floor + (1 - floor) * skill`: a beginner works at this fraction of a master's rate.
         */
        const val SKILL_OUTPUT_FLOOR = 0.45

        /**
         * How far a worker will look for a cell to work, measured from where they stand.
         *
         * Searching from the town centre with a weak distance penalty sent workers on 20-cell
         * marches to marginally better soil: at ~1 cell/day only a handful ever arrived and the
         * colony starved with full fields around it. Workers now look near themselves.
         */
        /**
         * Food produced by one worker-day at a multiplier of 1.
         *
         * The design writes farm output as `fertility x farmYield x skill x workMultiplier x
         * seasonMod`, which at baseline lands near 1.0 — exactly one person's daily ration. A
         * colony where one farmer feeds one person can never staff anything but farms, so the
         * formula needs an absolute scale: a competent farmer feeds roughly two and a half people,
         * which is what leaves room for hunters, gatherers, builders and scholars.
         *
         * M7 tried lowering both to 2.35 to make the game harder, and measured the opposite: a
         * naive spread went from 241 years to 273 and its peak population from 212 to 306, while
         * the marginal builds went from dying in year 3 to dying in year 0. Less food per worker
         * means a smaller town, and a smaller town does not over-farm itself into a famine — so
         * food throughput is a *cliff* dial, not a difficulty dial. It sets who can feed
         * themselves at all, and is left where AD-23 put it.
         */
        const val FARM_OUTPUT_SCALE = 3.0
        const val HUNT_OUTPUT_SCALE = 3.0

        /**
         * How much a candidate work cell's value is discounted per cell of walking distance.
         * Steep on purpose: take the decent field next door over the perfect one a fortnight away.
         */
        const val WORK_DISTANCE_PENALTY = 0.30

        const val WORK_SEARCH_RADIUS = 12

        /**
         * Extra cells of work-search radius per point of Speed above base.
         *
         * Speed raised output and movement, and neither helped a people survive: output is drained
         * out of the soil it comes from, so working *harder* on the same cells brought the fertility
         * collapse on sooner. Speed had no answer to the constraint the whole economy turns on, and
         * a solo-speed build died in year 0 while doing everything faster.
         *
         * Reach is the answer that belongs to the trait. AD-53 measured movement — not work rate —
         * as what decides whether a people is viable, because work is spatial (AD-21) and a worker
         * only counts as working beside their cell (AD-22). A people who cross ground quickly can
         * spread the same farming over more land, so each cell is worked less often and the drain
         * per cell falls without any change to the drain per worked day. It is the one route to
         * sustainability that is about *where* people work rather than how well.
         */
        const val WORK_SEARCH_RADIUS_PER_SPEED = 3.0

        /**
         * How much of a cell's fertility drain one point of Speed above base avoids.
         *
         * Speed was the last trait with no answer to the constraint the whole economy turns on.
         * It raised output and movement, and a solo-Speed people still died in year two, because
         * output comes out of soil that base Farming cannot restore: working the same fields harder
         * brings the collapse on sooner, and a wider search radius only finds *better* cells, not
         * fresher ones, since the worker picks the best it can see and then drains that.
         *
         * The drain is charged per worked day per cell, so the thing a quick people genuinely does
         * differently is not linger: the same work is spread over more ground, and each patch is
         * turned over less often. At 0.05 a Speed-8 people pays 0.0057 a day against their own
         * recovery of 0.0058 — sustainable by a hair, which is the right size for a trait that buys
         * sustainability sideways rather than head-on the way Farming and Elements do.
         */
        const val DRAIN_REDUCTION_PER_SPEED = 0.05

        /** Default job weights before a Premier sets them (M4). Normalised at use. */
        val DEFAULT_JOB_WEIGHTS: Map<Job, Double> = mapOf(
            Job.FARMER to 0.46,
            Job.HUNTER to 0.20,
            Job.GATHERER to 0.16,
            Job.BUILDER to 0.06,
            Job.SCHOLAR to 0.05,
            Job.HEALER to 0.03,
            Job.ARTISAN to 0.02,
            Job.SOLDIER to 0.02,
        )

        /**
         * Floor under the share of the workforce on food, whatever the Premier's agenda says.
         * Without it a Zealot with a Tech agenda simply starves the town in year one, which is a
         * failure of the model rather than an interesting political outcome.
         */
        const val MIN_FOOD_WORKER_SHARE = 0.45

                /**
         * Share of the workforce kept on materials and construction whatever the agenda says.
         * A town that never gathers wood can never build anything, and almost every building
         * costs wood — one run quarried 1,250 stone, gathered 18 wood, and never laid a single
         * foundation in fifty years.
         */
        const val INFRASTRUCTURE_WORKER_SHARE = 0.18
        const val BUILDER_SHARE_OF_INFRASTRUCTURE = 0.35

        /**
         * How much each completed Lifestyle building raises a town's birth rate, and the ceiling
         * on the sum.
         *
         * Housing already gates births through housing slack; this is the other half of the same
         * idea — a town with plazas, baths and somewhere to gather is a town people start families
         * in. Capped, because Lifestyle is the cheapest category and nine plazas must not double
         * the population curve.
         */
        const val FERTILITY_PER_LIFESTYLE_BUILDING = 0.06
        const val MAX_LIFESTYLE_FERTILITY_BONUS = 0.45

        /** In a food crisis, this share of the workforce is pushed onto food production. */
        const val CRISIS_FOOD_WORKER_SHARE = 0.85

        const val GATHERER_OUTPUT = 0.35

        /**
         * Food a gatherer forages per worked day, before their `gatherYield` scales it.
         *
         * Gathering was the one trait that touched no food at all: it drove timber, stone and build
         * rate, so a Gathering-8 people with base Farming starved before it could build anything
         * with the materials it was so good at collecting — 0.0 years in the sweep. Foraging is what
         * gathering *is*, so the trait earns its food rather than being handed a farm bonus.
         *
         * Scaled like [FARM_OUTPUT_SCALE] and for the same reason (AD-23): the natural scale of
         * `cellValue x yield` is about one person's ration, so a literal reading has a forager feed
         * a third of a person. The first attempt at this constant was 0.55 unscaled, which made
         * foraging a fifth as productive as farming — so staffing it, as the trait now does, cut
         * food production by 40% and killed the build faster than leaving it alone. The job counts
         * proved it: 28 gatherers producing less than the 6 they replaced.
         *
         * It is deliberately the *same* scale as [FARM_OUTPUT_SCALE] and [HUNT_OUTPUT_SCALE]: all
         * three food jobs read `cellValue x yield x scale`, so one number governs how much a day's
         * work feeds a town and the traits do the differentiating. At 2.4 a Gathering-8 forager
         * brought back 2.4 food a day against a Farming-8 farmer's 3.1, and measurement showed that
         * gap was the whole difference between a people that lived and one that starved at day 240
         * with 37 foragers in the field. Parity of scale, difference by trait.
         *
         * Note this makes gathering a *partial* food source for every people, not only a specialised
         * one — anyone in the woods picks something up. That is a real change from the five-trait
         * baseline the M3-M7 tables were measured against, which is among the reasons those tables
         * are marked provisional.
         */
        const val FORAGE_FOOD_PER_GATHER_DAY = 3.0

        /**
         * How fast foraging depletes a cell's wild game, as a share of [HUNT_DEPLETION_PER_DAY].
         *
         * Foraging draws on the same renewable pool hunting does, which is what stops it being free
         * food: an unlimited source with no drain would have made Gathering the answer to every
         * build rather than one answer among six. It also creates a real interaction — a hunting
         * people and a gathering people on the same range compete — where a separate invented
         * resource would have created none.
         */
        const val FORAGE_DEPLETION_SHARE = 0.45
        const val BUILDER_OUTPUT = 1.0
        const val SCHOLAR_OUTPUT = 0.20

        /**
         * Absolute scale for knowledge, the mirror of [FARM_OUTPUT_SCALE].
         *
         * The design fixes both scholar output (0.20/day) and the tier costs (120 x 2.6^tier),
         * and the two are inconsistent with its own stated intent that "tier 6 is a genuine grind
         * that most runs don't reach": at face value a town of 150 reached tier 6 in about twelve
         * years. The tier costs are kept exactly as specified and the output is scaled instead,
         * since the cost curve is the thing the whole incremental spine is shaped around.
         *
         * M7 lowered it from 0.10. Ascension ends a run the moment tier 6 meets 400 people, and at
         * 0.10 any run that got comfortable hit both — 31% of sweep runs ascended against a target
         * of "rare", and §13's "tier 6 and Ascension should take 20+ runs". Scaling knowledge is
         * the same lever AD-30 chose for the same reason: the cost curve is the design, so the
         * other side of the equation moves.
         */
        const val KNOWLEDGE_OUTPUT_SCALE = 0.06
        const val ARTISAN_OUTPUT = 0.30

        /** Healer care capacity, in citizens fully covered per healer. */
        const val HEALER_CARE_CAPACITY = 12.0
        const val HEALER_RANGE_CELLS = 20
    }

    // ---------------------------------------------------------------- buildings

    /**
     * The building catalogue: every cost and every effect, in one table.
     *
     * Tiers gate availability — tier 0 is buildable from the founding, tier 6 needs the full tech
     * tree. Costs rise steeply with tier so that late buildings are a genuine commitment of
     * builder-years, not a formality.
     */
    object Buildings {

        /** Wealth upkeep is charged daily; a civ that cannot pay it loses buildings to ruin. */
        const val UPKEEP_GRACE_DAYS = 30

        /** A building with no residents still shelters people within this radius, at this quality. */
        const val SHELTER_QUALITY_HOUSED = 1.0

        /**
         * How far a new building may be sited from the civ's home cell. The built area grows with
         * the town: at a fixed 18 cells a colony ran out of room at 63 buildings and then never
         * built again, while its population grew past 1,300 with nowhere to live.
         */
        /**
         * How far from its village a civ will build, as a floor, a per-citizen growth, and a cap.
         *
         * All three grew with the 400x400 map and the real footprints. A town whose granary is six
         * cells across and whose town hall is ten cannot be packed inside fourteen cells the way a
         * town of 2x2 sheds could: at the old distances a colony ran out of room after a dozen
         * buildings and then silently stopped building anything at all.
         */
        const val BASE_SITE_DISTANCE = 34
        const val SITE_DISTANCE_PER_CITIZEN = 0.09
        const val MAX_SITE_DISTANCE = 150

        /** Minimum gap between buildings, so towns do not become solid blocks. */
        const val SITE_SPACING = 1

        /**
         * A new building must stand within this many cells of one the civ already owns.
         *
         * The reach above says how far from the village a town may build; this says the town has to
         * be a *town*. Without it the ring search happily placed a granary thirty cells out on the
         * first patch of free ground it found, so a settlement became a scatter of unrelated
         * structures across half the island with nothing joining them up. Growth now has to
         * proceed from what is already built, which is what makes a town spread as a town and what
         * makes taking its centre mean something.
         *
         * The first building is exempt, because there is nothing yet to be near: it anchors on the
         * village itself.
         */
        /**
         * How far a new building may stand from the nearest one the civ already owns, measured
         * origin to origin — which is why it has to clear the footprints themselves. Two adjacent
         * six-cell buildings are already 7 apart before any gap, so the old 9 left almost no room
         * and a town could not grow outward without failing this test.
         */
        const val MAX_DISTANCE_FROM_OWN_BUILDING = 22

        val CATALOGUE: List<BuildingSpec> = listOf(
            // ---- Farms: storage, yield, and turning surplus into money ----
            BuildingSpec(
                BuildingType.FIELD, BuildingCategory.FARMS, tier = 0, footprint = 6,
                buildPointsRequired = 60.0, woodCost = 20.0, stoneCost = 0.0, upkeepWealth = 0.02,
                farmYieldBonus = 0.06, range = 10,
            ),
            BuildingSpec(
                BuildingType.GRANARY, BuildingCategory.FARMS, tier = 1, footprint = 5,
                buildPointsRequired = 140.0, woodCost = 60.0, stoneCost = 20.0, upkeepWealth = 0.05,
                foodStorageBonus = 600.0,
            ),
            BuildingSpec(
                BuildingType.IRRIGATION, BuildingCategory.FARMS, tier = 2, footprint = 5,
                buildPointsRequired = 220.0, woodCost = 70.0, stoneCost = 60.0, upkeepWealth = 0.08,
                farmYieldBonus = 0.10, seasonFloor = 0.55, range = 12,
            ),
            BuildingSpec(
                BuildingType.MILL, BuildingCategory.FARMS, tier = 3, footprint = 5,
                buildPointsRequired = 320.0, woodCost = 120.0, stoneCost = 80.0, upkeepWealth = 0.12,
                foodToWealth = 0.08, foodStorageBonus = 200.0,
            ),

            // ---- Health: care access, disease, and infant survival ----
            BuildingSpec(
                BuildingType.HUT, BuildingCategory.HEALTH, tier = 0, footprint = 3,
                buildPointsRequired = 70.0, woodCost = 25.0, stoneCost = 0.0, upkeepWealth = 0.02,
                careCapacity = 14.0, range = 14,
            ),
            BuildingSpec(
                BuildingType.CLINIC, BuildingCategory.HEALTH, tier = 1, footprint = 3,
                buildPointsRequired = 170.0, woodCost = 60.0, stoneCost = 30.0, upkeepWealth = 0.06,
                careCapacity = 45.0, diseaseResistBonus = 0.05, range = 18,
            ),
            BuildingSpec(
                BuildingType.AQUEDUCT, BuildingCategory.HEALTH, tier = 2, footprint = 3,
                buildPointsRequired = 260.0, woodCost = 40.0, stoneCost = 140.0, upkeepWealth = 0.09,
                careCapacity = 30.0, diseaseResistBonus = 0.12, range = 22,
            ),
            BuildingSpec(
                BuildingType.HOSPITAL, BuildingCategory.HEALTH, tier = 3, footprint = 6,
                buildPointsRequired = 420.0, woodCost = 150.0, stoneCost = 120.0, upkeepWealth = 0.16,
                careCapacity = 120.0, diseaseResistBonus = 0.10, range = 24,
            ),

            // ---- Military: strength, safety, and deterrence ----
            BuildingSpec(
                BuildingType.WATCHTOWER, BuildingCategory.MILITARY, tier = 0, footprint = 2,
                buildPointsRequired = 80.0, woodCost = 30.0, stoneCost = 10.0, upkeepWealth = 0.03,
                militaryStrength = 6.0, safetyBonus = 0.08, range = 20,
            ),
            BuildingSpec(
                BuildingType.BARRACKS, BuildingCategory.MILITARY, tier = 1, footprint = 6,
                buildPointsRequired = 190.0, woodCost = 70.0, stoneCost = 40.0, upkeepWealth = 0.08,
                militaryStrength = 28.0, safetyBonus = 0.10,
            ),
            BuildingSpec(
                BuildingType.WALL, BuildingCategory.MILITARY, tier = 2, footprint = 1,
                buildPointsRequired = 300.0, woodCost = 30.0, stoneCost = 190.0, upkeepWealth = 0.06,
                militaryStrength = 14.0, safetyBonus = 0.22,
            ),
            BuildingSpec(
                BuildingType.ARMOURY, BuildingCategory.MILITARY, tier = 3, footprint = 5,
                buildPointsRequired = 380.0, woodCost = 110.0, stoneCost = 130.0, upkeepWealth = 0.14,
                militaryStrength = 60.0, safetyBonus = 0.10,
            ),

            // ---- Tech: knowledge rate, build speed, and the tier ladder ----
            BuildingSpec(
                BuildingType.WORKSHOP, BuildingCategory.TECH, tier = 0, footprint = 5,
                buildPointsRequired = 90.0, woodCost = 35.0, stoneCost = 15.0, upkeepWealth = 0.04,
                buildSpeedBonus = 0.12, knowledgeMultiplier = 0.10,
            ),
            BuildingSpec(
                BuildingType.LIBRARY, BuildingCategory.TECH, tier = 1, footprint = 5,
                buildPointsRequired = 200.0, woodCost = 80.0, stoneCost = 40.0, upkeepWealth = 0.09,
                knowledgeMultiplier = 0.45,
            ),
            BuildingSpec(
                BuildingType.ACADEMY, BuildingCategory.TECH, tier = 2, footprint = 6,
                buildPointsRequired = 340.0, woodCost = 130.0, stoneCost = 90.0, upkeepWealth = 0.15,
                knowledgeMultiplier = 0.80, buildSpeedBonus = 0.10,
            ),
            BuildingSpec(
                BuildingType.OBSERVATORY, BuildingCategory.TECH, tier = 4, footprint = 6,
                buildPointsRequired = 520.0, woodCost = 160.0, stoneCost = 180.0, upkeepWealth = 0.22,
                knowledgeMultiplier = 1.40,
            ),

            // ---- Lifestyle: housing, morale, influence ----
            BuildingSpec(
                BuildingType.HOUSING, BuildingCategory.LIFESTYLE, tier = 0, footprint = 3,
                buildPointsRequired = 75.0, woodCost = 30.0, stoneCost = 5.0, upkeepWealth = 0.02,
                housingCapacity = 8, moraleBonus = 0.02, range = 12,
            ),
            BuildingSpec(
                BuildingType.PLAZA, BuildingCategory.LIFESTYLE, tier = 1, footprint = 6,
                buildPointsRequired = 160.0, woodCost = 40.0, stoneCost = 70.0, upkeepWealth = 0.05,
                moraleBonus = 0.10, influenceBonus = 0.04, range = 16,
            ),
            BuildingSpec(
                BuildingType.TEMPLE, BuildingCategory.LIFESTYLE, tier = 2, footprint = 5,
                buildPointsRequired = 280.0, woodCost = 90.0, stoneCost = 110.0, upkeepWealth = 0.10,
                moraleBonus = 0.14, influenceBonus = 0.05, range = 18,
            ),
            BuildingSpec(
                BuildingType.THEATRE, BuildingCategory.LIFESTYLE, tier = 3, footprint = 6,
                buildPointsRequired = 400.0, woodCost = 150.0, stoneCost = 90.0, upkeepWealth = 0.18,
                moraleBonus = 0.20, influenceBonus = 0.06, range = 20,
            ),
        )

        private val BY_TYPE: Map<BuildingType, BuildingSpec> = CATALOGUE.associateBy { it.type }

        fun spec(type: BuildingType): BuildingSpec = BY_TYPE.getValue(type)

        fun inCategory(category: BuildingCategory): List<BuildingSpec> =
            CATALOGUE.filter { it.category == category }

        /** Everything a civ at [techTier] is allowed to build, best tier first. */
        fun available(category: BuildingCategory, techTier: Int): List<BuildingSpec> =
            inCategory(category).filter { it.tier <= techTier }.sortedByDescending { it.tier }
    }

    // ---------------------------------------------------------------- tech

    object Tech {
        const val MAX_TIER = 6
        // tierCost = COST_BASE * COST_GROWTH^tier
        const val COST_BASE = 120.0
        const val COST_GROWTH = 2.6
        /** Global output multiplier granted per unlocked tier. */
        const val MULTIPLIER_PER_TIER = 0.12
    }

    // ---------------------------------------------------------------- politics

    object Politics {
        const val TERM_LENGTH_DAYS = Time.DAYS_PER_YEAR
        const val CANDIDATE_COUNT = 3

        /** Candidates are announced this many days before the vote, so the player can campaign. */
        const val CAMPAIGN_DAYS = 30

        /**
         * Years a Premier holds office before the town votes again.
         *
         * Annual was too often once the election stopped the clock (AD-65): a pause every single
         * year is an interruption rather than an event, and it made the office itself cheap — a
         * Premier barely outlived their own building order. Four years gives a term long enough to
         * have a record worth judging, and turns the vote into something a player looks forward to.
         */
        const val TERM_YEARS = 4

        /**
         * When the *first* election is held, regardless of the term length.
         *
         * A town cannot build without a Premier — `premierDecisions` returns early when the office is
         * empty — so lengthening the term to four years silently gave every colony four years with no
         * granary, no housing and no healer's hut, however much timber it had gathered. The balance
         * sweep showed it plainly: collapsing runs finished with hundreds of units of wood and zero
         * spent, because nothing had ever been ordered.
         *
         * The founding year is therefore special-cased. A people elect their first government
         * promptly and then keep it for a proper term, which is both the sensible mechanic and what
         * the four-year term was actually for.
         */
        const val FIRST_ELECTION_YEAR = 1
        const val VOTING_AGE_YEARS = 16
        /** Days between the Premier's decision points — once a season. */
        const val DAYS_PER_DECISION = Time.DAYS_PER_SEASON

                /** The Premier acts once per season, not every tick. */
        const val DECISIONS_PER_YEAR = Time.SEASONS_PER_YEAR

        /** How far each temperament deviates from need-based building, 0..1. */
        val TEMPERAMENT_DEVIATION = mapOf(
            Temperament.PASSIVE to 0.10,
            Temperament.PRAGMATIC to 0.25,
            Temperament.AMBITIOUS to 0.55,
            Temperament.ZEALOT to 0.85,
        )

        // Agenda generation: a primary cause, a tolerated second, and a floor under the rest so
        // no candidate ignores a category entirely.
        const val AGENDA_FLOOR = 0.06
        const val AGENDA_NOISE = 0.10
        const val AGENDA_PRIMARY_WEIGHT = 0.55
        const val AGENDA_SECONDARY_WEIGHT = 0.25

        /** How often a rival civ's candidate runs on the platform its people's character favours. */
        const val PERSONALITY_AGENDA_CHANCE = 0.55

        /**
         * How strongly each felt need pushes a voter toward the matching category. The electorate
         * is a feedback loop on the state of the town: hungry citizens vote farms, sick citizens
         * vote health, frightened citizens vote military.
         */
        const val VOTE_HUNGER_WEIGHT = 1.5
        const val VOTE_HEALTH_WEIGHT = 1.2
        const val VOTE_FEAR_WEIGHT = 1.0
        const val VOTE_HOMELESS_WEIGHT = 0.9
        const val VOTE_CURIOSITY_WEIGHT = 0.35

        /** Baseline pull toward every category, so a contented town still has opinions. */
        const val VOTE_BASELINE = 0.30

        /** Range of a citizen's personal political bias, drawn once at birth. */
        const val VOTE_BIAS_MAX = 0.9

        /** A Premier builds at most this many things per decision point. */
        const val BUILD_ORDERS_PER_DECISION = 2

        /** Share of the workforce a Premier puts on construction while anything is unfinished. */
        const val BUILDER_SHARE_WHILE_BUILDING = 0.14

                // Influence economy (the player's lever).
        const val INFLUENCE_POINTS_PER_DAY_BASE = 0.08
        const val INFLUENCE_POINTS_PER_PLAZA = 0.04
        const val INFLUENCE_POINTS_PER_TEMPLE = 0.05
        const val INFLUENCE_POINTS_MORALE_SCALE = 0.6

        const val COST_ENDORSE = 20
        /**
         * A standing charter: the one lasting instruction the player can leave their town.
         *
         * A petition moves the sitting Premier and reverts within the year; an election replaces
         * them entirely. Neither lets a player pursue anything across a century, which made the
         * whole building layer something you watched. A charter names a category every future
         * Premier weights more heavily, and it holds until the player changes it.
         *
         * Deliberately a nudge and not a command — the design's point is that the town governs
         * itself and the player has influence over it. At this weight a charter reliably shows up
         * in what gets built without turning the Premier into a puppet.
         */
        const val CHARTER_WEIGHT = 0.30
        const val COST_CHARTER = 60

        const val COST_PETITION = 35
        const val COST_VETO = 45
        const val COST_REFERENDUM = 120

        /**
         * The two things a player may do to a neighbour directly, priced in influence.
         *
         * Everything else the player does is a nudge to their own town; these reach across the map,
         * so they are the most expensive levers on the board. Offering a trade is cheap because the
         * neighbour still has to want it — it opens a negotiation, it does not command one. Forcing
         * a war is the single most consequential button in the game and is priced to match: a player
         * should have to save for it, and should not be able to do it twice in a season.
         */
        /**
         * How much of a build decision a people's own traits account for, when the town is
         * comfortable. Scaled to zero as distress rises, so survival always wins in the end.
         *
         * At 0.35 a committed people's character is plainly visible in what they build over a
         * century without the Premier's platform or the town's needs becoming decoration.
         */
        const val TRAIT_LEAN_WEIGHT = 0.35

        const val COST_OFFER_TRADE = 25
        const val COST_FORCE_WAR = 180
        const val ENDORSE_VOTE_WEIGHT_BONUS = 0.25
        const val VETOES_PER_YEAR = 1

        // Unrest
        /**
         * Unrest only builds once the town is both suffering and badly governed. The first
         * version raised it whenever pressure cleared 0.02, which almost any Premier did: unrest
         * saturated within a year, every run, and the resulting emigration and coups killed
         * colonies that M3 had kept alive for centuries. It is now a threshold, not a ratchet.
         */
        const val UNREST_PRESSURE_THRESHOLD = 0.15
        const val UNREST_PER_DAY_PER_UNMET_NEED = 0.002
        const val UNREST_DECAY_PER_DAY = 0.0015
        const val UNREST_WORK_PENALTY_AT_MAX = 0.45
        const val UNREST_EMIGRATION_THRESHOLD = 0.55
        const val UNREST_EMIGRATION_DAILY_CHANCE = 0.004
        const val UNREST_COUP_THRESHOLD = 0.80
        const val UNREST_COUP_DAILY_CHANCE = 0.01
    }

    // ---------------------------------------------------------------- rivals

    object Rivals {
        /**
         * The lowest Farming a rival civilisation will found itself with.
         *
         * Not a difficulty knob — a viability floor. The balance runs put the line plainly:
         * Farming 3 dies inside two years whatever else the allocation holds, and Farming 1 never
         * reaches year one. A rival that starves immediately is an opponent deleted from the game
         * before the player ever meets it.
         */
        const val MIN_VIABLE_FARMING = 4

        /** Full per-citizen detail within this radius of player-visible area; aggregated beyond. */
        const val DETAIL_RADIUS_CELLS = 75

        // aggression = 0.5*militaryShare + 0.3*(1 - foodSecurity) + 0.2*personalityBias
        /**
         * A civ with this share of its people under arms scores full marks on the military term.
         *
         * The design's formula uses the raw share, but a realistic army is a tenth of a town, so
         * the term never exceeded 0.05 and aggression never came near the raid and war thresholds:
         * 150 years produced not one war. Normalising against a reference share keeps the formula's
         * shape and gives it a usable range.
         */
        const val MILITARY_SHARE_REFERENCE = 0.20

        const val AGGRESSION_W_MILITARY = 0.5
        const val AGGRESSION_W_HUNGER = 0.3
        const val AGGRESSION_W_PERSONALITY = 0.2

        val PERSONALITY_BIAS = mapOf(
            Personality.ISOLATIONIST to 0.10,
            Personality.MERCANTILE to 0.25,
            Personality.EXPANSIONIST to 0.60,
            Personality.MILITANT to 0.90,
        )

        /** Interactions are resolved at season boundaries. */
        const val TENSION_MAX = 1.0
        const val TENSION_DECAY_PER_SEASON = 0.05
        /**
         * Trade cools a relationship, but it cannot buy permanent peace: at 0.12 a season, with
         * every pair of civs trading every season, tension sat at exactly zero for 150 years while
         * borders were being contested the whole time.
         */
        const val TENSION_TRADE_RELIEF = 0.03
        const val TENSION_TRIBUTE_REFUSED = 0.25
        const val TENSION_BORDER_FRICTION = 0.07
        /**
         * Being raided is remembered. Without this the escalation ladder had a rung missing:
         * friction and refused tribute alone topped out around 0.54, so raiding went on
         * indefinitely and war — the thing raids are supposed to lead to — never arrived.
         */
        const val TENSION_RAID_LAUNCHED = 0.10
        const val TENSION_RAID_SUCCEEDED = 0.14

        const val TENSION_RAID_THRESHOLD = 0.45
        const val TENSION_WAR_THRESHOLD = 0.75

        /**
         * Aggression a civ needs before it will act on maximum tension and actually invade.
         *
         * Gating war on both high tension and aggression above 0.5 was over-constrained: with a
         * realistic army and full granaries, aggression tops out near 0.4, so tension pinned at
         * 1.00, trade froze, raids ran forever and no war was ever declared. A lower bar restores
         * the cycle the design describes — trade, friction, raids, war, exhaustion, peace, trade.
         */
        const val WAR_AGGRESSION_MIN = 0.30

        /** Aggression needed to send a raiding party. */
        const val RAID_AGGRESSION_MIN = 0.25

        const val TRIBUTE_FOOD_FRACTION = 0.10
        const val TRIBUTE_WEALTH_FRACTION = 0.15
        const val RAID_FOOD_STOLEN_FRACTION = 0.18
        const val RAID_CASUALTY_FRACTION = 0.04

        /** Seasons between diplomatic contacts with the same civ. */
        const val CONTACT_INTERVAL_SEASONS = 1

        /** A civ must hold this many days of food before it will trade any away. */
        const val TRADE_SURPLUS_DAYS = 45

        /** Fraction of a surplus a civ is willing to trade in one exchange. */
        const val TRADE_FRACTION = 0.25

        /** Wealth paid per unit of food or material received in trade. */
        const val TRADE_PRICE = 0.8

        /** A tribute demand is made when aggression clears this and tension is not yet war. */
        const val TRIBUTE_AGGRESSION_THRESHOLD = 0.45

        /** A civ pays tribute rather than fight when it is this much weaker. */
        const val TRIBUTE_SUBMIT_STRENGTH_RATIO = 0.7

        /** Cells within which two civs' claims count as competing. */
        const val BORDER_FRICTION_RADIUS = 6

        /** Soldiers sent on a raid, as a share of the civ's standing army. */
        const val RAID_PARTY_SHARE = 0.5

        /** Soldiers committed to a war, as a share of the civ's standing army. */
        const val WAR_PARTY_SHARE = 0.8

        /** How close armies must be before they fight. */
        const val ENGAGEMENT_RANGE = 3

        /** A raiding party gives up and goes home after this long. */
        /**
         * How long a raiding party stays out before it turns for home.
         *
         * A march is three times longer on the 400x400 map, and at 1.5-2 cells a day crossing 110
         * cells to a neighbour is most of a season each way. At the old 120 days a raid expired
         * before it arrived, so raids simply stopped happening — the escalation ladder (AD-34) lost
         * its first rung silently.
         */
        const val RAID_MAX_DAYS = 300

        /**
         * War weariness: tension falls by this each day a war runs, so wars end. Slow enough that
         * a war is a campaign rather than a season's mood — at 0.004 wars burned out in weeks and
         * were immediately re-declared, 637 times in 135 years.
         */
        const val WAR_WEARINESS_PER_DAY = 0.0015

        /** After a peace, neither side may declare war again for this long. */
        const val PEACE_COOLDOWN_DAYS = 1_080

        /** A war ends when tension falls below this. */
        const val PEACE_TENSION = 0.35

        /** Below this share of its starting strength, an army breaks off and goes home. */
        const val ARMY_BROKEN_AT = 0.35

        /**
         * What share of a civ's soldiers its buildings can equip as the better unit kinds.
         *
         * Derived from buildings rather than chosen per soldier (see [UnitKind]), and fixed rather
         * than rolled, so the same town always fields the same army and none of this touches the RNG
         * stream. The remainder are infantry where there is a barracks and militia where there is
         * not, which is what makes the first barracks visible in the army as well as on the map.
         */
        const val SHARE_MEN_AT_ARMS = 0.30
        const val SHARE_ARCHERS = 0.25

        /** Minimum soldiers before a civ will start anything. */
        const val MIN_PARTY_SIZE = 3

        /** How strongly a rival's strength advantage reduces the safety a citizen feels. */
        const val THREAT_SAFETY_PENALTY = 0.45

                // strength = sum(soldiers * (0.4 + 0.18*Hunting) * skill) * techMult * wallBonus
        const val COMBAT_DAYS = 9
        const val COMBAT_DAILY_ATTRITION = 0.12
        const val COMBAT_RANDOM_SPREAD = 0.20
        const val WALL_DEFENCE_BONUS = 1.45

        /**
         * The siege. A standing wall is not a multiplier any more — it is an obstacle that has to
         * be brought down before an army reaches the town behind it.
         *
         * Three numbers. [SIEGE_DAMAGE_PER_STRENGTH] is how much integrity a point of attacking
         * strength removes per day, so a wall's life is measured in army-days rather than in a dice
         * roll. [SIEGE_ATTACKER_ATTRITION_SCALE] is the share of a normal battle's losses the
         * besiegers still take while they work — the defenders are shooting down at them, so it is
         * not free, but they are not storming the place either. [SIEGE_MAX_DAYS] is the patience of
         * a raiding party: a raid that cannot get through a wall in a fortnight goes home, which is
         * exactly what walls are for.
         *
         * A WALL's `buildPointsRequired` is 300, so one integrity point is one build point: it
         * costs an army roughly what it cost the defenders, which is the trade the player is
         * choosing when they charter Military.
         */
        const val SIEGE_DAMAGE_PER_STRENGTH = 0.9
        const val SIEGE_ATTACKER_ATTRITION_SCALE = 0.35
        const val SIEGE_MAX_DAYS = 14
    }

    // ---------------------------------------------------------------- meta

    object Meta {
        const val ENDURANCE_YEARS = 300
        const val ASCENSION_TECH_TIER = Tech.MAX_TIER
        const val ASCENSION_POPULATION = 400

        // chronicle = floor(peakPop/10 + years/4 + techTier^2*6 + endStateBonus)
        const val CHRONICLE_PER_PEAK_POP = 0.10
        const val CHRONICLE_PER_YEAR = 0.25
        const val CHRONICLE_TECH_TIER_FACTOR = 6.0

        val END_STATE_BONUS = mapOf(
            EndState.COLLAPSE to 0,
            EndState.CONQUEST to 25,
            EndState.ENDURANCE to 200,
            EndState.ASCENSION to 500,
        )

        /** Cost of the 1st..4th `+1 starting allocation point` upgrade. */
        val ALLOCATION_POINT_UPGRADE_COSTS = intArrayOf(400, 900, 2_000, 4_500)

        /** Every other permanent upgrade costs this, growing per level already owned. */
        const val UPGRADE_BASE_COST = 150.0
        const val UPGRADE_COST_GROWTH = 2.2

        // What each permanent upgrade is worth per level.
        const val SETTLERS_PER_UPGRADE = 5
        const val SKILL_GROWTH_PER_UPGRADE = 0.25
        const val INFLUENCE_PER_UPGRADE = 0.06
        const val FERTILITY_FLOOR_PER_UPGRADE = 0.08
        const val TENSION_RELIEF_PER_UPGRADE = 0.10

        // Offline catch-up
        /**
         * Game ticks per real second spent away, which is **not** the live 1x rate.
         *
         * Taken literally, "convert elapsed real seconds to ticks" at 1x (10 ticks/sec) means one
         * hour away is 100 game years and the free eight-hour cap is 800 — more than twice the
         * longest possible run. Measured: a three-hour absence ran 240 years, ended the run in
         * Endurance, and took 48 seconds of computation. Every check-in would finish the player's
         * civilisation, and the Founders Pass 48-hour cap would sell nothing at all, since 8 hours
         * already exceeds any run.
         *
         * At 0.5 ticks/sec an absence is a chapter rather than the whole book: 8 hours is 40
         * years, so a 300-year run spans seven or eight visits, and the Pass's 48 hours (240
         * years) is a real upgrade. This is the single number that sets the game's return cadence.
         */
        const val OFFLINE_TICKS_PER_REAL_SECOND = 0.5

        const val OFFLINE_CAP_HOURS_FREE = 8
        const val OFFLINE_CAP_HOURS_FOUNDERS_PASS = 48

        const val CHRONICLE_BUFFER_SIZE = 4_000

        /** How many notable moments the "while you were away" report carries. */
        const val RETURN_REPORT_HIGHLIGHTS = 12
    }

    // ---------------------------------------------------------------- render

    object Render {
        /** Zoom range for the pixel camera. */
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 8.0f

        /** Citizen brightness is scaled between these by survival score. */
        const val CITIZEN_MIN_BRIGHTNESS = 0.35f
        const val CITIZEN_MAX_BRIGHTNESS = 1.0f

        /**
         * The player's own people are held to a higher brightness floor than anyone else.
         *
         * At one pixel per person, a struggling colony dimmed by its survival score sank into the
         * terrain and became genuinely hard to find on a phone. A starving town should still read
         * as starving — so the floor is raised, not removed, and the range above it is narrower:
         * the player's pixels stay locatable while still visibly dimming under hardship.
         */
        const val PLAYER_MIN_BRIGHTNESS = 0.70f

        /**
         * How far each citizen's hue may drift from their civ's colour, in degrees either way.
         *
         * One flat colour for thousands of people made a town look like spilled paint. A small,
         * stable per-citizen offset makes the same town read as a crowd — and it stays clearly
         * *one* people, which is why this is a few degrees and not a rainbow. The offset is derived
         * from the citizen's id, so it never shimmers between frames and costs no state.
         *
         * Kept small now that [JOB_HUE_DEGREES] carries the bulk of the variation: this is the
         * jitter that stops two farmers being the same pixel, not the signal.
         */
        const val CITIZEN_HUE_SPREAD_DEGREES = 3f

        /**
         * Hue offset per [Job], in degrees from the civ's own colour, indexed by `Job.ordinal`.
         *
         * A citizen is one pixel, so what they are doing has nowhere to go but their colour. These
         * are deliberately a spread around the civ's hue rather than arbitrary colours: a town has
         * to stay recognisably one people, and a player has to be able to see at a glance that half
         * of it is in the fields. Food jobs sit below the base hue, craft and knowledge above it,
         * soldiers furthest out.
         */
        val JOB_HUE_DEGREES = floatArrayOf(
            0f, // CHILD — the civ's own colour, dimmed below rather than shifted
            -16f, // FARMER
            -30f, // HUNTER
            -23f, // GATHERER
            14f, // BUILDER
            34f, // SOLDIER
            26f, // SCHOLAR
            20f, // HEALER
            8f, // ARTISAN
            0f, // IDLE
        )

        /** Children and the idle are drawn dimmer, which is the other half of reading a crowd. */
        const val CHILD_BRIGHTNESS_SCALE = 0.78f
        const val IDLE_BRIGHTNESS_SCALE = 0.86f

        /** Rival citizens are drawn slightly back, so gold reads first in a crowded frame. */
        const val RIVAL_BRIGHTNESS_SCALE = 0.86f

        /** With focus on, rivals fall this far back — for picking your own people out at a glance. */
        const val FOCUS_RIVAL_BRIGHTNESS_SCALE = 0.45f

        /** Radius, in cells, of the ring drawn around the player's founding site. */
        const val HOME_MARKER_RADIUS = 4

        /** The player's territory is tinted this much harder than a rival's. */
        const val PLAYER_TERRITORY_TINT_SCALE = 2.0f
    }
}
