package dev.kaff.app.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.kaff.app.MainActivity

/** Screens that wake methods (tile, bubble, shake, notifications, the share sheet) can open directly. */
enum class LaunchTarget {
    Send,
    Receive,

    /** Something was shared to Kaff: choose another phone or the desktop. */
    Share,
    Desktop,
    ;

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN)
        .putExtra(EXTRA, name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        ordinal,
        intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val ACTION_OPEN = "dev.kaff.app.action.OPEN"
        private const val EXTRA = "dev.kaff.app.extra.LAUNCH_TARGET"

        fun from(intent: Intent?): LaunchTarget? =
            intent?.getStringExtra(EXTRA)?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
