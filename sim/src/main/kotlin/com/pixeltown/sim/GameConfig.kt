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
        const val COUNT = 5
        const val BASE_VALUE = 3
        const val ALLOCATION_POINTS = 10
        const val MAX_PER_TRAIT = 8
        const val MIN_PER_TRAIT = 1

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
         * How long a player's earned point waits for them before the town spends it itself.
         *
         * This is an idle game with an offline catch-up path: a player who is away for a day must
         * not come back behind the rivals, who spend theirs the moment they earn them. The
         * automatic choice is deliberately conservative (see `Simulation.needBasedGrowth`), so
         * attention is rewarded with *direction*, never with raw power.
         */
        const val GENERATION_AUTOSPEND_GRACE_DAYS = Time.DAYS_PER_YEAR

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
        const val WORK_MULT_PER_SPEED = 0.08

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

        // lifespan = 48 + 3.0 * Health   (years)
        const val LIFESPAN_YEARS_BASE = 48.0
        const val LIFESPAN_YEARS_PER_HEALTH = 3.0

        // diseaseResist = 0.06 * Health
        const val DISEASE_RESIST_PER_HEALTH = 0.06

        const val MAX_HP_BASE = 60.0
        const val MAX_HP_PER_HEALTH = 8.0

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
         */
        const val FERTILITY_RECOVERY_BASE = 0.0030
        const val FERTILITY_RECOVERY_PER_FARMING = 0.0006
    }

    // ---------------------------------------------------------------- world

    object World {
        const val WIDTH = 128
        const val HEIGHT = 128

        const val STARTING_SETTLERS = 50

        /** Settlers are founded as adults of working age, spread across this range. */
        const val SETTLER_MIN_AGE_YEARS = 17
        const val SETTLER_MAX_AGE_YEARS = 38

        /** Share of founding settlers who arrive already partnered. */
        const val SETTLER_PARTNERED_SHARE = 0.55

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
        const val MIN_CIV_START_SEPARATION = 34

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
         */
        const val FERTILITY_DRAIN_PER_FARM_DAY = 0.0060

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
        const val CONCEIVE_BASE = 0.0012

        /**
         * Housing slack for a town with no housing at all. Not zero: a colony has to be able to
         * grow enough to build its first house.
         */
        const val HOUSING_SLACK_WITHOUT_HOUSING = 0.5
        const val GESTATION_DAYS = 270
        const val CHILD_UNTIL_YEARS = 14

        // Pairing
        const val PAIR_SEARCH_RADIUS_CELLS = 12
        const val PAIR_MIN_AGE_YEARS = 16
        const val PAIR_MAX_AGE_GAP_YEARS = 14
        const val PAIR_DAILY_PROBABILITY = 0.006
        const val WIDOW_REPAIR_DELAY_DAYS = 180

        // Disease
        const val DISEASE_EVENT_BASE_CHANCE = 0.0004
        const val DISEASE_HP_DAMAGE = 18.0

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
        const val BASE_FOOD_STORAGE_CAPACITY = 400.0

        /** Below this many days of food stock, job assignment is forced toward food. */
        const val FOOD_CRISIS_DAYS_OF_STOCK = 10

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

        /** In a food crisis, this share of the workforce is pushed onto food production. */
        const val CRISIS_FOOD_WORKER_SHARE = 0.85

        const val GATHERER_OUTPUT = 0.35
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
        const val BASE_SITE_DISTANCE = 14
        const val SITE_DISTANCE_PER_CITIZEN = 0.035
        const val MAX_SITE_DISTANCE = 46

        /** Minimum gap between buildings, so towns do not become solid blocks. */
        const val SITE_SPACING = 1

        val CATALOGUE: List<BuildingSpec> = listOf(
            // ---- Farms: storage, yield, and turning surplus into money ----
            BuildingSpec(
                BuildingType.FIELD, BuildingCategory.FARMS, tier = 0, footprint = 2,
                buildPointsRequired = 60.0, woodCost = 20.0, stoneCost = 0.0, upkeepWealth = 0.02,
                farmYieldBonus = 0.06, range = 10,
            ),
            BuildingSpec(
                BuildingType.GRANARY, BuildingCategory.FARMS, tier = 1, footprint = 2,
                buildPointsRequired = 140.0, woodCost = 60.0, stoneCost = 20.0, upkeepWealth = 0.05,
                foodStorageBonus = 600.0,
            ),
            BuildingSpec(
                BuildingType.IRRIGATION, BuildingCategory.FARMS, tier = 2, footprint = 2,
                buildPointsRequired = 220.0, woodCost = 70.0, stoneCost = 60.0, upkeepWealth = 0.08,
                farmYieldBonus = 0.10, seasonFloor = 0.55, range = 12,
            ),
            BuildingSpec(
                BuildingType.MILL, BuildingCategory.FARMS, tier = 3, footprint = 2,
                buildPointsRequired = 320.0, woodCost = 120.0, stoneCost = 80.0, upkeepWealth = 0.12,
                foodToWealth = 0.08, foodStorageBonus = 200.0,
            ),

            // ---- Health: care access, disease, and infant survival ----
            BuildingSpec(
                BuildingType.HUT, BuildingCategory.HEALTH, tier = 0, footprint = 2,
                buildPointsRequired = 70.0, woodCost = 25.0, stoneCost = 0.0, upkeepWealth = 0.02,
                careCapacity = 14.0, range = 14,
            ),
            BuildingSpec(
                BuildingType.CLINIC, BuildingCategory.HEALTH, tier = 1, footprint = 2,
                buildPointsRequired = 170.0, woodCost = 60.0, stoneCost = 30.0, upkeepWealth = 0.06,
                careCapacity = 45.0, diseaseResistBonus = 0.05, range = 18,
            ),
            BuildingSpec(
                BuildingType.AQUEDUCT, BuildingCategory.HEALTH, tier = 2, footprint = 2,
                buildPointsRequired = 260.0, woodCost = 40.0, stoneCost = 140.0, upkeepWealth = 0.09,
                careCapacity = 30.0, diseaseResistBonus = 0.12, range = 22,
            ),
            BuildingSpec(
                BuildingType.HOSPITAL, BuildingCategory.HEALTH, tier = 3, footprint = 3,
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
                BuildingType.BARRACKS, BuildingCategory.MILITARY, tier = 1, footprint = 2,
                buildPointsRequired = 190.0, woodCost = 70.0, stoneCost = 40.0, upkeepWealth = 0.08,
                militaryStrength = 28.0, safetyBonus = 0.10,
            ),
            BuildingSpec(
                BuildingType.WALL, BuildingCategory.MILITARY, tier = 2, footprint = 2,
                buildPointsRequired = 300.0, woodCost = 30.0, stoneCost = 190.0, upkeepWealth = 0.06,
                militaryStrength = 14.0, safetyBonus = 0.22,
            ),
            BuildingSpec(
                BuildingType.ARMOURY, BuildingCategory.MILITARY, tier = 3, footprint = 2,
                buildPointsRequired = 380.0, woodCost = 110.0, stoneCost = 130.0, upkeepWealth = 0.14,
                militaryStrength = 60.0, safetyBonus = 0.10,
            ),

            // ---- Tech: knowledge rate, build speed, and the tier ladder ----
            BuildingSpec(
                BuildingType.WORKSHOP, BuildingCategory.TECH, tier = 0, footprint = 2,
                buildPointsRequired = 90.0, woodCost = 35.0, stoneCost = 15.0, upkeepWealth = 0.04,
                buildSpeedBonus = 0.12, knowledgeMultiplier = 0.10,
            ),
            BuildingSpec(
                BuildingType.LIBRARY, BuildingCategory.TECH, tier = 1, footprint = 2,
                buildPointsRequired = 200.0, woodCost = 80.0, stoneCost = 40.0, upkeepWealth = 0.09,
                knowledgeMultiplier = 0.45,
            ),
            BuildingSpec(
                BuildingType.ACADEMY, BuildingCategory.TECH, tier = 2, footprint = 3,
                buildPointsRequired = 340.0, woodCost = 130.0, stoneCost = 90.0, upkeepWealth = 0.15,
                knowledgeMultiplier = 0.80, buildSpeedBonus = 0.10,
            ),
            BuildingSpec(
                BuildingType.OBSERVATORY, BuildingCategory.TECH, tier = 4, footprint = 3,
                buildPointsRequired = 520.0, woodCost = 160.0, stoneCost = 180.0, upkeepWealth = 0.22,
                knowledgeMultiplier = 1.40,
            ),

            // ---- Lifestyle: housing, morale, influence ----
            BuildingSpec(
                BuildingType.HOUSING, BuildingCategory.LIFESTYLE, tier = 0, footprint = 2,
                buildPointsRequired = 75.0, woodCost = 30.0, stoneCost = 5.0, upkeepWealth = 0.02,
                housingCapacity = 8, moraleBonus = 0.02, range = 12,
            ),
            BuildingSpec(
                BuildingType.PLAZA, BuildingCategory.LIFESTYLE, tier = 1, footprint = 3,
                buildPointsRequired = 160.0, woodCost = 40.0, stoneCost = 70.0, upkeepWealth = 0.05,
                moraleBonus = 0.10, influenceBonus = 0.04, range = 16,
            ),
            BuildingSpec(
                BuildingType.TEMPLE, BuildingCategory.LIFESTYLE, tier = 2, footprint = 2,
                buildPointsRequired = 280.0, woodCost = 90.0, stoneCost = 110.0, upkeepWealth = 0.10,
                moraleBonus = 0.14, influenceBonus = 0.05, range = 18,
            ),
            BuildingSpec(
                BuildingType.THEATRE, BuildingCategory.LIFESTYLE, tier = 3, footprint = 3,
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
        const val COST_PETITION = 35
        const val COST_VETO = 45
        const val COST_REFERENDUM = 120
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
        const val DETAIL_RADIUS_CELLS = 24

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
        const val RAID_MAX_DAYS = 120

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

        /** Minimum soldiers before a civ will start anything. */
        const val MIN_PARTY_SIZE = 3

        /** How strongly a rival's strength advantage reduces the safety a citizen feels. */
        const val THREAT_SAFETY_PENALTY = 0.45

                // strength = sum(soldiers * (0.4 + 0.18*Hunting) * skill) * techMult * wallBonus
        const val COMBAT_DAYS = 9
        const val COMBAT_DAILY_ATTRITION = 0.12
        const val COMBAT_RANDOM_SPREAD = 0.20
        const val WALL_DEFENCE_BONUS = 1.45
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
