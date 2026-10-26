package dev.kaff.app.ui.animation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.kaff.app.ui.theme.LocalReduceMotion

/** Provided by the nav host so screens can mark elements that travel between destinations. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks an element shared across screens under [key]. No-op outside the nav host (previews, tests)
 * and when the user prefers reduced motion.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedHero(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    if (LocalReduceMotion.current) return this
    return with(shared) {
        this@sharedHero.sharedBounds(rememberSharedContentState(key), animated)
    }
}
