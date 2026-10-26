package dev.kaff.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.kaff.app.R
import dev.kaff.app.core.LaunchTarget
import dev.kaff.app.core.di.ApplicationScope
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.data.sensors.ShakeDetector
import dev.kaff.app.domain.repository.SettingsRepository
import dev.kaff.app.domain.transfer.ReceiveController
import dev.kaff.app.domain.transfer.ReceiveEvent
import dev.kaff.app.domain.transfer.ReceiveState
import dev.kaff.app.domain.transfer.SendController
import dev.kaff.app.domain.transfer.SendState
import dev.kaff.app.service.notifications.KaffNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Opt-in Standby mode: keeps Kaff discoverable (Nearby advertising) with a persistent notification
 * that has a Stop action, and optionally listens for a shake. The camera is never opened here;
 * an incoming request posts a heads-up notification and the user taps it.
 */
@AndroidEntryPoint
class StandbyService : Service() {

    @Inject lateinit var receiveController: ReceiveController
    @Inject lateinit var sendController: SendController
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var job: Job? = null
    private var shake: ShakeDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appScope.launch { settings.update { it.copy(standbyMode = false) } }
            receiveController.dispatch(ReceiveEvent.Stop)
            stopSelf()
            return START_NOT_STICKY
        }
        if (KaffPermissions.missingNearby(this).isNotEmpty() || !startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) observe()
        return START_STICKY
    }

    private fun startInForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, KaffNotifications.ID_STANDBY, notification(), type)
        true
    } catch (e: IllegalStateException) {
        // Android 12+ refuses foreground starts from the background; Standby resumes next time the app opens.
        Log.w(TAG, "Cannot start Standby in the foreground", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Missing permission for Standby", e)
        false
    }

    @OptIn(FlowPreview::class)
    private fun observe() {
        job = appScope.launch {
            launch {
                // Stay discoverable whenever neither role is busy.
                combine(receiveController.state, sendController.model) { receive, send ->
                    receive == ReceiveState.Idle && send.state == SendState.Idle
                }.distinctUntilChanged().debounce(RESTART_DELAY_MS).collect { idle ->
                    if (idle) receiveController.dispatch(ReceiveEvent.Start)
                }
            }
            launch {
                settings.settings.map { it.shakeToWake }.distinctUntilChanged().collect { enabled ->
                    shake?.stop()
                    shake = if (enabled) ShakeDetector(this@StandbyService, ::onShake).also { it.start() } else null
                }
            }
        }
    }

    /** Shake while Kaff is in the background: open it if allowed, otherwise offer a notification. */
    private fun onShake() {
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (visible) return // MainActivity handles shakes itself while open.
        if (Settings.canDrawOverlays(this)) {
            startActivity(LaunchTarget.Receive.intent(this))
        } else if (KaffNotifications.canPost(this)) {
            val notification = NotificationCompat.Builder(this, KaffNotifications.CHANNEL_OFFERS)
                .setSmallIcon(R.drawable.ic_stat_kaff)
                .setContentTitle(getString(R.string.notif_shake_title))
                .setContentText(getString(R.string.notif_shake_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(LaunchTarget.Receive.pendingIntent(this))
                .setAutoCancel(true)
                .setTimeoutAfter(SHAKE_NOTIFICATION_TIMEOUT_MS)
                .build()
            try {
                NotificationManagerCompat.from(this).notify(KaffNotifications.ID_SHAKE, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission revoked", e)
            }
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, StandbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, KaffNotifications.CHANNEL_STANDBY)
            .setSmallIcon(R.drawable.ic_stat_kaff)
            .setContentTitle(getString(R.string.notif_standby_title))
            .setContentText(getString(R.string.notif_standby_body))
            .setOngoing(true)
            .setContentIntent(LaunchTarget.Receive.pendingIntent(this))
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        shake?.stop()
        if (receiveController.state.value is ReceiveState.Advertising) receiveController.dispatch(ReceiveEvent.Stop)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StandbyService"
        private const val ACTION_STOP = "dev.kaff.app.action.STOP_STANDBY"
        private const val RESTART_DELAY_MS = 1_000L
        private const val SHAKE_NOTIFICATION_TIMEOUT_MS = 30_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, StandbyService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Standby start refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StandbyService::class.java))
        }
    }
}
