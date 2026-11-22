package dev.kaff.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Phase {
    data object Searching : Phase
    data class Approving(val phone: Phone) : Phase
    data class Connected(val phone: Phone) : Phase
}

enum class TransferStatus { Waiting, Active, Done, Failed, Cancelled }

/** One file moving in either direction, newest first on screen. */
data class Transfer(
    val id: Long,
    val name: String,
    val size: Long,
    val incoming: Boolean,
    val bytes: Long = 0,
    val status: TransferStatus = TransferStatus.Waiting,
    val path: Path? = null,
    val startedAtMs: Long = 0,
    val finishedAtMs: Long = 0,
)

/**
 * Everything the window shows: finding phones, connecting (approval once, then trusted), and the
 * transfers. Files the phone sends download into Downloads\Kaff on their own, like a USB copy.
 */
class KaffController(private val scope: CoroutineScope, val identity: Identity) {
    val discovery = Discovery(scope)

    private val _phase = MutableStateFlow<Phase>(Phase.Searching)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    /** Kaff networks in range when no phone answers on this network. */
    private val _kaffNetworks = MutableStateFlow<List<String>>(emptyList())
    val kaffNetworks: StateFlow<List<String>> = _kaffNetworks.asStateFlow()

    private val _fast = MutableStateFlow<FastNetwork?>(null)
    val fastNetwork: StateFlow<FastNetwork?> = _fast.asStateFlow()

    private val _denied = MutableStateFlow(false)
    val denied: StateFlow<Boolean> = _denied.asStateFlow()

    /** New things for a system notification: (title, body). */
    val notifications = Channel<Pair<String, String>>(Channel.BUFFERED)

    private var client: PhoneClient? = null
    private var session: Job? = null
    private val ids = AtomicLong()
    private val seenFiles = ConcurrentHashMap.newKeySet<Int>()
    private var lastClip = 0L
    /** Phone file and the transfer row that shows it. */
    private val downloads = Channel<Pair<PhoneFile, Long>>(Channel.UNLIMITED)

    fun start() {
        discovery.start()
        scope.launch { autoConnect() }
        scope.launch(Dispatchers.IO) { scanKaffNetworks() }
        scope.launch(Dispatchers.IO) { for ((file, row) in downloads) download(file, row) }
    }

    /** Reconnects by itself to the phone used last time, as soon as it shows up. */
    private suspend fun autoConnect() {
        discovery.phones.collect { phones ->
            if (_phase.value != Phase.Searching) return@collect
            // By the phone's stable id: two phones may well carry the same name.
            val last = identity.lastPhone ?: return@collect
            phones.firstOrNull { it.memoryKey == last }?.let { connect(it) }
        }
    }

    private suspend fun scanKaffNetworks() {
        while (scope.isActive) {
            if (_phase.value == Phase.Searching && discovery.phones.value.isEmpty()) {
                WifiNetworks.scan()
                delay(3_000)
                _kaffNetworks.value = WifiNetworks.kaffNetworksInRange()
            }
            delay(6_000)
        }
    }

    fun connect(phone: Phone) {
        if (_phase.value is Phase.Approving) return
        session?.cancel()
        _denied.value = false
        _phase.value = Phase.Approving(phone)
        session = scope.launch(Dispatchers.IO) {
            val c = PhoneClient(phone.base, identity)
            val hello = runCatching { c.hello() }.getOrNull()
            if (hello?.approved != true) {
                _denied.value = hello != null
                _phase.value = Phase.Searching
                return@launch
            }
            client = c
            identity.lastPhone = phone.memoryKey
            _fast.value = hello.fast
            seenFiles.clear()
            lastClip = 0
            _clips.value = emptyList()
            _phase.value = Phase.Connected(phone)
            notifications.trySend(Strings.t.connectedTitle to phone.name)
            poll(c, first = true)
        }
    }

    fun disconnect() {
        session?.cancel()
        client = null
        identity.lastPhone = null
        _phase.value = Phase.Searching
    }

