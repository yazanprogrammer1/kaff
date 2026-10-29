package dev.kaff.app.presentation.desktop

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.data.desktop.DesktopClient
import dev.kaff.app.data.desktop.DesktopHost
import dev.kaff.app.data.desktop.DesktopState
import dev.kaff.app.data.media.MediaLibrary
import dev.kaff.app.presentation.common.Basket
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DesktopUiState(val desktop: DesktopState = DesktopState())

@HiltViewModel
class DesktopViewModel @Inject constructor(
    private val host: DesktopHost,
    private val basket: Basket,
    private val library: MediaLibrary,
) : ViewModel() {

    val state: StateFlow<DesktopUiState> = host.state.map(::DesktopUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, DesktopUiState())

    init {
        host.start()
        // Wi-Fi can change while the screen is open (joined a network, hotspot came up).
        viewModelScope.launch {
            while (isActive) {
                delay(ADDRESS_REFRESH_MS)
                host.refreshAddress()
            }
        }
    }

    fun decide(client: DesktopClient, allow: Boolean) = host.decide(client, allow)
    fun startHotspot() = host.startHotspot()
    fun stopHotspot() = host.stopHotspot()
    fun setCompatible(compatible: Boolean) = host.setCompatibleMode(compatible)
    fun clearFiles() = host.clearOutgoing()
    fun cancelIncoming(id: Long) = host.cancelIncoming(id)

    /** The picker opened from here starts empty: earlier picks were already sent. */
    fun startPicking() = basket.clear()

    /** "Ready" in the picker: everything picked goes to the computer now, and the basket empties. */
    fun sendPicked() {
        host.push(basket.items.value)
        basket.clear()
    }
    fun sendClip(text: String) = host.addClip(text)

    fun addDocuments(uris: List<Uri>) {
        viewModelScope.launch { host.push(uris.mapNotNull { library.describe(it) }) }
    }

    override fun onCleared() {
        host.stop()
    }

    private companion object {
        const val ADDRESS_REFRESH_MS = 3_000L
    }
}
