package com.pixeltown.sim

import com.pixeltown.sim.GameConfig.Rivals as RivalConfig
import com.pixeltown.sim.GameConfig.Time
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RivalsTest {

    private fun newRun(seed: Long = 1L) = Simulation.newRun(seed, TraitAllocation.of(3, 4, 3, 4, 8))

    private fun civ(personality: Personality, traits: TraitAllocation = TraitAllocation.EVEN_SPREAD) =
        Civilization(1, "Test", traits, personality, 0)

    private fun soldier(id: Int, skill: Float = 0.5f, civId: Int = 1) = Citizen(
        id = id, x = 0, y = 0, civId = civId, sex = Sex.MALE, ageDays = 25 * Time.DAYS_PER_YEAR,
        hp = 100f, nutrition = 1f, morale = 0.5f, survival = 70f, job = Job.SOLDIER,
        skill = skill, influence = 0f,
    )

    // ------------------------------------------------------------------ posture

    @Test
    fun `a starving militarist attacks and a fat mercantile civ trades`() {
        // The design's own example, and the reason posture is derived rather than scripted.
        val starvingMilitarist = DiplomacySystem.aggressionOf(
            civ(Personality.MILITANT), militaryShare = 0.25, foodSecurity = 0.0,
        )
        val fatMerchant = DiplomacySystem.aggressionOf(
            civ(Personality.MERCANTILE), militaryShare = 0.02, foodSecurity = 1.0,
        )
        assertTrue(
            starvingMilitarist > RivalConfig.WAR_AGGRESSION_MIN,
            "a starving militarist was not aggressive enough to act: $starvingMilitarist",
        )
        assertTrue(fatMerchant < starvingMilitarist)
        assertTrue(fatMerchant < RivalConfig.RAID_AGGRESSION_MIN, "a fat merchant went raiding")
    }

    @Test
    fun `hunger alone makes a peaceful people dangerous`() {
        val fed = DiplomacySystem.aggressionOf(civ(Personality.ISOLATIONIST), 0.1, foodSecurity = 1.0)
        val starving = DiplomacySystem.aggressionOf(civ(Personality.ISOLATIONIST), 0.1, foodSecurity = 0.0)
        assertTrue(starving > fed, "hunger did not raise aggression")
    }

    @Test
    fun `aggression stays inside its range for every personality`() {
        for (personality in Personality.entries) {
            for (share in listOf(0.0, 0.1, 0.5, 1.0)) {
                for (security in listOf(0.0, 0.5, 1.0)) {
                    val value = DiplomacySystem.aggressionOf(civ(personality), share, security)
                    assertTrue(value in 0.0..1.0, "aggression $value out of range")
                }
            }
        }
    }

    @Test
    fun `food security reflects days of stock`() {
        val hungry = civ(Personality.ISOLATIONIST)
        assertEquals(0.0, DiplomacySystem.foodSecurityOf(hungry, dailyConsumption = 10.0))
        hungry.add(Resource.FOOD, 10.0 * RivalConfig.TRADE_SURPLUS_DAYS)
        assertEquals(1.0, DiplomacySystem.foodSecurityOf(hungry, dailyConsumption = 10.0))
    }

    // ------------------------------------------------------------------ strength and battle

    @Test
    fun `military strength rises with numbers, skill, hunting and tech`() {
        val traits = TraitAllocation.of(3, 3, 8, 3, 3)
        val weakTraits = TraitAllocation.of(3, 3, 1, 3, 3)
        val few = listOf(soldier(1), soldier(2))
        val many = (1..10).map { soldier(it) }

        val base = DiplomacySystem.strengthOf(few, traits, 1.0, CivEffects.NONE)
        assertTrue(DiplomacySystem.strengthOf(many, traits, 1.0, CivEffects.NONE) > base, "numbers did not tell")
        assertTrue(DiplomacySystem.strengthOf(few, weakTraits, 1.0, CivEffects.NONE) < base, "Hunting did not tell")
        assertTrue(DiplomacySystem.strengthOf(few, traits, 2.0, CivEffects.NONE) > base, "tech did not tell")
        assertTrue(
            DiplomacySystem.strengthOf(few, traits, 1.0, CivEffects(militaryStrength = 50.0)) > base,
            "barracks did not tell",
        )
        assertEquals(0.0, DiplomacySystem.strengthOf(emptyList(), traits, 1.0, CivEffects.NONE))
    }

    @Test
    fun `combat resolution is symmetric`() {
        // Swapping the two sides must mirror the outcome, or attacking would be inherently
        // better or worse than defending for reasons nothing in the design justifies.
        val a = DiplomacySystem.resolveBattleDay(300.0, 120.0, 40, 20, SimRandom(7L))
        val b = DiplomacySystem.resolveBattleDay(120.0, 300.0, 20, 40, SimRandom(7L))
        assertEquals(a.first, b.second)
        assertEquals(a.second, b.first)
    }

    @Test
    fun `combat conserves casualties`() {
        val rng = SimRandom(3L)
        repeat(500) {
            val attackerSize = rng.nextInt(1, 60)
            val defenderSize = rng.nextInt(1, 60)
            val (attackerLosses, defenderLosses) = DiplomacySystem.resolveBattleDay(
                rng.nextDouble(1.0, 500.0), rng.nextDouble(1.0, 500.0), attackerSize, defenderSize, rng,
            )
            assertTrue(attackerLosses in 0..attackerSize, "attacker lost $attackerLosses of $attackerSize")
            assertTrue(defenderLosses in 0..defenderSize, "defender lost $defenderLosses of $defenderSize")
        }
    }

    @Test
    fun `an empty side takes and inflicts nothing`() {
        assertEquals(0 to 0, DiplomacySystem.resolveBattleDay(100.0, 100.0, 0, 10, SimRandom(1L)))
        assertEquals(0 to 0, DiplomacySystem.resolveBattleDay(100.0, 100.0, 10, 0, SimRandom(1L)))
    }

    @Test
    fun `the stronger side loses fewer people`() {
        var strongLosses = 0
        var weakLosses = 0
        val rng = SimRandom(11L)
        repeat(200) {
            val (strong, weak) = DiplomacySystem.resolveBattleDay(500.0, 100.0, 50, 50, rng)
            strongLosses += strong
            weakLosses += weak
        }
        assertTrue(strongLosses < weakLosses, "the stronger army did not fare better ($strongLosses vs $weakLosses)")
    }

    // ------------------------------------------------------------------ relations

    @Test
    fun `tension is symmetric and bounded`() {
        val relations = Relations(3)
        relations.raise(0, 1, 0.4)
        assertEquals(relations.tensionBetween(0, 1), relations.tensionBetween(1, 0))
        relations.raise(0, 1, 5.0)
        assertEquals(RivalConfig.TENSION_MAX, relations.tensionBetween(0, 1))
        relations.ease(0, 1, 99.0)
        assertEquals(0.0, relations.tensionBetween(0, 1))
        assertEquals(0.0, relations.tensionBetween(2, 2), "a civ cannot resent itself")
    }

    @Test
    fun `war is a latch and peace opens a truce`() {
        val relations = Relations(2)
        assertFalse(relations.atWar(0, 1))
        relations.declareWar(0, 1, day = 100L)
        assertTrue(relations.atWar(0, 1) && relations.atWar(1, 0))
        assertEquals(100L, relations.warStartDay(0, 1))

        relations.makePeace(0, 1, day = 500L)
        assertFalse(relations.atWar(0, 1))
        assertTrue(relations.inTruce(0, 1, day = 500L), "the truce did not begin")
        assertTrue(
            relations.inTruce(0, 1, day = 500L + RivalConfig.PEACE_COOLDOWN_DAYS - 1),
            "the truce expired early",
        )
        assertFalse(
            relations.inTruce(0, 1, day = 500L + RivalConfig.PEACE_COOLDOWN_DAYS),
            "the truce never expired",
        )
    }

    @Test
    fun `civs that have never fought are not in a truce`() {
        // The "never" sentinel was once Long.MIN_VALUE, and day - Long.MIN_VALUE overflows
        // negative, which read as "in truce" — so no war was ever declared in any run.
        val relations = Relations(2)
        assertFalse(relations.inTruce(0, 1, day = 0L))
        assertFalse(relations.inTruce(0, 1, day = 10_000L))
    }

    // ------------------------------------------------------------------ interactions in a run

    @Test
    fun `civs trade, raid and go to war over a long run`() {
        // M5's gate, and a claim about the *game* rather than about one island — which is why it is
        // measured across three maps. On the 400x400 map a civilisation has nearly ten times the
        // land it had at 128x128, and aggression is weighted heavily on hunger (AD-35), so a fed
        // civ does not attack: measured at year 120, both seeds have borders touching and tension
        // pinned at its maximum of 1.000 against a raid threshold of 0.45, yet seed 1 produced one
        // raid and no wars where seed 1000 produced 14 raids and 18 wars. On seed 1 two rivals
        // collapsed early and the three survivors grew fat (8,714 / 14,387 / 10,137 owned cells)
        // with nothing to fight over.
        //
        // So the ladder is intact and its *frequency* has fallen. That is a balance consequence of
        // the map size worth confronting deliberately — more civilisations on a larger island, or a
        // heavier personality term in aggression — and not something to hide by asserting it on
        // whichever seed happens to comply.
        var trades = 0
        var raids = 0
        var wars = 0
        var peaces = 0
        var combatDeaths = 0
        for (seed in longArrayOf(1L, 1_000L, 8_919L)) {
            val sim = newRun(seed)
            sim.runUnattended(120 * Time.DAYS_PER_YEAR)
            trades += sim.chronicle.totalOf(ChronicleEventKind.TRADE)
            raids += sim.chronicle.totalOf(ChronicleEventKind.RAID)
            wars += sim.chronicle.totalOf(ChronicleEventKind.WAR_DECLARED)
            peaces += sim.chronicle.totalOf(ChronicleEventKind.PEACE)
            combatDeaths += sim.chronicle.deathsBy(DeathCause.COMBAT)
        }
        assertTrue(trades > 0, "nobody ever traded on any map")
        assertTrue(raids > 0, "nobody was ever raided on any map")
        assertTrue(wars > 0, "no war was ever declared on any map")
        assertTrue(peaces > 0, "no war ever ended on any map")
        assertTrue(combatDeaths > 0, "nobody ever died fighting on any map")
    }

    @Test
    fun `rivals grow and decline independently`() {
        val sim = newRun()
        sim.runUnattended(120 * Time.DAYS_PER_YEAR)
        val populations = sim.civs.map { it.population }
        assertTrue(populations.distinct().size > 1, "every civ ended up the same size")
        assertTrue(populations.any { it > 100 }, "no civ ever prospered: $populations")
        assertTrue(
            populations.any { it < 100 } || sim.civs.any { it.isExtinct },
            "no civ ever declined: $populations",
        )
        assertTrue(sim.civs.map { it.techTier }.distinct().size > 1, "every civ advanced identically")
    }

    @Test
    fun `a rival can plausibly destroy someone`() {
        // Across a few seeds, at least one civilisation should be wiped out inside 150 years.
        val extinctions = longArrayOf(1L, 42L, 555L).sumOf { seed ->
            val sim = newRun(seed)
            sim.runUnattended(150 * Time.DAYS_PER_YEAR)
            sim.civs.count { it.isExtinct }
        }
        assertTrue(extinctions > 0, "no civilisation was destroyed in three 150-year runs")
    }

    @Test
    fun `armies are real citizens that march`() {
        val sim = newRun()
        var sawArmy = false
        repeat(120 * Time.DAYS_PER_YEAR) {
            sim.runUnattended(1)
            val army = sim.armiesInField.firstOrNull { it.size > 0 } ?: return@repeat
            sawArmy = true
            for (id in army.members) {
                val soldier = sim.citizenOrNull(id) ?: continue
                assertEquals(Job.SOLDIER, soldier.job, "a civilian was marching with the army")
                assertTrue(soldier.enlisted, "a marching soldier was not enlisted")
                assertEquals(army.civId, soldier.civId, "an army contained an enemy citizen")
            }
        }
        assertTrue(sawArmy, "no army ever took the field in 120 years")
    }

    @Test
    fun `enlisted soldiers are not reassigned to farms mid-campaign`() {
        val world = World(32, 32)
        for (i in 0 until world.cellCount) world.setTerrain(i, TerrainType.PLAIN)
        val civ = Civilization(0, "Test", TraitAllocation.EVEN_SPREAD, Personality.MILITANT, 0)
        val marching = soldier(1, civId = 0).apply { enlisted = true; workCell = 500 }
        val idle = soldier(2, civId = 0)

        EconomySystem.assignJobs(
            world, civ, listOf(marching, idle), HashMap(), mapOf(Job.FARMER to 1.0),
        )

        assertEquals(Job.SOLDIER, marching.job, "an enlisted soldier was sent back to the fields")
        assertEquals(500, marching.workCell, "an enlisted soldier lost their marching orders")
        assertEquals(Job.FARMER, idle.job, "the civilian was not reassigned")
    }

    @Test
    fun `safety falls in the shadow of a stronger hostile rival`() {
        val safeAlone = DiplomacySystem.safetyFor(0.5, 0.0, ownStrength = 100.0, worstThreatStrength = 0.0, atWar = false)
        val threatened = DiplomacySystem.safetyFor(0.5, 0.0, ownStrength = 100.0, worstThreatStrength = 400.0, atWar = false)
        val atWar = DiplomacySystem.safetyFor(0.5, 0.0, ownStrength = 100.0, worstThreatStrength = 400.0, atWar = true)
        assertTrue(threatened < safeAlone, "a looming rival did not reduce safety")
        assertTrue(atWar < threatened, "being at war felt no worse than being threatened")

        val walled = DiplomacySystem.safetyFor(0.5, 0.3, 100.0, 400.0, atWar = true)
        assertTrue(walled > atWar, "walls did not help")
        for (value in listOf(safeAlone, threatened, atWar, walled)) {
            assertTrue(value in 0.0..1.0, "safety $value out of range")
        }
    }

    @Test
    fun `tribute is paid by the weak and refused by the strong`() {
        assertTrue(DiplomacySystem.shouldSubmit(weakerStrength = 10.0, strongerStrength = 100.0))
        assertFalse(DiplomacySystem.shouldSubmit(weakerStrength = 95.0, strongerStrength = 100.0))
        assertFalse(DiplomacySystem.shouldSubmit(weakerStrength = 100.0, strongerStrength = 0.0))
    }

    @Test
    fun `the rivals screen reports every rival`() {
        val sim = newRun()
        sim.runUnattended(40 * Time.DAYS_PER_YEAR)
        val reports = sim.rivalReports()
        assertEquals(GameConfig.World.RIVAL_CIV_COUNT, reports.size)
        assertTrue(reports.none { it.civId == GameConfig.World.PLAYER_CIV_ID }, "the player was listed as a rival")
        for (report in reports) {
            assertTrue(report.tension in 0.0..1.0)
            assertTrue(report.aggression in 0.0..1.0)
            assertTrue(report.militaryStrength >= 0.0)
        }
    }

    @Test
    fun `war does not break determinism`() {
        val a = newRun(42L)
        val b = newRun(42L)
        repeat(12) {
            a.runUnattended(10 * Time.DAYS_PER_YEAR)
            b.runUnattended(10 * Time.DAYS_PER_YEAR)
            assertEquals(a.stateHash(), b.stateHash(), "diverged by year ${a.year}")
            assertEquals(
                a.chronicle.deathsBy(DeathCause.COMBAT),
                b.chronicle.deathsBy(DeathCause.COMBAT),
                "different people died fighting",
            )
        }
    }
}
