package com.pixeltown.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pixeltown.sim.GameConfig

/**
 * The one and only rendering surface for the simulation.
 *
 * Pixels are written into an [IntArray], pushed to a [Bitmap] with `setPixels`, and drawn once
 * per frame with [FilterQuality.None] so the upscale stays crisp nearest-neighbour. Citizens are
 * never Compose shapes — there will be thousands of them.
 */
class PixelFramebuffer(
    val width: Int = GameConfig.World.WIDTH,
    val height: Int = GameConfig.World.HEIGHT,
) {
    /** ARGB pixels, row-major. Written by the renderer, read only by [toImageBitmap]. */
    val pixels = IntArray(width * height)

    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun fill(argb: Int) {
        pixels.fill(argb)
    }

    operator fun set(x: Int, y: Int, argb: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = argb
    }

    /** Uploads the current pixel buffer and returns it as a Compose image. */
    fun toImageBitmap(): ImageBitmap {
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }
}

/**
 * Draws [framebuffer] scaled to fit the available space at an integer-friendly scale, letterboxed
 * and centred so the world never stretches.
 */
@Composable
fun PixelCanvas(
    framebuffer: PixelFramebuffer,
    frameKey: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // frameKey is read so that a new tick batch invalidates the draw.
        @Suppress("UNUSED_EXPRESSION") frameKey
        drawScaledToFit(framebuffer.toImageBitmap(), framebuffer.width, framebuffer.height)
    }
}

private fun DrawScope.drawScaledToFit(image: ImageBitmap, srcWidth: Int, srcHeight: Int) {
    val scale = minOf(size.width / srcWidth, size.height / srcHeight)
    val drawWidth = (srcWidth * scale).toInt()
    val drawHeight = (srcHeight * scale).toInt()
    val left = ((size.width - drawWidth) / 2f).toInt()
    val top = ((size.height - drawHeight) / 2f).toInt()
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(drawWidth, drawHeight),
        filterQuality = FilterQuality.None,
    )
}
