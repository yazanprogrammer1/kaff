package dev.kaff.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import dev.kaff.app.R
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.PickItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** The items picked for the next transfer, shared by the Picker and Send screens. */
@Singleton
class Basket @Inject constructor() {
    private val _items = MutableStateFlow<List<PickItem>>(emptyList())
    val items: StateFlow<List<PickItem>> = _items.asStateFlow()

    /** Adds or removes [item]. Returns false when adding would exceed [MAX_ITEMS]. */
    fun toggle(item: PickItem): Boolean {
        var accepted = true
        _items.update { current ->
            when {
                current.any { it.uri == item.uri } -> current.filterNot { it.uri == item.uri }
                current.size >= MAX_ITEMS -> current.also { accepted = false }
                else -> current + item
            }
        }
        return accepted
    }

    /** Adds what fits; returns false if some items were left out because of [MAX_ITEMS]. */
    fun addAll(items: List<PickItem>): Boolean {
        var allFit = true
        _items.update { current ->
            val fresh = items.filter { candidate -> current.none { it.uri == candidate.uri } }
            val room = MAX_ITEMS - current.size
            if (fresh.size > room) allFit = false
            current + fresh.take(room.coerceAtLeast(0))
        }
        return allFit
    }

    fun removeAll(uris: Set<String>) = _items.update { current -> current.filterNot { it.uri in uris } }

    fun clear() {
        _items.value = emptyList()
    }

    companion object {
        /** Keeps OFFER inside Nearby's 32 KB message limit. */
        const val MAX_ITEMS = 50
    }
}

/** "3 photos · video · file": one entry per kind, in a stable order. */
@Composable
fun kindSummary(kinds: List<FileKind>, separator: String = " · "): String {
    val order = listOf(FileKind.Image, FileKind.Video, FileKind.Audio, FileKind.App, FileKind.Other)
    return order.mapNotNull { kind ->
        val count = kinds.count { it == kind }
        if (count == 0) null else kindCount(kind, count)
    }.joinToString(separator)
}

@Composable
fun kindCount(kind: FileKind, count: Int): String = pluralStringResource(
    when (kind) {
        FileKind.Image -> R.plurals.count_images
        FileKind.Video -> R.plurals.count_videos
        FileKind.Audio -> R.plurals.count_audio
        FileKind.App -> R.plurals.count_apps
        FileKind.Other -> R.plurals.count_files
    },
    count,
    count,
)

@Composable
fun itemCount(count: Int): String = pluralStringResource(R.plurals.count_items, count, count)
