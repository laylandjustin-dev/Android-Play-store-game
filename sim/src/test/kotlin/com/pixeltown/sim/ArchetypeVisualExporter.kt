package com.pixeltown.sim

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the archetype markers and the per-citizen hue spread to PNGs, so a visual change can be
 * looked at rather than argued about. Test source set for the same reason as `MapPreviewExporter`:
 * `javax.imageio` must never reach Android.
 */
class ArchetypeVisualExporter {

    @Test
    fun `export the archetype markers and the hue spread`() {
        val out = File("build/preview").apply { mkdirs() }

        // One tile per archetype, each marker drawn on an empty world in the player's gold.
        val tile = 32
        val shapes = Archetype.entries
        val sheet = BufferedImage(tile * shapes.size * SCALE, (tile + 10) * SCALE, BufferedImage.TYPE_INT_RGB)
        val world = World(tile, tile + 10)
        val pixels = IntArray(world.cellCount)

        for ((index, archetype) in shapes.withIndex()) {
            pixels.fill(0xFF0B0B0D.toInt())
            val centre = world.index(tile / 2, tile / 2)
            WorldRenderer.drawHomeMarker(
                world, pixels, centre, GameConfig.World.PLAYER_CIV_ID,
                radius = 9, shape = archetype.shape,
            )
            // The home cell must stay the civ's own colour, never painted over by its marker.
            pixels[centre] = Palette.PLAYER_CHOICES[0]

            for (y in 0 until world.height) {
                for (x in 0 until world.width) {
                    val argb = pixels[world.index(x, y)]
                    for (sy in 0 until SCALE) {
                        for (sx in 0 until SCALE) {
                            sheet.setRGB((index * tile + x) * SCALE + sx, y * SCALE + sy, argb)
                        }
                    }
                }
            }
        }
        val markers = File(out, "archetype-markers.png")
        ImageIO.write(sheet, "png", markers)
        assertTrue(markers.length() > 0)

        // The hue spread: one row per player colour, one column per citizen id.
        val ids = 96
        val rows = Palette.PLAYER_CHOICES.size
        val swatch = BufferedImage(ids * SCALE, rows * 12 * SCALE, BufferedImage.TYPE_INT_RGB)
        val strip = World(ids, rows * 12)
        val stripPixels = IntArray(strip.cellCount)
        for (row in 0 until rows) {
            val colors = CivColors.forPlayerChoice(row)
            for (id in 0 until ids) {
                for (band in 0 until 12) {
                    val index = strip.index(id, row * 12 + band)
                    WorldRenderer.drawCitizen(
                        stripPixels, index, GameConfig.World.PLAYER_CIV_ID,
                        survival = GameConfig.Survival.MAX.toFloat(),
                        isPlayer = true, colors = colors, citizenId = id,
                    )
                }
            }
        }
        for (y in 0 until strip.height) {
            for (x in 0 until strip.width) {
                val argb = stripPixels[strip.index(x, y)]
                for (sy in 0 until SCALE) {
                    for (sx in 0 until SCALE) {
                        swatch.setRGB(x * SCALE + sx, y * SCALE + sy, argb)
                    }
                }
            }
        }
        val hues = File(out, "citizen-hue-spread.png")
        ImageIO.write(swatch, "png", hues)
        assertTrue(hues.length() > 0)
    }

    @Test
    fun `export the building silhouettes at both footprints`() {
        val out = File("build/preview").apply { mkdirs() }
        val cats = BuildingCategory.entries
        val cell = 14
        val world = World(cell * cats.size, cell * 2)
        val pixels = IntArray(world.cellCount) { 0xFF0B0B0D.toInt() }

        for ((i, category) in cats.withIndex()) {
            // Top row: a tier 2+ building at footprint 3. Bottom row: a tier 0 at footprint 2.
            WorldRenderer.drawBuilding(world, pixels, i * cell + 5, 5, 3, category)
            WorldRenderer.drawBuilding(world, pixels, i * cell + 5, cell + 6, 2, category)
        }

        val scale = 10
        val image = BufferedImage(world.width * scale, world.height * scale, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until world.height * scale) {
            for (x in 0 until world.width * scale) {
                image.setRGB(x, y, pixels[(y / scale) * world.width + (x / scale)])
            }
        }
        val file = File(out, "building-shapes.png")
        ImageIO.write(image, "png", file)
        assertTrue(file.length() > 0)
    }

    private companion object {
        const val SCALE = 4
    }
}
