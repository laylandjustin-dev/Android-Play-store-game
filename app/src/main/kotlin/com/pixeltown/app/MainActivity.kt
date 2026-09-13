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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixeltown.sim.FrameRenderer
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.Simulation
import com.pixeltown.sim.TraitAllocation
import com.pixeltown.sim.Viewport

/**
 * M2: a generated island populated by five colonies of fifty, living and dying.
 *
 * The frame loop drains whole simulation ticks through [Simulation.step] and repaints once per
 * frame, after the batch — so at 10x the renderer does the same work per frame as at 1x, and
 * simulation logic is never skipped or approximated to keep up.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixelTownRoot() }
    }
}

@Composable
private fun PixelTownRoot() {
    val framebuffer = remember { PixelFramebuffer() }

    // A fixed seed and an even allocation until the allocation screen (M3) provides both.
    val simulation = remember { Simulation.newRun(PLACEHOLDER_SEED, TraitAllocation.EVEN_SPREAD) }
    val clock = simulation.clock
    val frameRenderer = remember(simulation) { FrameRenderer(simulation.world) }

    var viewport by remember { mutableStateOf(Viewport.whole()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var frameKey by remember { mutableLongStateOf(0L) }

    LaunchedEffect(simulation) {
        frameRenderer.render(simulation, framebuffer.pixels)
        frameKey = -1L // force a first draw before any tick runs
    }

    LaunchedEffect(simulation) {
        var lastNanos = withFrameNanos { it }
        var lastLoggedYear = -1
        while (true) {
            val nowNanos = withFrameNanos { it }
            val deltaSeconds = (nowNanos - lastNanos) / 1_000_000_000.0
            lastNanos = nowNanos

            // The clock decides how many days to run; the simulation runs every one of them.
            val ticksRun = clock.pendingTicks(deltaSeconds)
            repeat(ticksRun) { if (simulation.endState == null) simulation.step() }

            if (ticksRun > 0) {
                // Repaint once per frame, after the whole batch — never per tick.
                frameRenderer.render(simulation, framebuffer.pixels)
                frameKey = clock.tick
                if (clock.year != lastLoggedYear) {
                    lastLoggedYear = clock.year
                    Log.d(TAG, "year=${clock.year} pop=${simulation.populationOf(0)} end=${simulation.endState}")
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
        WorldHud(simulation = simulation, viewport = viewport, tick = frameKey)
    }
}

/** The bottom strip: date, population, zoom, speed. Grows into the real HUD at M4. */
@Composable
private fun WorldHud(simulation: Simulation, viewport: Viewport, tick: Long) {
    val clock = simulation.clock
    val day = (if (tick < 0) 0 else tick) % GameConfig.Time.DAYS_PER_YEAR
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HudText("Year ${clock.year} · day $day · ${clock.season.name.lowercase()}")
        HudText("pop ${simulation.populationOf(GameConfig.World.PLAYER_CIV_ID)}")
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
