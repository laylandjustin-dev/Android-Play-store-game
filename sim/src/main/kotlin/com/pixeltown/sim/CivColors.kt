package com.pixeltown.sim

/**
 * Which colour each civilisation is drawn in, for one run.
 *
 * `Palette.CIV` is a fixed table, which was fine while the player was always gold. Now that they
 * choose, the mapping is per-run state and belongs in a value the renderer is handed rather than
 * in a global — two runs open at once (a live one and a map preview) must be able to disagree.
 *
 * Rivals are recoloured around the player's pick for the same reason their names are: a rival in
 * the player's own colour would defeat the point of choosing one.
 */
class CivColors(private val colours: IntArray) {

    init {
        require(colours.isNotEmpty()) { "a run needs at least one civ colour" }
    }

    operator fun get(civId: Int): Int = colours[civId.mod(colours.size)]

    fun toIntArray(): IntArray = colours.copyOf()

    companion object {
        /** The stock mapping: gold for the player, the original four for the rivals. */
        val DEFAULT = CivColors(Palette.CIV)

        /**
         * The player takes [choiceIndex] from [Palette.PLAYER_CHOICES]; the rivals keep their own
         * colours, except that any rival wearing the player's choice is moved to the first unused
         * option. An out-of-range index falls back to the default rather than throwing — a save or
         * a URL from a later build must not be able to crash a run.
         */
        fun forPlayerChoice(choiceIndex: Int, civCount: Int = GameConfig.World.TOTAL_CIV_COUNT): CivColors {
            val choices = Palette.PLAYER_CHOICES
            val player = choices[choiceIndex.coerceIn(0, choices.size - 1)]

            val used = mutableSetOf(player)
            val colours = IntArray(civCount)
            colours[GameConfig.World.PLAYER_CIV_ID] = player
            for (id in colours.indices) {
                if (id == GameConfig.World.PLAYER_CIV_ID) continue
                val stock = Palette.CIV[id % Palette.CIV.size]
                val colour = if (used.add(stock)) {
                    stock
                } else {
                    // The player took this rival's colour: give it the first spare nobody holds.
                    (Palette.CIV.asSequence() + choices.asSequence()).first { used.add(it) }
                }
                colours[id] = colour
            }
            return CivColors(colours)
        }
    }
}
