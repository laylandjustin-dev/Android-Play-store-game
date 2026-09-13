package com.pixeltown.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.Viewport

/**
 * The world's pixels, as one reused [Bitmap].
 *
 * The simulation paints into [pixels] (see `WorldRenderer` in `:sim`, which has no Android
 * dependency); this class is the only thing that knows about bitmaps. `setPixels` on a single
 * pre-allocated bitmap avoids an allocation per frame.
 */
class PixelFramebuffer(
    val width: Int = GameConfig.World.WIDTH,
    val height: Int = GameConfig.World.HEIGHT,
) {
    /** ARGB pixels, row-major, one per world cell. */
    val pixels = IntArray(width * height)

    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun fill(argb: Int) = pixels.fill(argb)

    /** Uploads the current pixel buffer and returns it as a Compose image. */
    fun toImageBitmap(): ImageBitmap {
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }
}

/**
 * Draws the part of [framebuffer] described by [viewport], scaled to fill the available space with
 * nearest-neighbour filtering so pixels stay square and crisp.
 *
 * Zoom and pan are done by moving the *source* rectangle rather than by resampling the buffer:
 * one `drawImage` per frame, no per-zoom pixel work, and no filtering artefacts at any zoom.
 *
 * [frameKey] is read on every draw so that a new simulation tick invalidates it.
 */
@Composable
fun PixelCanvas(
    framebuffer: PixelFramebuffer,
    viewport: Viewport,
    frameKey: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        @Suppress("UNUSED_EXPRESSION") frameKey
        drawViewport(framebuffer.toImageBitmap(), viewport)
    }
}

private fun DrawScope.drawViewport(image: ImageBitmap, viewport: Viewport) {
    val srcWidth = viewport.srcWidth
    val srcHeight = viewport.srcHeight
    // Letterbox rather than stretch: one scale factor for both axes.
    val scale = minOf(size.width / srcWidth, size.height / srcHeight)
    val dstWidth = (srcWidth * scale).toInt()
    val dstHeight = (srcHeight * scale).toInt()
    drawImage(
        image = image,
        srcOffset = IntOffset(viewport.srcX, viewport.srcY),
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset(
            ((size.width - dstWidth) / 2f).toInt(),
            ((size.height - dstHeight) / 2f).toInt(),
        ),
        dstSize = IntSize(dstWidth, dstHeight),
        filterQuality = FilterQuality.None,
    )
}
