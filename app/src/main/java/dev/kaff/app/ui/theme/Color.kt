package dev.kaff.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Kaff design tokens. Dark-first; [LightKaffColors] is the white/green variant. */
@Immutable
data class KaffColors(
    val background: Color,
    val backgroundDeep: Color,
    val glow: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val text: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val danger: Color,
    val boneColor: Color,
    val tipColor: Color,
    val isDark: Boolean,
)

val DarkKaffColors = KaffColors(
    background = Color(0xFF07100C),
    backgroundDeep = Color(0xFF050A08),
    glow = Color(0xFF0E3321),
    surface = Color(0xFF101B16),
    surface2 = Color(0xFF16251E),
    border = Color.White.copy(alpha = 0.08f),
    primary = Color(0xFF2BE38B),
    onPrimary = Color(0xFF07100C),
    primarySoft = Color(0xFF7FF0B8),
    text = Color(0xFFF2FAF5),
    textMuted = Color(0xFF9DB8AA),
    textSubtle = Color(0xFFCFE3D8),
    danger = Color(0xFFFF6B6B),
    boneColor = Color(0xFF2BE38B),
    tipColor = Color.White,
    isDark = true,
)

val LightKaffColors = KaffColors(
    background = Color(0xFFF6FBF8),
    backgroundDeep = Color(0xFFFFFFFF),
    glow = Color(0xFFD5F7E6),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEDF6F1),
    border = Color.Black.copy(alpha = 0.08f),
    primary = Color(0xFF1FCB7B),
    onPrimary = Color(0xFF07100C),
    primarySoft = Color(0xFF0B7A48),
    text = Color(0xFF07100C),
    textMuted = Color(0xFF4E6A5C),
    textSubtle = Color(0xFF29453A),
    danger = Color(0xFFC62828),
    boneColor = Color(0xFF14A866),
    tipColor = Color(0xFF07100C),
    isDark = false,
)

val LocalKaffColors = staticCompositionLocalOf { DarkKaffColors }
