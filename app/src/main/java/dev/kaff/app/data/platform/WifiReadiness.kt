package dev.kaff.app.data.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the Wi-Fi radio is on, here and on the paired phone. Nearby only leaves Bluetooth
 * (~0.2 MB/s) for Wi-Fi Direct (12+ MB/s) when both radios are on; no internet is needed. The peer's
 * state arrives in its HELLO.
 */
@Singleton
class WifiReadiness @Inject constructor(@ApplicationContext private val context: Context) {

    private val wifi = context.getSystemService(WifiManager::class.java)
    private val _local = MutableStateFlow(wifi?.isWifiEnabled ?: true)
    val local: StateFlow<Boolean> = _local.asStateFlow()

    private val _peer = MutableStateFlow<Boolean?>(null)

    /** Null until a peer said; then whether its Wi-Fi radio is on. */
    val peer: StateFlow<Boolean?> = _peer.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    _local.value = wifi?.isWifiEnabled ?: true
                }
            },
            IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun peerSaid(on: Boolean?) {
        _peer.value = on
    }
}
