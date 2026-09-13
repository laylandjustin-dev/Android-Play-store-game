package com.pixeltown.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.pixeltown.sim.GameConfig
import com.pixeltown.sim.SimClock

/**
 * M0 skeleton: a black 128x128 world canvas scaled to fit, driven by a [SimClock] running at
 * 10 ticks/sec. The tick loop is frame-driven via [withFrameNanos] so the simulation rate is
 * decoupled from the render rate from the very first commit.
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
    var frameKey by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        framebuffer.fill(COLOR_VOID)
        var lastNanos = withFrameNanos { it }
        var lastLoggedTick = 0L
        while (true) {
            val nowNanos = withFrameNanos { it }
            val deltaSeconds = (nowNanos - lastNanos) / 1_000_000_000.0
            lastNanos = nowNanos

            val ticksRun = clock.advance(deltaSeconds) { /* no systems yet — M2 adds citizens */ }
            if (ticksRun > 0) {
                frameKey = clock.tick
                if (clock.tick - lastLoggedTick >= GameConfig.Time.BASE_TICKS_PER_SECOND) {
                    lastLoggedTick = clock.tick
                    Log.d(TAG, "tick=${clock.tick} year=${clock.year} season=${clock.season}")
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
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            PixelCanvas(
                framebuffer = framebuffer,
                frameKey = frameKey,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = "Year ${frameKey / GameConfig.Time.DAYS_PER_YEAR} · day ${frameKey % GameConfig.Time.DAYS_PER_YEAR} · tick $frameKey",
            color = Color(0xFFBFAE7A),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private const val TAG = "PixelTown"

/** Dark UI chrome so the pixel map is always the brightest thing on screen. */
private val COLOR_CHROME = Color(0xFF0B0B0D)
private const val COLOR_VOID = 0xFF000000.toInt()
