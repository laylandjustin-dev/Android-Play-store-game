package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.World as WorldConfig

/**
 * What the player may call their colony, and what the game does with what they type.
 *
 * The name is pure presentation — it is never read by any system that decides anything, so it
 * cannot affect determinism. That is the point of keeping the cleaning here rather than in a UI:
 * every front end gets the same rules, and [sanitise] is a total function with no RNG in it, so a
 * save that stores the cleaned name always reloads to the same string.
 */
object ColonyName {

    /** The name a colony carries when the player does not choose one. */
    const val DEFAULT: String = "Aurelia"

    /**
     * Cleans a typed name into one the game will display, or returns [DEFAULT] if nothing usable
     * is left. Whitespace is collapsed, control characters are dropped, and the result is capped
     * at [WorldConfig.MAX_COLONY_NAME_LENGTH] — the HUD, the Chronicle feed and the rivals list
     * all have to fit it on a phone.
     */
    fun sanitise(typed: String?): String {
        if (typed == null) return DEFAULT
        val sb = StringBuilder(typed.length)
        var pendingSpace = false
        for (ch in typed) {
            when {
                // Any whitespace — including a tab or a newline pasted in — becomes one space,
                // and only once something real has been written, so leading space never appears.
                ch.isWhitespace() -> if (sb.isNotEmpty()) pendingSpace = true
                // Control and format characters would let a name reorder or blank out the line
                // it is drawn on. Dropped rather than rejected: the player gets the rest of it.
                ch.isISOControl() || ch.category == CharCategory.FORMAT -> Unit
                else -> {
                    if (pendingSpace) {
                        if (sb.length + 1 >= WorldConfig.MAX_COLONY_NAME_LENGTH) break
                        sb.append(' ')
                        pendingSpace = false
                    }
                    if (sb.length >= WorldConfig.MAX_COLONY_NAME_LENGTH) break
                    sb.append(ch)
                }
            }
        }
        val cleaned = sb.toString()
        return if (cleaned.isEmpty()) DEFAULT else cleaned
    }

    /**
     * Names for the four rivals, avoiding whatever the player called their own colony.
     *
     * A player who names their town "Kressen" would otherwise share a name with a rival, and the
     * Chronicle feed — which prints names, not ids — would be unreadable. The pool is longer than
     * the number of civs so there is always a spare, and the choice is a deterministic scan
     * rather than a roll, so it costs nothing from the RNG stream.
     */
    fun rivalNames(playerName: String, count: Int): List<String> {
        val taken = playerName.lowercase()
        return POOL.asSequence().filter { it.lowercase() != taken }.take(count).toList()
    }

    /** Rival names, in preference order, with spares for when the player takes one. */
    val POOL: List<String> = listOf(
        "Kressen", "Tolmar", "Veyra", "Sildan", "Aurelia", "Morwick", "Calder", "Ashfen",
    )
}
