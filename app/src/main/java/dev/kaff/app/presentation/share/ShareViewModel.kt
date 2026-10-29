package dev.kaff.app.presentation.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.core.ShareInbox
import dev.kaff.app.data.desktop.DesktopHost
import dev.kaff.app.data.media.MediaLibrary
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.presentation.common.Basket
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ShareUiState(val loading: Boolean = true, val items: List<PickItem> = emptyList())

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val inbox: ShareInbox,
    private val library: MediaLibrary,
    private val basket: Basket,
    private val desktop: DesktopHost,
) : ViewModel() {

    val state: StateFlow<ShareUiState> = inbox.uris
        .map { uris -> ShareUiState(loading = false, items = uris.mapNotNull { library.describe(it) }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareUiState())

    /** Puts the shared items in the basket; both destinations read it. */
    fun fillBasket() {
        basket.clear()
        basket.addAll(state.value.items)
    }

    /** Shared text reaches the computer as copyable text; files go in the basket. */
    fun toDesktop() {
        val text = inbox.text
        if (text != null) desktop.addClip(text) else desktop.push(state.value.items)
    }
}
