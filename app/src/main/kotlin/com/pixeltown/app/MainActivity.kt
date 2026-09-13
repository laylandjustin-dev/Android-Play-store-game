package com.pixeltown.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.SimClock
import com.pixeltown.sim.SimRandom
import com.pixeltown.sim.Viewport
import com.pixeltown.sim.WorldGenerator
import com.pixeltown.sim.WorldRenderer

/**
 * M1: a generated island, rendered as pixels, with pinch zoom (1x-8x) and pan.
 *
 * There are no citizens yet, so terrain is painted once into the framebuffer and the tick loop
 * only advances the calendar. From M2 the dynamic layers are repainted on top of a cached copy of
 * the terrain buffer each frame.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixelTownRoot() }
    }
}

@Composable
private fun PixelTownRoot() {
    val clock = remember { SimClock(speedMultiplier = 1) }
    val framebuffer = remember { PixelFramebuffer() }

    // A fixed seed until the allocation screen (M3) hands one over with the player's traits.
    val generated = remember { WorldGenerator.generate(SimRandom(PLACEHOLDER_SEED)) }

    var viewport by remember { mutableStateOf(Viewport.whole()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var frameKey by remember { mutableLongStateOf(0L) }

    LaunchedEffect(generated) {
        WorldRenderer.renderTerrain(generated.world, framebuffer.pixels)
        frameKey = -1L // force a first draw before any tick runs
    }

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        var lastLoggedTick = 0L
        while (true) {
            val nowNanos = withFrameNanos { it }
            val deltaSeconds = (nowNanos - lastNanos) / 1_000_000_000.0
            lastNanos = nowNanos

            val ticksRun = clock.advance(deltaSeconds) { /* systems arrive in M2 */ }
            if (ticksRun > 0) {
                frameKey = clock.tick
                if (clock.tick - lastLoggedTick >= GameConfig.Time.DAYS_PER_YEAR) {
                    lastLoggedTick = clock.tick
                    Log.d(TAG, "year=${clock.year} season=${clock.season} tick=${clock.tick}")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(COLOR_CHROME),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { canvasSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
                .worldGestures(
                    viewport = { viewport },
                    canvasSize = { canvasSize },
                    onViewportChange = { viewport = it },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PixelCanvas(
                framebuffer = framebuffer,
                viewport = viewport,
                frameKey = frameKey,
                modifier = Modifier.fillMaxSize(),
            )
        }
        WorldHud(clock = clock, viewport = viewport, tick = frameKey)
    }
}

/** The bottom strip: date, zoom, speed. Grows into the real HUD at M3. */
@Composable
private fun WorldHud(clock: SimClock, viewport: Viewport, tick: Long) {
    val year = (if (tick < 0) 0 else tick) / GameConfig.Time.DAYS_PER_YEAR
    val day = (if (tick < 0) 0 else tick) % GameConfig.Time.DAYS_PER_YEAR
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HudText("Year $year · day $day · ${clock.season.name.lowercase()}")
        HudText("${clock.speedMultiplier}x · zoom ${"%.1f".format(viewport.zoom)}x")
    }
}

@Composable
private fun HudText(text: String) {
    Text(
        text = text,
        color = COLOR_HUD_TEXT,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}

private const val TAG = "PixelTown"

/** Replaced at M3 by the seed chosen when a run starts. */
private const val PLACEHOLDER_SEED = 20260913L

/** Dark UI chrome so the pixel map is always the brightest thing on screen. */
private val COLOR_CHROME = Color(0xFF0B0B0D)
private val COLOR_HUD_TEXT = Color(0xFFBFAE7A)
