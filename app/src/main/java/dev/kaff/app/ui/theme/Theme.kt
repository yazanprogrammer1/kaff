package dev.kaff.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** True when the user disabled animations system-wide; motion falls back to simple fades. */
val LocalReduceMotion = staticCompositionLocalOf { false }

object KaffShapes {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(18.dp)
    val card = RoundedCornerShape(20.dp)
    val hero = RoundedCornerShape(22.dp)
    val pill = RoundedCornerShape(percent = 50)
}

object KaffTheme {
    val colors: KaffColors
        @Composable get() = LocalKaffColors.current
}

@Composable
fun KaffTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkKaffColors else LightKaffColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            secondary = colors.primarySoft,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textMuted,
            outline = colors.border,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            secondary = colors.primarySoft,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textMuted,
            outline = colors.border,
            error = colors.danger,
        )
    }
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(
        LocalKaffColors provides colors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KaffTypography,
            shapes = Shapes(
                small = KaffShapes.small,
                medium = KaffShapes.medium,
                large = KaffShapes.large,
            ),
            content = content,
        )
    }
}
