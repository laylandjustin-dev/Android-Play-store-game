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
     * [strength] 0 leaves the terrain alone, 1 replaces it with the civ colour. The player's own
     * ground is tinted harder, so the shape of what is *yours* is readable at a glance.
     */
    fun tintOwnership(
        world: World,
        out: IntArray,
        strength: Float,
        playerCivId: Int = -1,
        colors: CivColors = CivColors.DEFAULT,
    ) {
        if (strength <= 0f) return
        val playerStrength = (strength * GameConfig.Render.PLAYER_TERRITORY_TINT_SCALE).coerceAtMost(1f)
        for (i in 0 until world.cellCount) {
            val civ = world.ownerCivId[i].toInt()
            if (civ < 0) continue
            val amount = if (civ == playerCivId) playerStrength else strength
            out[i] = blend(out[i], colors[civ], amount)
        }
    }

    /**
     * Draws one citizen pixel: the civ colour, dimmed by survival score.
     *
     * [isPlayer] holds the player's own people to a higher brightness floor, and [focus] pushes
     * everyone else further back still.
     */
    fun drawCitizen(
        out: IntArray,
        index: Int,
        civId: Int,
        survival: Float,
        isPlayer: Boolean = false,
        focus: Boolean = false,
        colors: CivColors = CivColors.DEFAULT,
        /** The citizen's id, which gives them their own stable shade of the civ colour. */
        citizenId: Int = 0,
        /** What they are doing, which is most of what their shade says. */
        job: Job = Job.IDLE,
    ) {
        val render = GameConfig.Render
        val t = (survival / GameConfig.Survival.MAX).toFloat().coerceIn(0f, 1f)
        val floor = if (isPlayer) render.PLAYER_MIN_BRIGHTNESS else render.CITIZEN_MIN_BRIGHTNESS
        var brightness = floor + (render.CITIZEN_MAX_BRIGHTNESS - floor) * t
        if (!isPlayer) {
            brightness *= if (focus) render.FOCUS_RIVAL_BRIGHTNESS_SCALE else render.RIVAL_BRIGHTNESS_SCALE
        }
        // Shade says what they are doing. A citizen is one pixel, so their job has nowhere else to
        // go, and a town where half the dots are farm-coloured is a town you can read at a glance.
        // The per-citizen jitter on top is what stops two farmers being literally the same pixel;
        // the id is run through an integer hash first, because a plain multiply left a visible
        // period in the pattern and siblings born in the same week landed on neighbouring shades.
        val jitter = (hash(citizenId) and 0xFF) / 255f * 2f - 1f
        val hue = render.JOB_HUE_DEGREES[job.ordinal] + jitter * render.CITIZEN_HUE_SPREAD_DEGREES
        val dim = when (job) {
            Job.CHILD -> render.CHILD_BRIGHTNESS_SCALE
            Job.IDLE -> render.IDLE_BRIGHTNESS_SCALE
            else -> 1f
        }
        out[index] = Palette.scaleBrightness(Palette.shiftHue(colors[civId], hue), brightness * dim)
    }

    /** A cheap integer avalanche hash, so consecutive citizen ids give unrelated shades. */
    private fun hash(value: Int): Int {
        var h = value * -2048144789 // 0x85EBCA6B as a signed Int
        h = h xor (h ushr 13)
        h *= -1028477387 // 0xC2B2AE35
        return h xor (h ushr 16)
    }

    /**
     * A ring around a civ's founding site, drawn under the citizens.
     *
     * Fifty gold pixels on a 128x128 island are easy to lose, especially once a town has spread
     * out or been pushed back. The ring says "this is where you are" without covering anybody up.
     */
    fun drawHomeMarker(
        world: World,
        out: IntArray,
        cell: Int,
        civId: Int,
        radius: Int = GameConfig.Render.HOME_MARKER_RADIUS,
        colors: CivColors = CivColors.DEFAULT,
        /** The glyph, which says what kind of people live here. See [Archetype]. */
        shape: MarkerShape = MarkerShape.RING,
    ) {
        val cx = cell % world.width
        val cy = cell / world.width
        val colour = Palette.scaleBrightness(colors[civId], 0.85f)

        // The shape owns its geometry and this owns the clipping, so a new glyph cannot introduce
        // an out-of-bounds write. No offset is ever the centre, so the home cell stays visible.
        for ((dx, dy) in shape.offsets(radius)) {
            plot(world, out, cx + dx, cy + dy, colour)
        }
    }

    private fun plot(world: World, out: IntArray, x: Int, y: Int, colour: Int) {
        if (world.inBounds(x, y)) out[world.index(x, y)] = colour
    }

    /** Draws a building as a solid block of its category colour, clipped to the world. */
    /**
     * Draws a building as its category's silhouette (see [BuildingShape]).
     *
     * The whole footprint is painted either way — a building is a solid object on the map — with
     * the figure in the category's accent colour and the rest of the footprint in a darker tone of
     * it. [complete] is false for a half-built structure, which is drawn dimmer still: an unfinished
     * building does nothing for the town and should not look as though it does.
     */
    fun drawBuilding(
        world: World,
        out: IntArray,
        x: Int,
        y: Int,
        footprint: Int,
        category: BuildingCategory,
        complete: Boolean = true,
    ) {
        val accent = Palette.BUILDING[category.ordinal]
        val figure = if (complete) accent else Palette.scaleBrightness(accent, 0.5f)
        val ground = Palette.scaleBrightness(accent, if (complete) 0.42f else 0.24f)
        val mask = BuildingShape.of(category).mask(footprint)

        for (dy in 0 until footprint) {
            for (dx in 0 until footprint) {
                val px = x + dx
                val py = y + dy
                if (!world.inBounds(px, py)) continue
                out[world.index(px, py)] = if (mask[dy * footprint + dx]) figure else ground
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
    /**
     * [focusPlayer] pushes every other civilisation into the background — the quickest way to find
     * your own people on a small screen.
     */
    fun render(
        simulation: Simulation,
        out: IntArray,
        ownershipTint: Float = 0.12f,
        focusPlayer: Boolean = false,
    ) {
        require(out.size >= world.cellCount) {
            "pixel buffer holds ${out.size} pixels, world needs ${world.cellCount}"
        }
        if (!terrainPainted) {
            WorldRenderer.renderTerrain(world, terrainCache)
            terrainPainted = true
        }
        terrainCache.copyInto(out, 0, 0, world.cellCount)

        val playerCivId = GameConfig.World.PLAYER_CIV_ID
        val colors = simulation.colors
        WorldRenderer.tintOwnership(world, out, ownershipTint, playerCivId, colors)

        // Every civ is marked with the glyph of its archetype, so what kind of people live in a
        // town is legible from the map rather than only from a panel. The player's is drawn last so
        // a neighbour's marker can never overlap theirs.
        for (civ in simulation.civs.sortedBy { it.isPlayer }) {
            if (civ.population == 0) continue
            WorldRenderer.drawHomeMarker(
                world, out, civ.homeSite, civ.id,
                colors = colors,
                shape = Archetype.of(civ.traits).shape,
            )
        }

        // Buildings, which until now were drawn by nothing: `drawBuilding` existed and was unit
        // tested, and no frame ever called it, so a town of sixty structures was invisible on its
        // own map. Drawn before the citizens so people walk over their own buildings.
        for (civ in simulation.civs) {
            for (building in simulation.buildingsOf(civ.id)) {
                WorldRenderer.drawBuilding(
                    world, out, building.x, building.y, building.spec.footprint,
                    building.spec.category, complete = building.isComplete,
                )
            }
        }

        for (citizen in simulation.citizens) {
            WorldRenderer.drawCitizen(
                out, world.index(citizen.x, citizen.y), citizen.civId, citizen.survival,
                isPlayer = citizen.civId == playerCivId,
                focus = focusPlayer,
                colors = colors,
                citizenId = citizen.id,
                job = citizen.job,
            )
        }
    }
}
