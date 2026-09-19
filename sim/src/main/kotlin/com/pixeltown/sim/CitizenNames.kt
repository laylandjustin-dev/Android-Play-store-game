package com.pixeltown.sim

/**
 * Every citizen's name, stored as two integers and rendered on demand.
 *
 * Three decisions worth recording.
 *
 * **Names are derived, not stored as strings.** A late-run colony holds thousands of people and a
 * save file already carries two 16,384-element float arrays (AD-42); two `Int`s per citizen cost
 * eight bytes and gzip to nothing, where two strings would have been the largest thing in the
 * file. [firstName] and [surname] are pure functions of those ints, so a name never has to be
 * migrated and can never disagree with itself between the HUD and the Chronicle.
 *
 * **A given name is built from three syllable positions, a family name from one list.** Given
 * names must be near-unique within a town, so they come from a product (`FIRST x MID x LAST`,
 * several thousand combinations) rather than a list. Family names must *repeat* — that is the
 * whole point of a lineage — so they come from a list short enough that a town has recognisable
 * families in it.
 *
 * **Syllable tables are append-only.** A name is an index into them, so inserting an entry in the
 * middle would rename every citizen in every existing save. New entries go on the end.
 */
object CitizenNames {

    private val FIRST = listOf(
        "Ald", "Bren", "Cor", "Dar", "Eil", "Fen", "Gar", "Hal", "Ir", "Jor",
        "Kel", "Lys", "Mar", "Nev", "Or", "Pell", "Quin", "Ros", "Sar", "Tam",
        "Ulf", "Ver", "Wyn", "Yar", "Zel", "Bal", "Cad", "Dre", "Esk", "Fal",
        "Gil", "Hes", "Ith", "Kar", "Lor", "Mir", "Nul", "Oth", "Rav", "Syl",
    )
    private val MID = listOf(
        "", "a", "e", "i", "o", "u", "ae", "ei", "ia", "ou", "ya", "we", "ry", "ol",
    )
    private val LAST = listOf(
        "n", "s", "th", "r", "l", "ric", "wen", "dal", "mir", "ath", "ell", "ost",
    )
    private val FAMILY = listOf(
        "Ashgrove", "Blackfen", "Coldwater", "Dunmoor", "Eastmarch", "Fairholt",
        "Greyhill", "Hearthstone", "Ironbrook", "Larkfield", "Mossbank", "Northreach",
        "Oakhollow", "Pinewatch", "Redbarrow", "Stonewell", "Thornfield", "Westmere",
        "Ambervale", "Briarwick", "Cinderholt", "Deepford", "Elmspire", "Foxmere",
        "Glasswater", "Hollowmere", "Icewold", "Kestrelmoor", "Longbarrow", "Marshlight",
    )

    /** How many distinct given names exist. Worth knowing when reading a town's name collisions. */
    val GIVEN_NAME_COUNT: Int = FIRST.size * MID.size * LAST.size

    val FAMILY_COUNT: Int = FAMILY.size

    fun firstName(code: Int): String {
        val c = if (code < 0) 0 else code
        val first = FIRST[c % FIRST.size]
        val mid = MID[(c / FIRST.size) % MID.size]
        val last = LAST[(c / (FIRST.size * MID.size)) % LAST.size]
        return first + mid + last
    }

    fun surname(familyId: Int): String = FAMILY[(if (familyId < 0) 0 else familyId) % FAMILY.size]

    fun fullName(code: Int, familyId: Int): String = "${firstName(code)} ${surname(familyId)}"

    /** A fresh given name. */
    fun randomGiven(rng: SimRandom): Int = rng.nextInt(GIVEN_NAME_COUNT)

    /** A fresh family, for a founding settler. Children inherit instead of drawing. */
    fun randomFamily(rng: SimRandom): Int = rng.nextInt(FAMILY_COUNT)
}
