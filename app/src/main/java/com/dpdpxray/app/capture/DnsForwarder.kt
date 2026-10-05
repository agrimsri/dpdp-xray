package com.dpdpxray.app.capture

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Relays one DNS query to a real resolver and returns the raw answer.
 * This is the only network socket in the app: it carries the audited app's own lookups, nothing else.
 */
class DnsForwarder(private val context: Context, private val protect: (DatagramSocket) -> Boolean) {

    fun resolve(query: ByteArray): ByteArray? {
        for (server in upstreamServers()) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = TIMEOUT_MS
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val buf = ByteArray(4096)
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    return buf.copyOf(reply.length)
                }
            } catch (_: SocketTimeoutException) {
                // try the next resolver
            } catch (_: Exception) {
                // network changed or resolver unreachable; try the next one
            }
        }
        return null
    }

    /** The underlying network's resolvers (this app is not routed through its own VPN), then public fallbacks. */
    private fun upstreamServers(): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val system = runCatching {
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
        }.getOrDefault(emptyList())
        val v4 = system.filterIsInstance<Inet4Address>()
        return (v4 + FALLBACKS.map { InetAddress.getByName(it) }).distinct()
    }

    companion object {
        private const val TIMEOUT_MS = 2500
        private val FALLBACKS = listOf("8.8.8.8", "1.1.1.1")
    }
}
