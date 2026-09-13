package com.pixeltown.sim

/**
 * Core enumerations shared across the simulation. Kept in one file so that the shape of the
 * domain is readable at a glance; behaviour lives in the systems, numbers live in [GameConfig].
 */

enum class TerrainType { OCEAN, BEACH, PLAIN, FOREST, HILL, MOUNTAIN, RIVER, MARSH }

enum class Sex { FEMALE, MALE }

enum class Job { CHILD, FARMER, HUNTER, GATHERER, BUILDER, SOLDIER, SCHOLAR, HEALER, ARTISAN, IDLE }

enum class Resource { FOOD, WOOD, STONE, KNOWLEDGE, WEALTH }

/** The five inherited traits allocated on the opening screen. */
enum class Trait { SPEED, HEALTH, HUNTING, ELEMENTS, FARMING }

/** The five building categories a Premier's agenda is a weight vector over. */
enum class BuildingCategory { FARMS, HEALTH, MILITARY, TECH, LIFESTYLE }

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** Premier temperament: how far they deviate from need-based building. */
enum class Temperament { PASSIVE, PRAGMATIC, AMBITIOUS, ZEALOT }

/** Rival AI personality, which biases derived aggression rather than dictating behaviour. */
enum class Personality { ISOLATIONIST, MERCANTILE, EXPANSIONIST, MILITANT }

enum class EndState { COLLAPSE, CONQUEST, ENDURANCE, ASCENSION }
