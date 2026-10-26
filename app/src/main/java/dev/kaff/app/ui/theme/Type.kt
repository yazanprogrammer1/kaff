package dev.kaff.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.kaff.app.R

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, lineHeight: Int = (size * 1.4f).toInt()) = TextStyle(
    fontFamily = Tajawal,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

val KaffTypography = Typography(
    displaySmall = style(32, FontWeight.ExtraBold, 40),
    headlineMedium = style(28, FontWeight.ExtraBold, 36),
    headlineSmall = style(22, FontWeight.ExtraBold, 30),
    titleLarge = style(18, FontWeight.ExtraBold, 26),
    titleMedium = style(17, FontWeight.Bold, 24),
    titleSmall = style(16, FontWeight.Bold, 22),
    bodyLarge = style(16, FontWeight.Normal, 26),
    bodyMedium = style(14, FontWeight.Normal, 21),
    bodySmall = style(13, FontWeight.Normal, 19),
    labelLarge = style(17, FontWeight.ExtraBold, 22),
    labelMedium = style(14, FontWeight.Bold, 19),
    labelSmall = style(13, FontWeight.Medium, 17),
)
