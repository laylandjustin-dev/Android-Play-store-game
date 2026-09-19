package com.pixeltown.sim

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The save format: explicit, versioned data classes rather than serialising the live objects.
 *
 * Three reasons for the extra typing. A save file is a compatibility contract, and a format that
 * mirrors whatever the runtime classes happen to look like today breaks the moment a field is
 * renamed. Only these classes need `@Serializable`, so R8 has one small, obvious surface to keep
 * rather than the whole domain. And the runtime classes stay free to hold whatever mutable,
 * derived, or cached state is convenient.
 *
 * Terrain is not stored: it regenerates exactly from the seed. [SaveGame.terrainHash] is a guard
 * on that — if world generation ever changes, an old save must fail loudly rather than quietly
 * load a player's town onto a different island.
 */
@Serializable
data class SaveGame(
    val version: Int = SaveFormat.VERSION,
    val seed: Long,
    val terrainHash: Int,
    val tick: Long,
    val speedMultiplier: Int,
    val rng: RngState,
    val config: RunConfigSave,
    val endState: EndState? = null,
    val nextCitizenId: Int,
    val nextBuildingId: Int,
    val nextArmyId: Int,
    val world: WorldSave,
    val civs: List<CivSave>,
    val citizens: List<CitizenSave>,
    val buildings: List<BuildingSave>,
    val premiers: List<PremierSave?>,
    /**
     * The election in progress, if any. Easy to forget and invisible until someone saves during
     * the thirty-day campaign window — at which point the candidates would be regenerated from a
     * different point in the RNG stream and a different Premier would take office.
     */
    val campaigns: List<List<CandidateSave>?> = emptyList(),
    val endorsements: List<Int?> = emptyList(),
    val vetoPending: List<Boolean> = emptyList(),
    val elections: List<ElectionSave>,
    val relations: RelationsSave,
    val armies: List<ArmySave>,
    val chronicle: ChronicleSave,
    val legacy: LegacySave,
    /** Wall-clock milliseconds when the game was last saved, for offline catch-up. */
    val savedAtEpochMillis: Long,
)

@Serializable
data class RngState(val s0: Long, val s1: Long, val s2: Long, val s3: Long)

@Serializable
data class RunConfigSave(
    val traits: List<Int>,
    /**
     * Defaulted so a save written before colonies could be named still loads — the run simply
     * carries the default name, which is what it was displaying anyway.
     */
    val colonyName: String = ColonyName.DEFAULT,
    /** Where the player landed and what colour they chose. Defaulted, like the name, for old saves. */
    val startCell: Int? = null,
    val colorIndex: Int = 0,
    val settlers: Int,
    val civCount: Int,
    val skillGrowthMultiplier: Double,
    val startingInfluence: Float,
    val fertilityFloor: Float,
    val startingTensionRelief: Double,
    val startingBuildings: Int,
    val offlineCapHours: Int,
)

/**
 * Only the parts of the grid that diverge from what generation produces. Fertility and wild game
 * are stored as exact floats — quantising them would make a reloaded world drift from the one
 * that was saved, and determinism is the whole architecture.
 */
@Serializable
data class WorldSave(
    val width: Int,
    val height: Int,
    val fertility: FloatArray,
    val wildGame: FloatArray,
    val ownerCivId: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = width * 31 + height
}

@Serializable
data class CivSave(
    val id: Int,
    val name: String,
    val traits: List<Int>,
    val personality: Personality,
    val homeSite: Int,
    val stores: DoubleArray,
    val foodStorageCapacity: Double,
    val techTier: Int,
    val unrest: Double,
    /** Earned-but-unspent growth points, and how many decades have paid out. Defaulted for old saves. */
    val unspentTraitPoints: Int = 0,
    val generationsAwarded: Int = 0,
    /** When the oldest unspent point was earned, so the auto-spend grace survives a reload. */
    val oldestUnspentPointDay: Long = 0L,
    /** An epidemic in progress is part of the run, not a detail to re-roll on load. */
    val epidemicDaysLeft: Int = 0,
    val epidemicCount: Int = 0,
    /** Techs chosen, by name, and a tier whose choice is still open. Both part of the run. */
    /** The standing instruction the player left, if any. Defaulted for older saves. */
    val charter: BuildingCategory? = null,
    val techChoices: List<String> = emptyList(),
    val pendingTechTier: Int? = null,
    val influencePoints: Double,
    val unpaidUpkeepDays: Int,
    val vetoesUsedThisYear: Int,
    val termCount: Int,
    val peakPopulation: Int,
    val totalBirths: Int,
    val totalDeaths: Int,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id
}

