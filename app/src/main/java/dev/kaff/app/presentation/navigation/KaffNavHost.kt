package dev.kaff.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.kaff.app.presentation.home.HomeRoute
import dev.kaff.app.presentation.lab.GestureLabRoute
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination

@Serializable data object LabDestination

@Composable
fun KaffNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(
                onSend = {},
                onReceive = {},
                onSettings = {},
                onHistory = {},
                onLab = { nav.navigate(LabDestination) },
            )
        }
        composable<LabDestination> { GestureLabRoute(onBack = nav::popBackStack) }
    }
}