    private suspend fun poll(c: PhoneClient, first: Boolean) {
        var firstRound = first
        var failures = 0
        while (scope.isActive) {
            try {
                val files = c.files()
                // Sent before we connected but not on a computer yet, or new since the last look.
                val due = files.filter { seenFiles.add(it.id) && (!firstRound || !it.delivered) }
                if (due.isNotEmpty()) {
                    due.forEach { file ->
                        val row = ids.incrementAndGet()
                        _transfers.update { listOf(Transfer(row, file.name, file.size, incoming = true)) + it }
                        downloads.send(file to row)
                    }
                    notifications.trySend(Strings.t.newFilesTitle(due.size) to (due.firstOrNull()?.name ?: ""))
                }
                val clips = c.clips(lastClip)
                if (clips.isNotEmpty()) {
                    lastClip = clips.maxOf { it.id }
                    _clips.update { (it + clips).takeLast(50) }
                    clips.filter { it.phone && !firstRound }.forEach { notifications.trySend(Strings.t.clipTitle to it.text.take(80)) }
                }
                firstRound = false
                failures = 0
            } catch (e: Exception) {
                if (++failures >= 3) {
                    // The phone left this network or closed its computer screen: look for it again.
                    client = null
                    _phase.value = Phase.Searching
                    return
                }
            }
            delay(1_500)
        }
    }

    private fun download(file: PhoneFile, row: Long) {
        val c = client ?: return update(row) { it.copy(status = TransferStatus.Failed) }
        val dir = identity.downloads
        Files.createDirectories(dir)
        val target = unique(dir, file.name)
        update(row) { it.copy(status = TransferStatus.Active, startedAtMs = System.currentTimeMillis(), path = target) }
        try {
            c.download(file, target) { bytes -> update(row) { it.copy(bytes = bytes) } }
            update(row) { it.copy(status = TransferStatus.Done, bytes = file.size, finishedAtMs = System.currentTimeMillis()) }
        } catch (e: Exception) {
            Files.deleteIfExists(target.resolveSibling(target.fileName.toString() + ".part"))
            update(row) { it.copy(status = TransferStatus.Failed, finishedAtMs = System.currentTimeMillis()) }
        }
    }

    /** Sends files to the phone, one after another. */
    fun send(paths: List<Path>) {
        val c = client ?: return
        val entries = paths.filter { Files.isRegularFile(it) }.map { Transfer(ids.incrementAndGet(), it.fileName.toString(), Files.size(it), incoming = false, path = it) }
        if (entries.isEmpty()) return
        _transfers.update { entries.reversed() + it }
        scope.launch(Dispatchers.IO) {
            for (entry in entries) {
                val path = entry.path ?: continue
                update(entry.id) { it.copy(status = TransferStatus.Active, startedAtMs = System.currentTimeMillis()) }
                val status = runCatching {
                    c.upload(path, Files.probeContentType(path)) { bytes -> update(entry.id) { it.copy(bytes = bytes) } }
                }.getOrDefault(0)
                update(entry.id) {
                    it.copy(
                        status = when (status) {
                            200 -> TransferStatus.Done
                            409 -> TransferStatus.Cancelled
                            else -> TransferStatus.Failed
                        },
                        bytes = if (status == 200) it.size else it.bytes,
                        finishedAtMs = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    fun sendClip(text: String) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) { runCatching { c.sendClip(text) } }
    }

    /** Joins Kaff's network ([password] only the first time; Windows keeps it). */
    fun joinKaffNetwork(ssid: String, password: String?, done: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) { done(WifiNetworks.join(ssid, password)) }
    }

    fun askCompatibleNetwork() {
        val c = client ?: return
        scope.launch(Dispatchers.IO) { c.askCompatibleNetwork() }
    }

    private fun update(id: Long, change: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun unique(dir: Path, name: String): Path {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[<>:\"|?*\\x00-\\x1F]"), "_").ifBlank { "kaff_file" }
        var candidate = dir.resolve(clean)
        var n = 1
        val base = clean.substringBeforeLast('.')
        val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (Files.exists(candidate)) candidate = dir.resolve("$base ($n)$ext").also { n++ }
        return candidate
    }
}
