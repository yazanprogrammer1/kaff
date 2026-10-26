package dev.kaff.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.kaff.app.R
import dev.kaff.app.core.LaunchTarget
import dev.kaff.app.core.di.ApplicationScope
import dev.kaff.app.domain.repository.SettingsRepository
import dev.kaff.app.service.notifications.KaffNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * Opt-in floating bubble drawn over other apps (SYSTEM_ALERT_WINDOW). Drag to move, tap to open
 * the receive screen. Runs as a foreground service with a notification that can stop it.
 */
@AndroidEntryPoint
class FloatingBubbleService : Service() {

    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var windowManager: WindowManager? = null
    private var bubble: ImageView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appScope.launch { settings.update { it.copy(floatingBubble = false) } }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this) || !startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        return START_STICKY
    }

    private fun startInForeground(): Boolean = try {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, KaffNotifications.CHANNEL_BUBBLE)
            .setSmallIcon(R.drawable.ic_stat_kaff)
            .setContentTitle(getString(R.string.notif_bubble_title))
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, KaffNotifications.ID_BUBBLE, notification, type)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Cannot start bubble in the foreground", e)
        false
    }

    @SuppressLint("ClickableViewAccessibility") // Taps are forwarded to performClick() below.
    private fun showBubble() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        val size = resources.getDimensionPixelSize(R.dimen.bubble_size)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            contentDescription = getString(R.string.bubble_description)
            setOnClickListener { startActivity(LaunchTarget.Receive.intent(this@FloatingBubbleService)) }
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Snap to the nearest screen edge.
                        val width = resources.displayMetrics.widthPixels
                        params.x = if (params.x + size / 2 < width / 2) 0 else width - size
                        wm.updateViewLayout(v, params)
                    } else {
                        v.performClick()
                    }
                }
            }
            true
        }
        try {
            wm.addView(view, params)
            windowManager = wm
            bubble = view
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Overlay not allowed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        bubble?.let { windowManager?.removeView(it) }
        bubble = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FloatingBubble"
        private const val ACTION_STOP = "dev.kaff.app.action.STOP_BUBBLE"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingBubbleService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Bubble start refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
