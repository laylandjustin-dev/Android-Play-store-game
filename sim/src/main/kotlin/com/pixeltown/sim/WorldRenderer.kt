package com.pixeltown.sim

/**
 * Paints the world into a flat ARGB pixel buffer: one world cell, one pixel.
 *
 * Lives in `:sim` and knows nothing about Android — the app's only job is to upload the finished
 * [IntArray] to a bitmap. That keeps the drawing logic testable on the JVM, which matters because
 * this is the code that runs on every frame.
 *
 * Layers, painted in order: terrain, then buildings, then citizens on top.
 */
object WorldRenderer {

    /** Shading range applied to terrain by elevation, so relief is readable at zoom 1. */
    private const val SHADE_MIN = 0.80f
    private const val SHADE_MAX = 1.18f

    /**
     * Paints terrain for the whole world into [out], which must hold `world.cellCount` pixels.
     * Terrain changes rarely, so callers may cache this buffer and repaint only the dynamic
     * layers on top of a copy.
     */
    fun renderTerrain(world: World, out: IntArray) {
        require(out.size >= world.cellCount) {
            "pixel buffer holds ${out.size} pixels, world needs ${world.cellCount}"
        }
        for (i in 0 until world.cellCount) {
            val base = Palette.terrainColor(world.terrainAt(i))
            // Higher ground is lighter. Cheap relief shading, no normals needed.
            val shade = SHADE_MIN + (SHADE_MAX - SHADE_MIN) * world.elevation[i]
            out[i] = Palette.scaleBrightness(base, shade)
        }
    }

    /**
     * Tints a cell by its owning civ, used to show claimed territory faintly under the citizens.
     * [strength] 0 leaves the terrain alone, 1 replaces it with the civ colour.
     */
    fun tintOwnership(world: World, out: IntArray, strength: Float) {
        if (strength <= 0f) return
        for (i in 0 until world.cellCount) {
            val civ = world.ownerCivId[i].toInt()
            if (civ < 0) continue
            out[i] = blend(out[i], Palette.civColor(civ), strength)
        }
    }

    /** Draws one citizen pixel: the civ colour, dimmed by survival score. */
    fun drawCitizen(out: IntArray, index: Int, civId: Int, survival: Float) {
        val t = (survival / GameConfig.Survival.MAX).toFloat().coerceIn(0f, 1f)
        val brightness = GameConfig.Render.CITIZEN_MIN_BRIGHTNESS +
            (GameConfig.Render.CITIZEN_MAX_BRIGHTNESS - GameConfig.Render.CITIZEN_MIN_BRIGHTNESS) * t
        out[index] = Palette.scaleBrightness(Palette.civColor(civId), brightness)
    }

    /** Draws a building as a solid block of its category colour, clipped to the world. */
    fun drawBuilding(
        world: World,
        out: IntArray,
        x: Int,
        y: Int,
        footprint: Int,
        category: BuildingCategory,
    ) {
        val color = Palette.BUILDING[category.ordinal]
        for (dy in 0 until footprint) {
            for (dx in 0 until footprint) {
                val px = x + dx
                val py = y + dy
                if (world.inBounds(px, py)) out[world.index(px, py)] = color
            }
        }
    }

    /** Linear per-channel blend of two opaque ARGB colours. */
    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = ((from ushr 16 and 0xFF) * (1 - f) + (to ushr 16 and 0xFF) * f).toInt()
        val g = ((from ushr 8 and 0xFF) * (1 - f) + (to ushr 8 and 0xFF) * f).toInt()
        val b = ((from and 0xFF) * (1 - f) + (to and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Composites a whole frame: terrain (cached), then ownership tint, then citizens on top.
 *
 * Terrain changes only when the world does, so it is painted once into a cache and copied per
 * frame — an array copy of 16,384 ints is far cheaper than re-shading every cell. Only the
 * dynamic layers are drawn per tick.
 */
class FrameRenderer(private val world: World) {

    private val terrainCache = IntArray(world.cellCount)
    private var terrainPainted = false

    /** Repaint the terrain cache — after world generation, or when terrain changes. */
    fun invalidateTerrain() {
        terrainPainted = false
    }

    /**
     * Draws the current state of [simulation] into [out], which must hold one pixel per world
     * cell. [ownershipTint] fades claimed territory toward its civ colour under the citizens.
     */
    fun render(simulation: Simulation, out: IntArray, ownershipTint: Float = 0.12f) {
        require(out.size >= world.cellCount) {
            "pixel buffer holds ${out.size} pixels, world needs ${world.cellCount}"
        }
        if (!terrainPainted) {
            WorldRenderer.renderTerrain(world, terrainCache)
            terrainPainted = true
        }
        terrainCache.copyInto(out, 0, 0, world.cellCount)
        WorldRenderer.tintOwnership(world, out, ownershipTint)

        for (citizen in simulation.citizens) {
            WorldRenderer.drawCitizen(out, world.index(citizen.x, citizen.y), citizen.civId, citizen.survival)
        }
    }
}
