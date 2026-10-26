package dev.kaff.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = KaffTheme.colors.text,
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
