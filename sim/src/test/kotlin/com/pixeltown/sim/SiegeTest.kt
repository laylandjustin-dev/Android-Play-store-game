package com.pixeltown.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.pixeltown.sim.GameConfig.Buildings as BuildingConfig
import com.pixeltown.sim.GameConfig.Time
import com.pixeltown.sim.GameConfig.Rivals as RivalConfig

/** A wall is an obstacle, not a discount on losing (AD-60). */
class SiegeTest {

    @Test
    fun `a wall starts at full integrity and comes down in pieces`() {
        val wall = Building(id = 1, type = BuildingType.WALL, civId = 0, x = 4, y = 4)
        val full = BuildingConfig.spec(BuildingType.WALL).buildPointsRequired
        assertEquals(full, wall.integrity, 0.0001)
        assertFalse(wall.isRubble)

        assertFalse(wall.damage(full / 3.0), "a third of a wall was the whole wall")
        assertTrue(wall.integrity < full)
        assertFalse(wall.isRubble)

        assertTrue(wall.damage(full), "the wall outlived a blow larger than itself")
        assertTrue(wall.isRubble)
        // Rubble absorbs nothing further: the blow that lands on it is not the blow that broke it.
        assertFalse(wall.damage(full))
    }

    @Test
    fun `a wall costs an army roughly what it cost the defenders`() {
        // The trade the player is choosing when they charter Military, stated as arithmetic rather
        // than asserted in prose: one integrity point is one build point.
        val spec = BuildingConfig.spec(BuildingType.WALL)
        val armyDays = spec.buildPointsRequired / (10.0 * RivalConfig.SIEGE_DAMAGE_PER_STRENGTH)
        assertTrue(
            armyDays > RivalConfig.COMBAT_DAYS,
            "a wall fell faster than an ordinary battle takes, so it is not a siege",
        )
        assertTrue(
            RivalConfig.SIEGE_MAX_DAYS < armyDays * 4,
            "a raid's patience is so long that walls never turn one back",
        )
    }

    @Test
    fun `besiegers take losses, but fewer than an assault would cost`() {
        assertTrue(RivalConfig.SIEGE_ATTACKER_ATTRITION_SCALE > 0.0, "a siege was free for the attacker")
        assertTrue(
            RivalConfig.SIEGE_ATTACKER_ATTRITION_SCALE < 1.0,
            "standing in front of a wall cost as much as storming the town",
        )
    }

    @Test
    fun `a wall's integrity survives a save`() {
        val sim = Simulation.newRun(RunConfig(seed = 31L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattended(15 * Time.DAYS_PER_YEAR)
        // Damage whatever is standing, then check the file remembers it.
        val victim = sim.buildingsOf(0).firstOrNull { it.isComplete }
        assertTrue(victim != null, "fifteen years produced nothing to besiege")
        victim!!.damage(victim.spec.buildPointsRequired / 4.0)
        val wounded = victim.integrity

        val reloaded = Simulation.restore(SaveFormat.decode(SaveFormat.encode(sim.snapshot())))
        val same = reloaded.buildingsOf(0).first { it.id == victim.id }
        assertEquals(wounded, same.integrity, 0.0001, "the save forgot what the siege had done")
    }

    @Test
    fun `a save from before walls could be besieged loads with them intact`() {
        val sim = Simulation.newRun(RunConfig(seed = 31L, traits = TraitAllocation.of(3, 4, 3, 4, 8)))
        sim.runUnattended(15 * Time.DAYS_PER_YEAR)
        assertTrue(sim.buildingsOf(0).isNotEmpty(), "fifteen years produced no buildings at all")
        val text = SaveFormat.encode(sim.snapshot())
        val stripped = Regex(",\"integrity\":[0-9.E-]+").replace(text, "")
        assertTrue(stripped.length < text.length, "there was no integrity field to remove")

        val reloaded = Simulation.restore(SaveFormat.decode(stripped))
        for (building in reloaded.buildingsOf(0)) {
            assertEquals(
                building.spec.buildPointsRequired,
                building.integrity,
                0.0001,
                "an old save's ${building.type} came back as rubble",
            )
        }
    }
}
