package com.vtlo.unrestrict3d

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.vtlo.unrestrict3d.R
import kotlinx.coroutines.delay

private val BANNERS = listOf(
    R.drawable.banner_triptych,
    R.drawable.banner_robots,
    R.drawable.banner_heatsink,
    R.drawable.banner_glitch,
)
private val VBIAS = floatArrayOf(0f, 0.15f, -0.1f, 0f)

/**
 * Top banner: the four reference pictures cross-fade every few seconds while a slow pan/zoom
 * (Ken Burns), CRT scan-lines and a sweeping light line run over them. A scrim keeps the header
 * text readable and fades the banner into the screen background.
 */
@Composable
fun AnimatedBanner(modifier: Modifier = Modifier, scrimTo: Color = Color(0xFF111112)) {
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(4800); idx = (idx + 1) % BANNERS.size }
    }
    val t = rememberInfiniteTransition(label = "banner")
    val pan by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "pan")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")

    Box(modifier) {
        Crossfade(targetState = idx, animationSpec = tween(1000), label = "xfade") { i ->
            Image(
                painter = painterResource(BANNERS[i]),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment((pan * 2f - 1f) * 0.7f, VBIAS[i]),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val z = 1.12f + 0.08f * pan
                    scaleX = z; scaleY = z
                },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            var y = 0f
            while (y < size.height) { // scan-lines
                drawLine(Color(0x22000000), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                y += 5f
            }
            val sy = size.height * sweep // light sweep
            drawLine(Color(0x33FFFFFF), Offset(0f, sy), Offset(size.width, sy), strokeWidth = 2f)
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0.0f to Color(0x99000000),
                        0.55f to Color(0x55000000),
                        1.0f to scrimTo,
                    )
                )
            }
        )
    }
}
