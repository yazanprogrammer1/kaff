package dev.kaff.app.service.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.R
import dev.kaff.app.core.LaunchTarget
import dev.kaff.app.domain.repository.IncomingNotifier
import dev.kaff.app.domain.transfer.IncomingAlert
import javax.inject.Inject
import javax.inject.Singleton

object KaffNotifications {
    const val CHANNEL_OFFERS = "offers"
    const val CHANNEL_STANDBY = "standby"
    const val CHANNEL_BUBBLE = "bubble"

    const val ID_INCOMING = 1
    const val ID_STANDBY = 2
    const val ID_BUBBLE = 3
    const val ID_SHAKE = 4

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_OFFERS, context.getString(R.string.notif_channel_offers), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.notif_channel_offers_desc) },
                NotificationChannel(CHANNEL_STANDBY, context.getString(R.string.notif_channel_standby), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_BUBBLE, context.getString(R.string.notif_channel_bubble), NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/**
 * Posts a heads-up notification when a sender wants to connect or offers a file while Kaff is not
 * in the foreground. Tapping it opens the receive screen; nothing launches by itself.
 */
@Singleton
class AndroidIncomingNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : IncomingNotifier {

    private val main = Handler(Looper.getMainLooper())

    override fun alert(alert: IncomingAlert) {
        main.post {
            val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (visible || !KaffNotifications.canPost(context)) return@post
            val (title, body) = when (alert) {
                is IncomingAlert.Pairing ->
                    context.getString(R.string.notif_pair_title, alert.peerName) to context.getString(R.string.notif_pair_body)
                is IncomingAlert.Offer -> {
                    val first = alert.offer.files.first()
                    val what = if (alert.offer.files.size > 1) {
                        context.resources.getQuantityString(R.plurals.file_count, alert.offer.files.size, alert.offer.files.size)
                    } else {
                        first.name
                    }
                    context.getString(R.string.notif_pair_title, alert.peerName) to context.getString(R.string.notif_offer_body, what)
                }
            }
            val notification = NotificationCompat.Builder(context, KaffNotifications.CHANNEL_OFFERS)
                .setSmallIcon(R.drawable.ic_stat_kaff)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(LaunchTarget.Receive.pendingIntent(context))
                .setAutoCancel(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(KaffNotifications.ID_INCOMING, notification)
            } catch (e: SecurityException) {
                // Permission revoked between the check and the post; nothing else to do.
            }
        }
    }

    override fun clear() {
        main.post { NotificationManagerCompat.from(context).cancel(KaffNotifications.ID_INCOMING) }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
