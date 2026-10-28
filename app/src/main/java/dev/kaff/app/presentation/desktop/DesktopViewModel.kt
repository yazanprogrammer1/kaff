package dev.kaff.app.presentation.desktop

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.data.desktop.DesktopClient
import dev.kaff.app.data.desktop.DesktopHost
import dev.kaff.app.data.desktop.DesktopState
import dev.kaff.app.data.media.MediaLibrary
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.presentation.common.Basket
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DesktopUiState(val desktop: DesktopState = DesktopState(), val items: List<PickItem> = emptyList())

@HiltViewModel
class DesktopViewModel @Inject constructor(
    private val host: DesktopHost,
    private val basket: Basket,
    private val library: MediaLibrary,
) : ViewModel() {

    val state: StateFlow<DesktopUiState> = combine(host.state, basket.items, ::DesktopUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, DesktopUiState())

    init {
        host.start()
        viewModelScope.launch { basket.items.collect { host.offer(it.filterNot { item -> item.unsupported }) } }
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
    fun clearFiles() = basket.clear()
    fun sendClip(text: String) = host.addClip(text)

    fun addDocuments(uris: List<Uri>) {
        viewModelScope.launch { basket.addAll(uris.mapNotNull { library.describe(it) }) }
    }

    override fun onCleared() {
        host.stop()
    }

    private companion object {
        const val ADDRESS_REFRESH_MS = 3_000L
    }
}
