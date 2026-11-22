package dev.kaff.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The phone app's dark tokens. */
object K {
    val bg = Color(0xFF07100C)
    val deep = Color(0xFF050A08)
    val glow = Color(0xFF0E3321)
    val surface = Color(0xFF101B16)
    val surface2 = Color(0xFF16251E)
    val border = Color.White.copy(alpha = 0.08f)
    val primary = Color(0xFF2BE38B)
    val onPrimary = Color(0xFF07100C)
    val soft = Color(0xFF7FF0B8)
    val text = Color(0xFFF2FAF5)
    val muted = Color(0xFF9DB8AA)
    val subtle = Color(0xFFCFE3D8)
    val danger = Color(0xFFFF6B6B)
}

val Tajawal = FontFamily(
    Font("font/tajawal_regular.ttf", FontWeight.Normal),
    Font("font/tajawal_medium.ttf", FontWeight.Medium),
    Font("font/tajawal_bold.ttf", FontWeight.Bold),
    Font("font/tajawal_extrabold.ttf", FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, line: Float = 1.45f) =
    TextStyle(fontFamily = Tajawal, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp)

@Composable
fun KaffTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = K.primary, onPrimary = K.onPrimary, background = K.bg, surface = K.surface,
        onBackground = K.text, onSurface = K.text, error = K.danger,
    ),
    typography = Typography(
        displaySmall = style(40, FontWeight.ExtraBold, 1.2f),
        headlineMedium = style(28, FontWeight.ExtraBold, 1.25f),
        headlineSmall = style(22, FontWeight.Bold),
        titleLarge = style(19, FontWeight.Bold),
        titleMedium = style(16, FontWeight.Bold),
        bodyLarge = style(16, FontWeight.Normal),
        bodyMedium = style(14, FontWeight.Normal),
        bodySmall = style(13, FontWeight.Normal),
        labelLarge = style(15, FontWeight.Bold),
        labelMedium = style(13, FontWeight.Medium),
    ),
    content = content,
)

/** Line icons from the phone app (24 × 24, round caps), tinted by Icon. */
object KIcons {
    private fun icon(name: String, stroke: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
        }.build()

    val Hand = icon("Hand", 2.2f, "M8 13V5.5a1.5 1.5 0 0 1 3 0V11M11 10V4.5a1.5 1.5 0 0 1 3 0V11M14 10V6a1.5 1.5 0 0 1 3 0v8a6 6 0 0 1-6 6h-1a6 6 0 0 1-5-2.7L3.5 14a1.5 1.5 0 0 1 2.5-1.6L8 15")
    val Phone = icon("Phone", 1.8f, "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1-2.5 2.5h-5a2.5 2.5 0 0 1-2.5-2.5V5a2.5 2.5 0 0 1 2.5-2.5z", "M11 18.5h2")
    val Wifi = icon("Wifi", 1.8f, "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0", "M12 19.5h0")
    val Bolt = icon("Bolt", 2.2f, "M13 2L4 14h7l-1 8 9-12h-7z")
    val Upload = icon("Upload", 2f, "M12 16V5M7 10l5-5 5 5", "M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3")
    val Download = icon("Download", 2f, "M12 4v11M7 10l5 5 5-5M5 20h14")
    val Folder = icon("Folder", 1.8f, "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Copy = icon("Copy", 1.8f, "M10.5 8h7a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 8 17.5v-7A2.5 2.5 0 0 1 10.5 8z", "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2")
    val Text = icon("Text", 1.8f, "M5 6h14M5 11h14M5 16h9")
    val Lock = icon("Lock", 1.8f, "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val File = icon("File", 1.8f, "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")
    val Check = icon("Check", 2.6f, "M5 12.5l4.5 4.5L19 7.5")
    val Close = icon("Close", 2f, "M6 6l12 12M18 6L6 18")
}
