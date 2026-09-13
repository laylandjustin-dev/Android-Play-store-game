package com.pixeltown.sim

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Writes generated maps to PNGs under `sim/build/preview/` so the renderer can be inspected
 * without a device.
 *
 * Deliberately in the test source set: `javax.imageio` and `java.awt` do not exist on Android, so
 * this must never be dexed into the app. It runs as part of the normal test task — it is cheap,
 * and a broken renderer should fail the build rather than wait to be noticed on screen.
 */
class MapPreviewExporter {

    @Test
    fun `export map previews`() {
        val outputDir = File("build/preview").apply { mkdirs() }
        val scale = 4

        for (seed in longArrayOf(1L, 42L, 20260913L)) {
            val generated = WorldGenerator.generate(SimRandom(seed))
            val world = generated.world
            val pixels = IntArray(world.cellCount)
            WorldRenderer.renderTerrain(world, pixels)

            // Mark the five starting sites so site selection is visible too.
            for ((civId, site) in generated.civStartSites.withIndex()) {
                WorldRenderer.drawCitizen(pixels, site, civId, survival = 100f)
            }

            val image = BufferedImage(world.width * scale, world.height * scale, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until world.height * scale) {
                for (x in 0 until world.width * scale) {
                    image.setRGB(x, y, pixels[(y / scale) * world.width + (x / scale)])
                }
            }
            val file = File(outputDir, "map-seed-$seed.png")
            ImageIO.write(image, "png", file)
            assertTrue(file.length() > 0, "failed to write $file")
        }
    }
}
