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

        // workMultiplier = 0.55 + 0.15 * Speed
        const val WORK_MULT_BASE = 0.55
        const val WORK_MULT_PER_SPEED = 0.15

        /** Cells per day a citizen can move, scaled by Speed. */
        const val MOVE_SPEED_BASE = 0.6
        const val MOVE_SPEED_PER_SPEED = 0.18

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

        // farmYield = 0.5 + 0.16 * Farming
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

        /** Soil recovery per day scales with the owning civ's Farming trait. */
        const val FERTILITY_RECOVERY_BASE = 0.0012
        const val FERTILITY_RECOVERY_PER_FARMING = 0.0004
    }

    // ---------------------------------------------------------------- world

    object World {
        const val WIDTH = 128
        const val HEIGHT = 128

        const val STARTING_SETTLERS = 50
        const val RIVAL_CIV_COUNT = 4
        const val TOTAL_CIV_COUNT = RIVAL_CIV_COUNT + 1
        const val PLAYER_CIV_ID = 0

        // Value-noise elevation / moisture generation.
        const val NOISE_OCTAVES = 5
        const val NOISE_BASE_FREQUENCY = 0.045
        const val NOISE_LACUNARITY = 2.0
        const val NOISE_PERSISTENCE = 0.5

        /** Elevation thresholds, low to high, after normalisation to 0..1. */
        const val SEA_LEVEL = 0.42
        const val BEACH_LEVEL = 0.46
        const val HILL_LEVEL = 0.70
        const val MOUNTAIN_LEVEL = 0.84

        /** Moisture thresholds for classifying land below [HILL_LEVEL]. */
        const val MARSH_MOISTURE = 0.72
        const val FOREST_MOISTURE = 0.52

        /** Rivers are carved from the N wettest peaks by steepest descent to ocean. */
        const val RIVER_SOURCE_COUNT = 6
        const val RIVER_MIN_SOURCE_ELEVATION = 0.72
        const val RIVER_MAX_LENGTH = 400

        /** Radial island falloff: elevation is multiplied down toward the map edge. */
        const val ISLAND_FALLOFF_START = 0.55
        const val ISLAND_FALLOFF_POWER = 2.2

        /** Minimum distance in cells between civ starting sites. */
        const val MIN_CIV_START_SEPARATION = 34
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

        /** Wild game regrows toward the terrain base at this fraction per day. */
        const val WILD_GAME_REGEN_PER_DAY = 0.004

        /** Fertility drained per farmer-day worked on a cell. */
        const val FERTILITY_DRAIN_PER_FARM_DAY = 0.0035

        /** Fertility below this makes a farm cell not worth working. */
        const val FERTILITY_ABANDON_THRESHOLD = 0.12
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

        /** Consecutive days at zero nutrition before starvation kills. */
        const val STARVATION_DAYS = 12
        const val HP_LOSS_PER_STARVING_DAY = 4.0
        const val HP_REGEN_PER_FED_DAY = 0.8

        // Births
        const val FERTILE_AGE_MIN_YEARS = 16
        const val FERTILE_AGE_MAX_YEARS = 42
        const val BIRTH_MIN_SURVIVAL = 58.0
        const val CONCEIVE_BASE = 0.0028
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
        const val FOOD_PER_ADULT_PER_DAY = 1.0
        const val FOOD_PER_CHILD_PER_DAY = 0.45

        /** Food above storage capacity spoils at this fraction per day. */
        const val SPOILAGE_PER_DAY_OVER_CAPACITY = 0.02
        const val BASE_FOOD_STORAGE_CAPACITY = 400.0

        /** Below this many days of food stock, job assignment is forced toward food. */
        const val FOOD_CRISIS_DAYS_OF_STOCK = 10

        /** Jobs are reassigned once per week. */
        const val JOB_REASSIGN_INTERVAL_DAYS = 7

        const val GATHERER_OUTPUT = 0.35
        const val BUILDER_OUTPUT = 1.0
        const val SCHOLAR_OUTPUT = 0.20
        const val ARTISAN_OUTPUT = 0.30

        /** Healer care capacity, in citizens fully covered per healer. */
        const val HEALER_CARE_CAPACITY = 12.0
        const val HEALER_RANGE_CELLS = 20
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
        const val VOTING_AGE_YEARS = 16
        /** The Premier acts once per season, not every tick. */
        const val DECISIONS_PER_YEAR = Time.SEASONS_PER_YEAR

        /** How far each temperament deviates from need-based building, 0..1. */
        val TEMPERAMENT_DEVIATION = mapOf(
            Temperament.PASSIVE to 0.10,
            Temperament.PRAGMATIC to 0.25,
            Temperament.AMBITIOUS to 0.55,
            Temperament.ZEALOT to 0.85,
        )

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
        /** Full per-citizen detail within this radius of player-visible area; aggregated beyond. */
        const val DETAIL_RADIUS_CELLS = 24

        // aggression = 0.5*militaryShare + 0.3*(1 - foodSecurity) + 0.2*personalityBias
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
        const val TENSION_TRADE_RELIEF = 0.12
        const val TENSION_TRIBUTE_REFUSED = 0.25
        const val TENSION_BORDER_FRICTION = 0.06
        const val TENSION_RAID_THRESHOLD = 0.45
        const val TENSION_WAR_THRESHOLD = 0.75

        const val TRIBUTE_FOOD_FRACTION = 0.10
        const val TRIBUTE_WEALTH_FRACTION = 0.15
        const val RAID_FOOD_STOLEN_FRACTION = 0.18
        const val RAID_CASUALTY_FRACTION = 0.04

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

        // Offline catch-up
        const val OFFLINE_CAP_HOURS_FREE = 8
        const val OFFLINE_CAP_HOURS_FOUNDERS_PASS = 48

        const val CHRONICLE_BUFFER_SIZE = 4_000
    }

    // ---------------------------------------------------------------- render

    object Render {
        /** Zoom range for the pixel camera. */
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 8.0f

        /** Citizen brightness is scaled between these by survival score. */
        const val CITIZEN_MIN_BRIGHTNESS = 0.35f
        const val CITIZEN_MAX_BRIGHTNESS = 1.0f
    }
}
