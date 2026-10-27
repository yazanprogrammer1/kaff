package dev.kaff.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaff.app.ui.theme.KaffTheme

/** Glass navigation button at the start, optional title, and trailing [actions]. */
@Composable
fun KaffTopBar(
    navigationIcon: ImageVector,
    navigationDescription: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIconButton(navigationIcon, navigationDescription, onNavigate)
        if (title != null) {
            // One line that shrinks before it wraps, so trailing actions never push it to two lines.
            BasicText(
                title,
                style = MaterialTheme.typography.titleLarge.copy(color = KaffTheme.colors.text),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
        } else {
            Row(Modifier.weight(1f)) {}
        }
        actions()
    }
}
