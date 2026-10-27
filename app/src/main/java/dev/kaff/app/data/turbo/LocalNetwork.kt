package dev.kaff.app.data.turbo

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.SocketException

/** IPv4 addresses this phone can be reached on by the peer of a Nearby Wi-Fi link. */
internal object LocalNetwork {

    /** Cellular interfaces never carry the phone-to-phone link. */
    private val CELLULAR = listOf("rmnet", "ccmni", "pdp", "seth", "v4-rmnet", "dummy", "tun")

    private fun interfaceAddresses(): List<Pair<NetworkInterface, InterfaceAddress>> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && CELLULAR.none(it.name::startsWith) }
            .flatMap { nif -> nif.interfaceAddresses.filter { it.address is Inet4Address }.map { nif to it } }
    } catch (e: SocketException) {
        emptyList()
    }

    /** Wi-Fi Direct (p2p*) first: that is where Nearby puts its high-speed upgrade. */
    fun advertisedAddresses(): List<String> = interfaceAddresses()
        .sortedBy { (nif, _) -> if (nif.name.startsWith("p2p")) 0 else 1 }
        .mapNotNull { (_, a) -> a.address.hostAddress }
        .distinct()

    /**
     * The local address on the same subnet as [remote]. Sockets must be bound to it: without a bind
     * Android routes through the default network (often plain Wi-Fi), which cannot reach the peer.
     */
    fun localFor(remote: InetAddress): InetAddress? = interfaceAddresses()
        .map { it.second }
        .firstOrNull { sameSubnet(it.address, remote, it.networkPrefixLength.toInt()) }
        ?.address

    internal fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix !in 1..x.size * 8) return false
        var bits = prefix
        for (i in x.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
