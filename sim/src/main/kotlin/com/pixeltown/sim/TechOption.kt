package com.pixeltown.sim

/**
 * What a civilisation learns when it reaches a new tech tier.
 *
 * A tier used to be a number that went up: it multiplied output and unlocked buildings, and the
 * player watched it happen. It is now a decision. Each tier offers three ways to spend what the
 * town has learned, they pull in different directions, and the choice is permanent — so two runs
 * that reach tier 4 can be quite different civilisations by the time they get there.
 *
 * The three at each tier are deliberately one for food, one for force and one for everything else,
 * so no tier is a dominant pick for every build: a people who cannot feed themselves take the
 * granary, a people at war take the drill, and a people who are safe and fed compound.
 */
enum class TechOption(
    val tier: Int,
    val label: String,
    val blurb: String,
    val effects: TechEffects,
) {
    // ---- tier 1 ----
    CROP_ROTATION(1, "Crop rotation", "fields recover between seasons: +12% farm yield", TechEffects(farmYield = 0.12)),
    SALTING(1, "Salting", "a harvest keeps: +50% food storage", TechEffects(foodStorage = 0.50)),
    DRILL(1, "Drill", "every hand knows the spear: +25% military strength", TechEffects(military = 0.25)),

    // ---- tier 2 ----
    DEEP_PLOUGH(2, "Deep plough", "heavier soil opens up: +15% farm yield", TechEffects(farmYield = 0.15)),
    BOILED_WATER(2, "Boiled water", "sickness spreads slower: +10% disease resistance", TechEffects(diseaseResist = 0.10)),
    PALISADES(2, "Palisades", "the town is harder to take: +15% safety", TechEffects(safety = 0.15)),

    // ---- tier 3 ----
    CRUCIBLE(3, "Crucible", "better tools, faster work: +20% build speed", TechEffects(buildSpeed = 0.20)),
    HEARTH_CRAFT(3, "Hearth craft", "less food burned to stay warm: −8% rations", TechEffects(rationDiscount = 0.08)),
    STANDING_GUARD(3, "Standing guard", "soldiers who train: +35% military strength", TechEffects(military = 0.35)),

    // ---- tier 4 ----
    CROP_ROTATION_II(4, "Three-field system", "+18% farm yield", TechEffects(farmYield = 0.18)),
    PHYSICIANS(4, "Physicians", "+15% disease resistance and better care", TechEffects(diseaseResist = 0.15, care = 30.0)),
    SCRIPTORIUM(4, "Scriptorium", "+35% knowledge", TechEffects(knowledge = 0.35)),

    // ---- tier 5 ----
    GRANARY_STATE(5, "State granaries", "+80% food storage", TechEffects(foodStorage = 0.80)),
    QUARANTINE(5, "Quarantine", "+20% disease resistance", TechEffects(diseaseResist = 0.20)),
    ENGINEERS(5, "Engineers", "+30% build speed and +20% safety", TechEffects(buildSpeed = 0.30, safety = 0.20)),

    // ---- tier 6 ----
    SELECTIVE_BREEDING(6, "Selective breeding", "+25% farm yield", TechEffects(farmYield = 0.25)),
    THE_ACADEMY(6, "The academy", "+60% knowledge", TechEffects(knowledge = 0.60)),
    THE_LEGION(6, "The legion", "+60% military strength", TechEffects(military = 0.60)),
    ;

    companion object {
        /** The three options offered at [tier], or empty if that tier offers none. */
        fun forTier(tier: Int): List<TechOption> = entries.filter { it.tier == tier }

        /**
         * What a civ with no player picks: the option its personality favours, falling back to the
         * first offered so this is total. Used for rivals, and for the headless harness, which has
         * no player and must never block.
         */
        fun choiceFor(personality: Personality, tier: Int): TechOption? {
            val options = forTier(tier)
            if (options.isEmpty()) return null
            val wanted = when (personality) {
                Personality.MILITANT -> options.firstOrNull { it.effects.military > 0.0 || it.effects.safety > 0.0 }
                Personality.MERCANTILE -> options.firstOrNull { it.effects.farmYield > 0.0 || it.effects.foodStorage > 0.0 }
                Personality.EXPANSIONIST -> options.firstOrNull { it.effects.farmYield > 0.0 || it.effects.buildSpeed > 0.0 }
                Personality.ISOLATIONIST -> options.firstOrNull {
                    it.effects.diseaseResist > 0.0 || it.effects.rationDiscount > 0.0 || it.effects.knowledge > 0.0
                }
            }
            return wanted ?: options.first()
        }
    }
}

/**
 * What a [TechOption] does, as deltas folded into the same [CivEffects] buildings produce.
 *
 * Reusing that struct is the whole trick: every system already reads its civ's effects, so a tech
 * choice needs no new plumbing and cannot be forgotten by one of them.
 */
data class TechEffects(
    /** Added to the farm-yield multiplier, where 0.12 means +12%. */
    val farmYield: Double = 0.0,
    /** Added to the knowledge multiplier. */
    val knowledge: Double = 0.0,
    /** Added to the build-speed multiplier. */
    val buildSpeed: Double = 0.0,
    /** A fraction of base food storage, added. */
    val foodStorage: Double = 0.0,
    /** A fraction of the civ's building-derived military strength, added. */
    val military: Double = 0.0,
    /** Added to the safety term outright. */
    val safety: Double = 0.0,
    /** Added to disease resistance outright. */
    val diseaseResist: Double = 0.0,
    /** Care capacity added outright, as a hospital would. */
    val care: Double = 0.0,
    /** Fraction taken off the daily ration, where 0.08 means people eat 8% less. */
    val rationDiscount: Double = 0.0,
)
