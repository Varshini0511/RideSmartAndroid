package com.ridesmart.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF1A73E8)
val PrimaryDark = Color(0xFF1557B0)
val Secondary = Color(0xFF34A853)
val Accent = Color(0xFFFBBC04)
val Danger = Color(0xFFEA4335)
val Background = Color(0xFFF8F9FA)
val Surface = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF202124)
val TextSecondary = Color(0xFF5F6368)
val TextTertiary = Color(0xFF80868B)
val Border = Color(0xFFDADCE0)

val UberColor = Color(0xFF000000)
val OlaColor = Color(0xFF1C8C37)
val RapidoColor = Color(0xFFFFC107)
val NammaYatriColor = Color(0xFF2D8CFF)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = Background,
    surface = Surface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Danger,
)

@Composable
fun RideSmartTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
