package dev.kaff.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.kaff.app.core.LaunchTarget
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.data.sensors.ShakeDetector
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
        enableEdgeToEdge()
        if (savedInstanceState == null) LaunchTarget.from(intent)?.let { launchTargets.trySend(it) }
        setContent {
            KaffTheme {
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
