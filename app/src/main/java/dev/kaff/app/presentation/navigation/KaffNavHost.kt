package dev.kaff.app.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.kaff.app.core.LaunchTarget
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.presentation.done.DoneRoute
import dev.kaff.app.presentation.history.HistoryRoute
import dev.kaff.app.presentation.home.HomeRoute
import dev.kaff.app.presentation.lab.GestureLabRoute
import dev.kaff.app.presentation.permissions.PermissionsRoute
import dev.kaff.app.presentation.picker.PickerRoute
import dev.kaff.app.presentation.receive.ReceiveRoute
import dev.kaff.app.presentation.send.SendRoute
import dev.kaff.app.presentation.settings.SettingsRoute
import dev.kaff.app.ui.animation.LocalNavAnimatedScope
import dev.kaff.app.ui.animation.LocalSharedTransitionScope
import dev.kaff.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination
@Serializable data object PickerDestination
@Serializable data object SendDestination
@Serializable data object ReceiveDestination
@Serializable data object DoneDestination
@Serializable data object HistoryDestination
@Serializable data object LabDestination
@Serializable data object SettingsDestination

/** Explains and requests permissions, then continues to [target]. */
@Serializable data class PermissionsDestination(val target: String)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KaffNavHost(launchTargets: Flow<LaunchTarget>) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current

    /** Transfer screens need camera + Nearby permissions; explain and ask first if missing. */
    fun gated(target: LaunchTarget) {
        val destination: Any = if (KaffPermissions.missingRequired(context).isEmpty()) target.destination() else PermissionsDestination(target.name)
        nav.navigate(destination) { launchSingleTop = true }
    }

    fun open(target: LaunchTarget) {
        when (target) {
            // Sending starts by filling the basket; permissions are asked when it is ready.
            LaunchTarget.Send -> nav.navigate(PickerDestination) {
                popUpTo(HomeDestination)
                launchSingleTop = true
            }
            LaunchTarget.Receive -> {
                nav.popBackStack(HomeDestination, inclusive = false)
                gated(target)
            }
        }
    }

    LaunchedEffect(launchTargets) { launchTargets.collect { open(it) } }

    val slide = 320
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = nav,
                startDestination = HomeDestination,
                enterTransition = {
                    if (reduceMotion) fadeIn() else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(slide)) + fadeIn(tween(slide))
                },
                exitTransition = { fadeOut(tween(slide / 2)) },
                popEnterTransition = { fadeIn(tween(slide)) },
                popExitTransition = {
                    if (reduceMotion) fadeOut() else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(slide)) + fadeOut(tween(slide))
                },
            ) {
                composable<HomeDestination> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        HomeRoute(
                            onSend = { open(LaunchTarget.Send) },
                            onReceive = { open(LaunchTarget.Receive) },
                            onSettings = { nav.navigate(SettingsDestination) },
                            onHistory = { nav.navigate(HistoryDestination) },
                            onLab = { nav.navigate(LabDestination) },
                        )
                    }
                }
                composable<PermissionsDestination> { entry ->
                    val target = LaunchTarget.valueOf(entry.toRoute<PermissionsDestination>().target)
                    PermissionsRoute(
                        onGranted = {
                            nav.navigate(target.destination()) {
                                popUpTo<PermissionsDestination> { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onBack = nav::popBackStack,
                    )
                }
                composable<PickerDestination> {
                    PickerRoute(onBack = { nav.backToHome() }, onReady = { gated(LaunchTarget.Send) })
                }
                composable<SendDestination> {
                    SendRoute(
                        onClose = { nav.backToHome() },
                        onChangeFiles = { if (!nav.popBackStack(PickerDestination, inclusive = false)) open(LaunchTarget.Send) },
                        onNewTransfer = { open(LaunchTarget.Send) },
                    )
                }
                composable<ReceiveDestination> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        ReceiveRoute(
                            onClose = { nav.backToHome() },
                            onCompleted = {
                                nav.navigate(DoneDestination) {
                                    popUpTo(HomeDestination)
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
                composable<DoneDestination> {
                    DoneRoute(
                        onClose = { nav.backToHome() },
                        onTransferAnother = { open(LaunchTarget.Receive) },
                    )
                }
                composable<HistoryDestination> { HistoryRoute(onBack = nav::popBackStack) }
                composable<LabDestination> { GestureLabRoute(onBack = nav::popBackStack) }
                composable<SettingsDestination> { SettingsRoute(onBack = nav::popBackStack, onLab = { nav.navigate(LabDestination) }) }
            }
        }
    }
}

private fun LaunchTarget.destination(): Any = when (this) {
    LaunchTarget.Send -> SendDestination
    LaunchTarget.Receive -> ReceiveDestination
}

private fun NavHostController.backToHome() {
    if (!popBackStack(HomeDestination, inclusive = false)) navigate(HomeDestination)
}
