package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Traits as TraitConfig

/**
 * What kind of people a civilisation is, read off the allocation it was founded with.
 *
 * The trait sheet decided what a people were *good at* and, since AD-72, what their town tended to
 * build. It did not make them feel like a distinct nation: five towns on one island differed by
 * numbers a player could not see. This is the Age of Empires answer — a named people with a couple
 * of bonuses of its own and a unit nobody else fields — derived rather than authored, so it costs no
 * content and cannot disagree with the sheet the player actually filled in.
 *
 * **It is fixed at founding, not recomputed.** A civ earns a trait point every decade (AD-50), so a
 * people derived from *current* traits could stop being Stalkers halfway through a run because they
 * spent two points on Farming. A nation is what it was founded as; that is what makes it an identity
 * rather than a status effect. `Civilization.archetype` is therefore set once and saved.
 *
 * **Every bonus here sits outside the food economy, deliberately.** AD-79 spent a long pass giving
 * each of the six traits its own route past the food gate, and those routes balance against each
 * other within a few percent. A bonus that touched farm yield or ration size would land on top of
 * that and reopen the cliff from the other side. So these reach military strength, march speed,
 * building cost, decay and defence — the parts of the game the food work does not price.
 */
enum class CivArchetype(
    /** The trait that defines this people. */
    val trait: Trait,
    /** What the Rivals panel and the end screen call them. */
    val peopleName: String,
    /** One line a player can read and act on. */
    val blurb: String,
    /** The unit only this people fields, once they have the building for it. */
    val uniqueUnit: UnitKind,
    /** Multiplier on this civ's military strength. */
    val strengthBonus: Double = 1.0,
    /** Multiplier on how fast this civ's armies cross the map. */
    val marchBonus: Double = 1.0,
    /** Multiplier on the wood and stone a building costs this civ. */
    val buildCostBonus: Double = 1.0,
    /** Multiplier on how fast this civ's buildings fall into disrepair. */
    val decayBonus: Double = 1.0,
    /** Multiplier on the integrity of this civ's walls, which is what a siege must chew through. */
    val wallBonus: Double = 1.0,
    /** Multiplier on this people's resistance to disease. */
    val diseaseBonus: Double = 1.0,
) {
    /** Farming: a settled people whose stores outlast a bad year. */
    TILLERS(
        trait = Trait.FARMING,
        peopleName = "Tillers",
        blurb = "Settled farmers. Their buildings are cheap to raise and slow to fall down.",
        uniqueUnit = UnitKind.REAPERS,
        buildCostBonus = 0.90,
        decayBonus = 0.75,
    ),

    /** Hunting: the people who are dangerous because of what they do every day. */
    STALKERS(
        trait = Trait.HUNTING,
        peopleName = "Stalkers",
        blurb = "Hunters who fight as they hunt. Their soldiers hit markedly harder than anyone's.",
        uniqueUnit = UnitKind.BEASTMASTERS,
        strengthBonus = 1.25,
    ),

    /** Gathering: this is the people who build things (AD-57), taken to its conclusion. */
    WRIGHTS(
        trait = Trait.GATHERING,
        peopleName = "Wrights",
        blurb = "Builders. Their structures cost a fifth less, and their walls are built to hold.",
        uniqueUnit = UnitKind.SAPPERS,
        buildCostBonus = 0.80,
        wallBonus = 1.30,
    ),

    /** Elements: shelter, against weather and against armies alike. */
    WARDENS(
        trait = Trait.ELEMENTS,
        peopleName = "Wardens",
        blurb = "Weatherers. Nothing they build decays, and their walls take a siege to break.",
        uniqueUnit = UnitKind.WALLWRIGHTS,
        decayBonus = 0.45,
        wallBonus = 1.55,
    ),

    /** Health: a people who simply do not stop. */
    ENDURING(
        trait = Trait.HEALTH,
        peopleName = "the Enduring",
        blurb = "Hardy stock. Plague passes them by, and their soldiers are hard to put down.",
        uniqueUnit = UnitKind.SHIELDBEARERS,
        strengthBonus = 1.10,
        diseaseBonus = 1.50,
    ),

    /** Speed: the people who arrive before you are ready. */
    OUTRIDERS(
        trait = Trait.SPEED,
        peopleName = "Outriders",
        blurb = "Quick. Their armies cross the island in half the time anyone expects.",
        uniqueUnit = UnitKind.LANCERS,
        marchBonus = 1.45,
        strengthBonus = 1.05,
    ),
    ;

    companion object {
        /**
         * The people an opening allocation makes, by its highest trait above base.
         *
         * Ties break by [Trait] order rather than by anything clever, because the rule has to be
         * stated on the allocation screen in one sentence and a player has to be able to predict it.
         * An allocation with nothing spent — every trait at base — has no dominant trait at all and
         * comes out [TILLERS], the settled default, rather than throwing.
         */
        fun of(opening: TraitAllocation): CivArchetype {
            // Iterated in *Trait* order, not in this enum's declaration order, because the tie-break
            // rule is stated to the player in terms of the traits on their own allocation screen.
            // Those two orders are not the same, and a test caught them disagreeing.
            var best: CivArchetype = byTrait.getValue(Trait.entries.first())
            var bestAbove = Int.MIN_VALUE
            for (trait in Trait.entries) {
                val above = opening[trait] - TraitConfig.BASE_VALUE
                if (above > bestAbove) {
                    bestAbove = above
                    best = byTrait.getValue(trait)
                }
            }
            return best
        }

        /** Every trait has exactly one people; `every trait makes a people of its own` pins it. */
        private val byTrait: Map<Trait, CivArchetype> = entries.associateBy { it.trait }

        /** Looked up by name, tolerantly, for decoding a save. See AD-57 on enum names. */
        fun byNameOrNull(name: String): CivArchetype? = entries.firstOrNull { it.name == name }
    }
}
