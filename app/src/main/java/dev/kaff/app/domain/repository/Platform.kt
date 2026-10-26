package dev.kaff.app.domain.repository

import dev.kaff.app.domain.transfer.FeedbackKind
import dev.kaff.app.domain.transfer.IncomingAlert
import kotlinx.coroutines.flow.StateFlow

/** Haptics and the optional arrival sound, honoring the user's settings. */
interface FeedbackPlayer {
    fun play(kind: FeedbackKind)
}

/** Heads-up notification for incoming requests while the app is not visible. */
interface IncomingNotifier {
    fun alert(alert: IncomingAlert)
    fun clear()
}

/** Name this phone shows to nearby devices (user setting or the phone model). */
interface LocalDevice {
    val name: StateFlow<String>
}