@Serializable
data class CitizenSave(
    val id: Int,
    val x: Int,
    val y: Int,
    val civId: Int,
    val sex: Sex,
    val ageDays: Int,
    val hp: Float,
    val nutrition: Float,
    val morale: Float,
    val survival: Float,
    val job: Job,
    val skill: Float,
    /** Individual constitution. Defaulted so a save from before people were individuals loads. */
    val vigour: Float = 1f,
    val influence: Float,
    val partnerId: Int? = null,
    val pregnantUntilDay: Int? = null,
    val homeBuildingId: Int? = null,
    val workCell: Int,
    val enlisted: Boolean,
    val starvingDays: Int,
    val widowedOnDay: Int? = null,
    val politicalBias: BuildingCategory,
    val politicalBiasStrength: Float,
)

@Serializable
data class BuildingSave(
    val id: Int,
    val type: BuildingType,
    val civId: Int,
    val x: Int,
    val y: Int,
    val buildProgress: Double,
    val complete: Boolean,
    val residents: Int,
)

@Serializable
data class PremierSave(
    val citizenId: Int,
    val name: String,
    val agenda: List<Double>,
    val electedAgenda: List<Double>,
    val temperament: Temperament,
    val electedOnDay: Long,
    val termNumber: Int,
    val byCoup: Boolean,
    val built: List<BuildingType>,
    val petitionActive: Boolean,
)

@Serializable
data class CandidateSave(
    val citizenId: Int,
    val name: String,
    val ageYears: Int,
    val job: Job,
    val agenda: List<Double>,
    val temperament: Temperament,
    val votes: Int,
)

@Serializable
data class ElectionSave(
    val day: Long,
    val termNumber: Int,
    val civId: Int,
    val winnerName: String,
    val winnerCitizenId: Int,
    val agenda: List<Double>,
    val temperament: Temperament,
    val voteNames: List<String>,
    val voteCounts: List<Int>,
    val turnout: Int,
)

@Serializable
data class RelationsSave(
    val civCount: Int,
    val tension: List<Double>,
    val war: List<Boolean>,
    val trades: List<Int>,
    val warStartedOn: List<Long>,
    val peaceMadeOn: List<Long>,
)

@Serializable
data class ArmySave(
    val id: Int,
    val civId: Int,
    val targetCivId: Int,
    val targetCell: Int,
    val kind: Army.Kind,
    val startedOnDay: Long,
    val members: List<Int>,
    val startingSize: Int,
    val returning: Boolean,
)

@Serializable
data class ChronicleEventSave(
    val tick: Long,
    val kind: ChronicleEventKind,
    val civId: Int,
    val citizenId: Int? = null,
    val deathCause: DeathCause? = null,
    val detail: String? = null,
    val value: Int = 0,
)

@Serializable
data class ChronicleSave(
    val events: List<ChronicleEventSave>,
    /** Running totals are stored because they must survive events falling off the ring. */
    val totals: Map<ChronicleEventKind, Int>,
    val deathsByCause: Map<DeathCause, Int>,
)

@Serializable
data class LegacySave(
    val chroniclePoints: Int,
    val runsPlayed: Int,
    val bestYears: Int,
    val upgrades: Map<LegacyUpgrade, Int>,
)

/** Reading and writing save files. The app owns the file; this owns the bytes. */
object SaveFormat {

    /** Bump when the format changes in a way older readers cannot handle. */
    const val VERSION = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun encode(save: SaveGame): String = json.encodeToString(SaveGame.serializer(), save)

    fun decode(text: String): SaveGame {
        val save = json.decodeFromString(SaveGame.serializer(), text)
        if (save.version > VERSION) {
            throw IncompatibleSaveException(
                "save was written by a newer version of the game (format ${save.version}, this build reads $VERSION)",
            )
        }
        return save
    }
}

/** Thrown when a save cannot be loaded, rather than loading something subtly wrong. */
class IncompatibleSaveException(message: String) : Exception(message)
