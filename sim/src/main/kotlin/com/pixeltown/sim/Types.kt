package com.pixeltown.sim

/**
 * Core enumerations shared across the simulation. Kept in one file so that the shape of the
 * domain is readable at a glance; behaviour lives in the systems, numbers live in [GameConfig].
 */

enum class TerrainType { OCEAN, BEACH, PLAIN, FOREST, HILL, MOUNTAIN, RIVER, MARSH }

enum class Sex { FEMALE, MALE }

enum class Job { CHILD, FARMER, HUNTER, GATHERER, BUILDER, SOLDIER, SCHOLAR, HEALER, ARTISAN, IDLE }

/**
 * What a job *is*, for the HUD's population breakdown.
 *
 * "1,240 people" is a number a player can read and not one they can act on. Grouping the jobs says
 * where a town's effort is actually going: whether it is feeding itself, building itself, learning,
 * or under arms. The groups are deliberately few — five columns a player can take in at a glance,
 * rather than ten job names to read one at a time.
 */
enum class JobGroup(val label: String) {
    /** Food on the table: farmers and hunters. */
    PROVIDERS("Providers"),

    /** Everything the town is made of: gatherers, builders, artisans. */
    LABOURERS("Labourers"),

    /** Healers and scholars — the town investing in itself rather than in today. */
    LEARNED("Learned"),

    /** Under arms. */
    MILITARY("Military"),

    /** Children, and anyone the town has no work for. */
    DEPENDENTS("Dependents");

    companion object {
        fun of(job: Job): JobGroup = when (job) {
            Job.FARMER, Job.HUNTER -> PROVIDERS
            Job.GATHERER, Job.BUILDER, Job.ARTISAN -> LABOURERS
            Job.SCHOLAR, Job.HEALER -> LEARNED
            Job.SOLDIER -> MILITARY
            Job.CHILD, Job.IDLE -> DEPENDENTS
        }
    }
}

/**
 * What kind of soldier someone is, derived from what their civ can equip them with.
 *
 * Deliberately *derived* rather than a job a citizen is assigned: the town does not choose between
 * archers and cavalry, it builds an armoury and its soldiers become better armed. That keeps the
 * weekly job assignment (AD-25) a food-and-materials decision and makes military variety a
 * consequence of the building layer, which is where a Premier's agenda already lives.
 */
enum class UnitKind(val label: String) {
    /** No barracks: whoever picked up a spear. */
    MILITIA("Militia"),

    /** A barracks turns them into trained infantry. */
    INFANTRY("Infantry"),

    /** A watchtower's marksmen, who fight from the walls. */
    ARCHERS("Archers"),

    /** An armoury puts them in mail. */
    MEN_AT_ARMS("Men-at-arms"),

    // The unique units: one people fields each, and nobody else can. Derived from the civ's
    // founding allocation (see CivArchetype), so they cost no content and cannot contradict the
    // trait sheet the player filled in.

    /** Tillers: farmhands who fight in season, and never run out of supply. */
    REAPERS("Reapers"),

    /** Stalkers: hunters who treat a battle line as a herd. */
    BEASTMASTERS("Beastmasters"),

    /** Wrights: builders who bring down a wall the way they put one up. */
    SAPPERS("Sappers"),

    /** Wardens: masons who fight from their own stonework. */
    WALLWRIGHTS("Wallwrights"),

    /** The Enduring: infantry who are still standing when the line has gone. */
    SHIELDBEARERS("Shieldbearers"),

    /** Outriders: the reason a raid arrives before the warning does. */
    LANCERS("Lancers"),
}

enum class Resource { FOOD, WOOD, STONE, KNOWLEDGE, WEALTH }

/** The five inherited traits allocated on the opening screen. */
enum class Trait { SPEED, HEALTH, HUNTING, ELEMENTS, FARMING, GATHERING }

/** The five building categories a Premier's agenda is a weight vector over. */
enum class BuildingCategory { FARMS, HEALTH, MILITARY, TECH, LIFESTYLE }

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** Premier temperament: how far they deviate from need-based building. */
enum class Temperament { PASSIVE, PRAGMATIC, AMBITIOUS, ZEALOT }

/** Rival AI personality, which biases derived aggression rather than dictating behaviour. */
enum class Personality { ISOLATIONIST, MERCANTILE, EXPANSIONIST, MILITANT }

enum class EndState { COLLAPSE, CONQUEST, ENDURANCE, ASCENSION }
