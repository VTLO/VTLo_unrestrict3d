package com.vtlo.unrestrict3d.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette lifted from the two reference images: studio greys + the VTLO olive-green glitch. */
object Vtlo {
    val Void = Color(0xFF050506)
    val Chat = Color(0xFF111112)       // plain darkest grey used once a chat is open
    val Ink = Color(0xFF161718)
    val Steel = Color(0xFF7A7B7D)
    val Fog = Color(0xFFB9BABB)
    val Bone = Color(0xFFE6E7E3)
    val TextHi = Color(0xFFF6F6F4)     // main text: contrast > 15:1 on Chat
    val TextMid = Color(0xFFD2D4CE)    // secondary text: contrast > 10:1 on Chat
    val Olive = Color(0xFFB2C99C)      // brighter than before for legibility
    val OliveDim = Color(0xFF5B6A4F)
    val Panel = Color(0xFF1E2020)      // bubbles / inputs on the chat background
    val Glass = Color(0xB3101112)
    val Danger = Color(0xFFFF8A7A)
}

private val Scheme = darkColorScheme(
    primary = Vtlo.Olive,
    onPrimary = Vtlo.Void,
    secondary = Vtlo.Steel,
    background = Vtlo.Chat,
    onBackground = Vtlo.TextHi,
    surface = Vtlo.Ink,
    onSurface = Vtlo.TextHi,
    surfaceVariant = Vtlo.Panel,
    onSurfaceVariant = Vtlo.TextMid,
    outline = Vtlo.OliveDim,
    error = Vtlo.Danger,
)

val Mono = FontFamily.Monospace

private val Type = Typography(
    bodyLarge = TextStyle(fontFamily = Mono, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 15.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 0.6.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = Mono, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
)

@Composable
fun VtloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
