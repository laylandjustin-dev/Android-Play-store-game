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

            writePng(File(outputDir, "map-seed-$seed.png"), world, pixels, scale)
        }

        // A founded world, ten days in: five colonies of fifty, each a cluster of civ-coloured
        // pixels. This is what the app draws every frame.
        val sim = Simulation.newRun(20260913L, TraitAllocation.EVEN_SPREAD)
        sim.run(10)
        val frame = IntArray(sim.world.cellCount)
        val renderer = FrameRenderer(sim.world)
        renderer.render(sim, frame)
        writePng(File(outputDir, "colony-day-10.png"), sim.world, frame, scale)

        // A working economy, sixty years on: towns have spread out across their farmland and the
        // territory tint shows which civ works which cells.
        val mature = Simulation.newRun(1L, TraitAllocation.of(3, 4, 3, 4, 8))
        mature.run(60 * GameConfig.Time.DAYS_PER_YEAR)
        val matureFrame = IntArray(mature.world.cellCount)
        FrameRenderer(mature.world).render(mature, matureFrame, ownershipTint = 0.18f)
        writePng(File(outputDir, "colony-year-60.png"), mature.world, matureFrame, scale)
    }

    private fun writePng(file: File, world: World, pixels: IntArray, scale: Int) {
        run {
            val image = BufferedImage(world.width * scale, world.height * scale, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until world.height * scale) {
                for (x in 0 until world.width * scale) {
                    image.setRGB(x, y, pixels[(y / scale) * world.width + (x / scale)])
                }
            }
            ImageIO.write(image, "png", file)
            assertTrue(file.length() > 0, "failed to write $file")
        }
    }
}
