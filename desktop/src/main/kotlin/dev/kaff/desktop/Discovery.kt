package dev.kaff.desktop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A phone with Kaff's computer screen open, found on one of this computer's networks. */
data class Phone(val id: String?, val name: String, val host: String, val port: Int, val path: String, val network: String?, val seenAtMs: Long) {
    /** How the app remembers this phone: its stable id, or its name for older phone builds. */
    val memoryKey: String get() = id?.let { "id:$it" } ?: "name:$name"

    val base: String get() = "http://$host:$port$path"
    val onKaffNetwork: Boolean get() = host.startsWith("192.168.49.")
}

@Serializable
private data class Answer(val app: String, val v: Int, val name: String, val port: Int, val path: String, val network: String? = null, val id: String? = null)

/**
 * Asks every network "KAFF?" (UDP broadcast, port 4998) every two seconds and lists the phones that
 * answer. The answers are unicast replies to our own question, which Windows Firewall lets in.
 */
class Discovery(private val scope: CoroutineScope) {
    private val _phones = MutableStateFlow<List<Phone>>(emptyList())
    val phones: StateFlow<List<Phone>> = _phones.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        scope.launch(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                var lastAsk = 0L
                val buffer = ByteArray(1024)
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastAsk >= ASK_EVERY_MS) {
                        ask(socket)
                        lastAsk = now
                        // Phones that stopped answering (screen closed, network changed) drop off.
                        _phones.value = _phones.value.filter { now - it.seenAtMs < FORGET_AFTER_MS }
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        delay(500)
                        continue
                    }
                    val answer = runCatching { json.decodeFromString(Answer.serializer(), String(packet.data, 0, packet.length)) }.getOrNull()
                    if (answer == null || answer.app != "kaff") continue
                    val host = packet.address.hostAddress
                    val phone = Phone(answer.id, answer.name, host, answer.port, answer.path, answer.network, System.currentTimeMillis())
                    // One phone session (its unique path) can answer on several of our networks: keep one
                    // card, preferring Kaff's direct network, and otherwise the address already shown.
                    val same = _phones.value.firstOrNull { it.path == phone.path }
                    val keep = when {
                        same == null -> phone
                        phone.onKaffNetwork && !same.onKaffNetwork -> phone
                        same.host == phone.host -> phone
                        else -> same.copy(seenAtMs = phone.seenAtMs)
                    }
                    _phones.value = (_phones.value.filterNot { it.path == phone.path } + keep).sortedBy { it.name }
                }
            }
        }
    }

    private var sweeps = 0

    private fun ask(socket: DatagramSocket) {
        val question = "KAFF?".toByteArray()
        // Every other round, also ask each address of our own networks directly: many routers and
        // phones drop broadcasts on home Wi-Fi, but nobody drops a plain unicast packet.
        val addresses = if (sweeps++ % 2 == 0) targets() + sweepTargets() else targets()
        addresses.forEach { address ->
            runCatching { socket.send(DatagramPacket(question, question.size, address, PORT)) }
        }
    }

    /** Every host address of each IPv4 network this computer is on (subnets up to /22, 1022 hosts). */
    private fun sweepTargets(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.networkPrefixLength.toInt() in MIN_SWEEP_PREFIX..30 }
            .flatMap { hostsOf(it.address as Inet4Address, it.networkPrefixLength.toInt()) }
    }.getOrDefault(emptyList())

    private fun hostsOf(address: Inet4Address, prefix: Int): List<InetAddress> {
        val own = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = own and mask
        val size = 1L shl (32 - prefix)
        return (1L until size - 1).map { network + it }.filter { it != own }.map { ip ->
            InetAddress.getByAddress(byteArrayOf((ip shr 24).toByte(), (ip shr 16).toByte(), (ip shr 8).toByte(), ip.toByte()))
        }
    }

    /** The global broadcast, each interface's own broadcast, and the Wi-Fi Direct group owner. */
    private fun targets(): Set<InetAddress> {
        val out = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"), InetAddress.getByName(KAFF_GROUP_OWNER))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNull { it.broadcast }
                .forEach { out += it }
        }
        return out
    }

    companion object {
        const val PORT = 4998
        const val KAFF_GROUP_OWNER = "192.168.49.1"
        private const val ASK_EVERY_MS = 2_000L
        private const val FORGET_AFTER_MS = 7_000L
        private const val MIN_SWEEP_PREFIX = 22
    }
}
