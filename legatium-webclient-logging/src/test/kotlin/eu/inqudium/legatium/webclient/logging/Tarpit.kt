package eu.inqudium.legatium.webclient.logging

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.SocketChannel

/**
 * A peer whose TCP connect never completes - for provoking a connector's CONNECT timeout on loopback,
 * without a non-routable address (which a network may answer with a reset or an ICMP unreachable,
 * turning the timeout into a refusal).
 *
 * Mechanism: a listening socket with the smallest backlog that is never accepted from. Linux admits
 * `backlog + 1` established connections into the accept queue; once it is full, further SYNs are
 * DROPPED (not reset, `tcp_abort_on_overflow=0` by default), so the client's connect hangs in SYN
 * retransmit - the first retransmit comes after a second, longer than any connect timeout under test.
 * The fillers are non-blocking connects: the first ones complete into the queue, the rest hang, none
 * is waited for.
 */
internal class Tarpit : AutoCloseable {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 1, loopback)
    private val fillers =
        List(FILLERS) {
            SocketChannel.open().apply {
                configureBlocking(false)
                connect(InetSocketAddress(loopback, server.localPort))
            }
        }

    val baseUrl: String
        get() = "http://${loopback.hostAddress}:${server.localPort}"

    override fun close() {
        fillers.forEach { runCatching { it.close() } }
        server.close()
    }

    private companion object {
        /** Well above what any accept queue of backlog 1 admits. */
        const val FILLERS = 8
    }
}
