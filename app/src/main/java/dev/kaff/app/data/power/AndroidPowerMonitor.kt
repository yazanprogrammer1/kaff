package dev.kaff.app.data.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.domain.model.PowerState
import dev.kaff.app.domain.repository.PowerMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Battery saver and thermal status, observed only while someone collects (i.e. the camera is on). */
@Singleton
class AndroidPowerMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : PowerMonitor {

    private val powerManager = context.getSystemService(PowerManager::class.java)

    override val state: Flow<PowerState> = callbackFlow {
        fun current() = PowerState(
            batterySaver = powerManager.isPowerSaveMode,
            thermalThrottled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
        )
        trySend(current())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(current())
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { trySend(current()) }
                .also { powerManager.addThermalStatusListener(context.mainExecutor, it) }
        } else {
            null
        }
        awaitClose {
            context.unregisterReceiver(receiver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
                powerManager.removeThermalStatusListener(thermalListener)
            }
        }
    }.distinctUntilChanged()
}
