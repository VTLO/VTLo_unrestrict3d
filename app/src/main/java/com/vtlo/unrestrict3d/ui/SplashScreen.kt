package com.vtlo.unrestrict3d.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vtlo.unrestrict3d.R
import kotlinx.coroutines.delay

/** VTLO logo with a short glitch-flicker, then hands off to the chat screen. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // flicker in
        for (a in listOf(0.9f, 0.1f, 1f, 0.35f, 1f)) {
            alpha.snapTo(a)
            delay(70)
        }
        delay(700)
        alpha.animateTo(0f, androidx.compose.animation.core.tween(350))
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Vtlo.Void), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.vtlo_logo),
            contentDescription = "VTLO",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().alpha(alpha.value),
        )
        Text(
            "unrestrict3d",
            color = Vtlo.Olive,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).alpha(alpha.value),
        )
    }
}
