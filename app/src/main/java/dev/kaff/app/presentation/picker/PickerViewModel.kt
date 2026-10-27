package dev.kaff.app.presentation.picker

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.data.media.MediaLibrary
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.MediaAccess
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.PickSource
import dev.kaff.app.presentation.common.Basket
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PickTab { Photos, Videos, Apps, Files, Audio }

/** Which tab an item is counted under. */
fun PickItem.tab(): PickTab = when (source) {
    PickSource.App -> PickTab.Apps
    PickSource.Document -> PickTab.Files
    PickSource.Media -> when (kind) {
        FileKind.Image -> PickTab.Photos
        FileKind.Video -> PickTab.Videos
        FileKind.Audio -> PickTab.Audio
        FileKind.App, FileKind.Other -> PickTab.Files
    }
}

@Immutable
data class PickerUiState(
    val tab: PickTab = PickTab.Photos,
    /** Null while the tab is loading. */
    val items: List<PickItem>? = null,
    val access: MediaAccess = MediaAccess.Full,
    val query: String = "",
    val searching: Boolean = false,
    val basket: List<PickItem> = emptyList(),
    val limitHit: Boolean = false,
) {
    val selectedUris: Set<String> = basket.mapTo(HashSet()) { it.uri }
    val countByTab: Map<PickTab, Int> = basket.groupingBy { it.tab() }.eachCount()

    val visible: List<PickItem>?
        get() = items?.let { list ->
            if (query.isBlank()) list else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
        }
}

@HiltViewModel
class PickerViewModel @Inject constructor(
    private val library: MediaLibrary,
    private val basket: Basket,
) : ViewModel() {

    private val tab = MutableStateFlow(PickTab.Photos)
    private val loaded = MutableStateFlow<Map<PickTab, List<PickItem>>>(emptyMap())
    private val access = MutableStateFlow<Map<PickTab, MediaAccess>>(emptyMap())
    private val query = MutableStateFlow("")
    private val searching = MutableStateFlow(false)
    private val limitHit = MutableStateFlow(false)

    val state: StateFlow<PickerUiState> = combine(
        combine(tab, loaded, access) { t, l, a -> Triple(t, l, a) },
        query,
        searching,
        basket.items,
        limitHit,
    ) { (t, l, a), q, s, b, limit ->
        PickerUiState(
            tab = t,
            items = if (t == PickTab.Files) b.filter { it.source == PickSource.Document } else l[t],
            access = a[t] ?: MediaAccess.Full,
            query = q,
            searching = s,
            basket = b,
            limitHit = limit,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PickerUiState())

    init {
        load(PickTab.Photos)
    }

    fun selectTab(newTab: PickTab) {
        tab.value = newTab
        if (newTab !in loaded.value) load(newTab)
    }

    /** Reloads everything after a permission change. */
    fun refresh() {
        loaded.value = emptyMap()
        load(tab.value)
    }

    private fun load(target: PickTab) {
        viewModelScope.launch {
            val collection = when (target) {
                PickTab.Photos -> MediaLibrary.Collection.Images
                PickTab.Videos -> MediaLibrary.Collection.Videos
                PickTab.Audio -> MediaLibrary.Collection.Audio
                PickTab.Apps, PickTab.Files -> null
            }
            val items = when {
                target == PickTab.Apps -> library.apps()
                collection == null -> emptyList()
                else -> {
                    val level = library.access(collection)
                    access.update { it + (target to level) }
                    if (level == MediaAccess.None) emptyList() else library.media(collection)
                }
            }
            loaded.update { it + (target to items) }
        }
    }

    fun toggle(item: PickItem) {
        if (item.unsupported) return
        limitHit.value = !basket.toggle(item)
    }

    fun selectAllVisible() {
        val visible = state.value.visible.orEmpty().filterNot { it.unsupported }
        val selected = state.value.selectedUris
        if (visible.isNotEmpty() && visible.all { it.uri in selected }) {
            basket.removeAll(visible.mapTo(HashSet()) { it.uri })
        } else {
            limitHit.value = !basket.addAll(visible)
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleSearch() {
        searching.update { !it }
        if (!searching.value) query.value = ""
    }

    /** Documents or media picked with a system picker go straight into the basket. */
    fun addUris(uris: List<Uri>, asDocuments: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = uris.map { async { library.describe(it) } }.awaitAll().filterNotNull()
                .map { if (asDocuments) it else it.copy(source = PickSource.Media) }
            limitHit.value = !basket.addAll(items)
        }
    }
}
