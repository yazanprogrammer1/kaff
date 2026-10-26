package dev.kaff.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.kaff.app.core.LaunchTarget
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.data.sensors.ShakeDetector
import dev.kaff.app.domain.model.AppSettings
import dev.kaff.app.domain.model.ThemeMode
import dev.kaff.app.domain.repository.SettingsRepository
import dev.kaff.app.presentation.navigation.KaffNavHost
import dev.kaff.app.service.FloatingBubbleService
import dev.kaff.app.service.StandbyService
import dev.kaff.app.ui.theme.KaffTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    /** Screens requested by the tile, bubble, shake or a notification. */
    private val launchTargets = Channel<LaunchTarget>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) LaunchTarget.from(intent)?.let { launchTargets.trySend(it) }
        setContent {
            val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = when (appSettings.themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                // Status and navigation bar icons follow the app theme, not the system one.
                val transparent = Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            KaffTheme(darkTheme = dark) {
                KaffNavHost(launchTargets.receiveAsFlow())
            }
        }
        restoreWakeServices()
        listenForShake()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LaunchTarget.from(intent)?.let { launchTargets.trySend(it) }
    }

    /** Opt-in services cannot start from the background, so they are (re)started while the app is open. */
    private fun restoreWakeServices() {
        lifecycleScope.launch {
            val current = settings.settings.first()
            if (current.standbyMode && KaffPermissions.missingNearby(this@MainActivity).isEmpty()) StandbyService.start(this@MainActivity)
            if (current.floatingBubble && Settings.canDrawOverlays(this@MainActivity)) FloatingBubbleService.start(this@MainActivity)
        }
    }

    /** Shake to wake while Kaff is on screen; Standby mode handles shakes in the background. */
    private fun listenForShake() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!settings.settings.first().shakeToWake) return@repeatOnLifecycle
                val detector = ShakeDetector(this@MainActivity) { launchTargets.trySend(LaunchTarget.Receive) }
                detector.start()
                try {
                    awaitCancellation()
                } finally {
                    detector.stop()
                }
            }
        }
    }
}
