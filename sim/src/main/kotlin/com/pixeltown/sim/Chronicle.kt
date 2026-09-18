package com.pixeltown.sim

/**
 * The rolling record of what happened, and the backbone of two features: the live event feed on
 * the World screen, and the "while you were away" report that is the game's main retention hook.
 *
 * Events are structured, not pre-rendered strings — narrative templating happens in the UI, and
 * the Ledger needs to count deaths by cause without parsing text. The buffer is a fixed-size ring
 * so a 500-year run cannot grow it without bound.
 */
class Chronicle(private val capacity: Int = GameConfig.Meta.CHRONICLE_BUFFER_SIZE) {

    private val buffer = ArrayDeque<ChronicleEvent>()

    /** Running totals that must survive events falling off the end of the ring. */
    private val totals = HashMap<ChronicleEventKind, Int>()
    private val deathsByCause = HashMap<DeathCause, Int>()

    val size: Int get() = buffer.size

    fun record(event: ChronicleEvent) {
        buffer.addLast(event)
        while (buffer.size > capacity) buffer.removeFirst()
        totals[event.kind] = (totals[event.kind] ?: 0) + 1
        if (event.kind == ChronicleEventKind.DEATH) {
            event.deathCause?.let { deathsByCause[it] = (deathsByCause[it] ?: 0) + 1 }
        }
    }

    fun recent(count: Int): List<ChronicleEvent> = buffer.takeLast(count)

    /** Events from [fromTick] onward — what the return report is built from. */
    fun since(fromTick: Long): List<ChronicleEvent> = buffer.filter { it.tick >= fromTick }

    fun totalOf(kind: ChronicleEventKind): Int = totals[kind] ?: 0

    fun deathsBy(cause: DeathCause): Int = deathsByCause[cause] ?: 0

    /** Cause-of-death breakdown for the Ledger screen. */
    fun deathBreakdown(): Map<DeathCause, Int> = deathsByCause.toMap()

    /** Everything the save file needs, including the totals that outlive the ring. */
    fun snapshot(): ChronicleSave = ChronicleSave(
        events = buffer.map {
            ChronicleEventSave(it.tick, it.kind, it.civId, it.citizenId, it.deathCause, it.detail, it.value)
        },
        totals = totals.toMap(),
        deathsByCause = deathsByCause.toMap(),
    )

    fun restoreFrom(save: ChronicleSave) {
        clear()
        for (event in save.events) {
            buffer.addLast(
                ChronicleEvent(event.tick, event.kind, event.civId, event.citizenId, event.deathCause, event.detail, event.value),
            )
        }
        totals.putAll(save.totals)
        deathsByCause.putAll(save.deathsByCause)
    }

    fun clear() {
        buffer.clear()
        totals.clear()
        deathsByCause.clear()
    }
}

enum class ChronicleEventKind {
    FOUNDING,
    BIRTH,
    DEATH,
    PAIRING,
    ELECTION,
    BUILDING_COMPLETED,
    TECH_TIER,
    DISASTER,
    RAID,
    WAR_DECLARED,
    PEACE,
    TRADE,
    COUP,
    GENERATION,
    RUN_ENDED,
}

/**
 * One recorded event. [detail] carries a small amount of context for narrative templating (a
 * building name, a rival's name); it is never the whole sentence.
 */
data class ChronicleEvent(
    val tick: Long,
    val kind: ChronicleEventKind,
    val civId: Int,
    val citizenId: Int? = null,
    val deathCause: DeathCause? = null,
    val detail: String? = null,
    val value: Int = 0,
)
