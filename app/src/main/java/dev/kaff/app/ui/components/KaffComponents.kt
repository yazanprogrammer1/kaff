package dev.kaff.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme

enum class BackgroundStyle { Glow, Camera }

/** Full-screen background: a soft green radial glow (hero screens) or a dark camera gradient. */
@Composable
fun KaffBackground(
    modifier: Modifier = Modifier,
    style: BackgroundStyle = BackgroundStyle.Glow,
    glowCenterY: Float = 0.28f,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = KaffTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(c.background)
            .drawBehind {
                when (style) {
                    BackgroundStyle.Glow -> drawRect(
                        Brush.radialGradient(
                            colors = listOf(c.glow, c.background),
                            center = Offset(size.width / 2f, size.height * glowCenterY),
                            radius = size.maxDimension * 0.62f,
                        ),
                    )
                    BackgroundStyle.Camera -> drawRect(
                        Brush.verticalGradient(
                            0f to c.backgroundDeep,
                            0.55f to c.glow.copy(alpha = if (c.isDark) 0.55f else 0.6f),
                            1f to c.background,
                        ),
                    )
                }
            },
        content = content,
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    height: Dp = 58.dp,
) {
    val c = KaffTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .shadow(if (enabled) 18.dp else 0.dp, KaffShapes.large, ambientColor = c.primary, spotColor = c.primary),
        shape = KaffShapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary,
            contentColor = c.onPrimary,
            disabledContainerColor = c.surface2,
            disabledContentColor = c.textMuted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        if (trailingIcon != null) {
            Spacer(Modifier.width(10.dp))
            Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
    enabled: Boolean = true,
    height: Dp = 54.dp,
) {
    val c = KaffTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = KaffShapes.large,
        border = BorderStroke(
            1.dp,
            if (accent) c.primary.copy(alpha = 0.4f) else c.text.copy(alpha = 0.14f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (accent) c.primarySoft else c.textSubtle,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** 48 dp square icon button with the translucent glass look from the design. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KaffTheme.colors
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(KaffShapes.medium)
            .background(c.text.copy(alpha = 0.05f))
            .border(1.dp, c.text.copy(alpha = 0.1f), KaffShapes.medium),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = c.text, modifier = Modifier.size(22.dp))
    }
}

enum class PillTone { Accent, Neutral, Danger }

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Accent,
    icon: ImageVector? = null,
    dot: Boolean = icon == null,
    glowingDot: Boolean = false,
) {
    val c = KaffTheme.colors
    val (bg, border, fg, accent) = when (tone) {
        PillTone.Accent -> listOf(c.primary.copy(alpha = 0.12f), c.primary.copy(alpha = 0.3f), c.primarySoft, c.primary)
        PillTone.Neutral -> listOf(c.text.copy(alpha = 0.06f), c.text.copy(alpha = 0.14f), c.textSubtle, c.textSubtle)
        PillTone.Danger -> listOf(c.danger.copy(alpha = 0.12f), c.danger.copy(alpha = 0.35f), c.danger, c.danger)
    }
    Row(
        modifier
            .clip(KaffShapes.pill)
            .background(bg)
            .border(1.dp, border, KaffShapes.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        if (dot) {
            Box(
                Modifier
                    .size(8.dp)
                    .then(if (glowingDot) Modifier.shadow(6.dp, CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
                    .background(accent, CircleShape),
            )
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

/** Rounded surface card with the 8% hairline border. */
@Composable
fun KaffCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = KaffTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(KaffShapes.card)
            .background(c.surface)
            .border(1.dp, if (highlighted) c.primary.copy(alpha = 0.35f) else c.border, KaffShapes.card)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Tinted square that holds an icon, used at the start of list rows. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = KaffTheme.colors.primary) {
    Box(
        modifier
            .size(size)
            .clip(KaffShapes.small)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Centered headline + body block used by hero sections. */
@Composable
fun HeroText(title: String, body: String?, modifier: Modifier = Modifier) {
    val c = KaffTheme.colors
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = c.text,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = c.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
